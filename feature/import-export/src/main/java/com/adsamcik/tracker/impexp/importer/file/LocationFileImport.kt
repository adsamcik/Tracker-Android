package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import androidx.room.withTransaction
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.portable.InvalidLocationFile
import com.adsamcik.tracker.impexp.portable.LocationFileJsonV1
import com.adsamcik.tracker.impexp.portable.LocationFileMeasurementV1
import com.adsamcik.tracker.impexp.portable.LocationFilePointV1
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationFileIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileArchivePointEntity
import com.adsamcik.tracker.shared.base.database.data.LocationPointCorrection
import com.adsamcik.tracker.shared.base.mapper.toModel
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Restores exact saved Location points; file labels never grant capture or background authority. */
internal class LocationFileImport : FileImport {
    override val supportedExtensions = listOf(LocationFileJsonV1.EXTENSION)
    override val transactionMode = ImportTransactionMode.IMPORTER_MANAGED

    override suspend fun import(context: Context, database: AppDatabase,
        stream: FileImportStream): ImportResult {
        stream.importReceipt ?: throw IOException("Location import needs a saved file job")
        val epoch = stream.requireOriginalDataEpoch()
        if (database.sourceEvidenceStateDao().get()?.collectedDataEpoch != epoch) {
            return invalid("Import stopped because recorded data was cleared")
        }
        val stage = File.createTempFile("tracker-location-import-", ".tmp", context.cacheDir)
        var importFailure: Throwable? = null
        try {
            stage.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read == 0) {
                        val single = stream.read()
                        if (single < 0) break
                        total = Math.addExact(total, 1L)
                        if (total > LocationFileJsonV1.MAX_FILE_BYTES) {
                            return invalid("Location file exceeds its size limit")
                        }
                        output.write(single)
                        continue
                    }
                    total = Math.addExact(total, read.toLong())
                    if (total > LocationFileJsonV1.MAX_FILE_BYTES) {
                        return invalid("Location file exceeds its size limit")
                    }
                    output.write(buffer, 0, read)
                }
            }
            if (database.sourceEvidenceStateDao().get()?.collectedDataEpoch != epoch) {
                return invalid("Import stopped because recorded data was cleared")
            }
            return LocationPointIdIndex.open(context.cacheDir).use { pointIds ->
                val preview = try {
                    stage.inputStream().buffered().use { input ->
                        inspectLocationFile(database, input, epoch, pointIds)
                    }
                } catch (invalid: InvalidLocationFile) {
                    return@use invalid(invalid.message ?: "Invalid Location file")
                }
                val approved = stream.locationReview
                if (approved != null && (
                    approved.preview.fileJobId != preview.fileJobId ||
                    approved.preview.dataEpoch != epoch ||
                    approved.preview.pointCount != preview.pointCount ||
                    approved.preview.conflictCount != preview.conflictCount ||
                    approved.preview.existingStateHash != preview.existingStateHash
                )) return@use invalid("Location file or saved points changed; review it again")
                if (approved == null && preview.conflictCount > 0) {
                    return@use invalid("Location file changes saved points; review it before importing")
                }
                pointIds.finish()
                importValidatedFile(database, stage, epoch, preview, pointIds,
                    approved?.choice ?: LocationFileConflictChoice.KEEP_EXISTING)
            }
        } catch (failure: Throwable) {
            importFailure = failure
            throw failure
        } finally {
            try {
                deleteOwnedLocationTempFile(stage, "Location import")
            } catch (cleanup: Throwable) {
                val original = importFailure
                if (original == null) throw cleanup
                original.addSuppressed(cleanup)
            }
        }
    }

    private suspend fun importValidatedFile(database: AppDatabase, stage: File, epoch: Long,
        preview: LocationFileImportPreview,
        pointIds: LocationPointIdIndex, choice: LocationFileConflictChoice): ImportResult {
        var result = ImportResult.EMPTY
        val batch = ArrayList<LocationFilePointV1>(256)
        var firstOrdinal = 0
        val archive = LocationFileArchiveEntity(preview.fileJobId, LocationFileJsonV1.FORMAT,
            LocationFileJsonV1.VERSION, epoch, preview.pointCount)
        suspend fun writeBatch() {
            if (batch.isEmpty()) return
            currentCoroutineContext().ensureActive()
            result += database.withTransaction { writeBatchInTransaction(database, batch, epoch,
                pointIds, choice, archive, firstOrdinal) }
            firstOrdinal += batch.size
            batch.clear()
        }
        return try {
            // The file was validated in full before creating its source-owned association.
            database.withTransaction {
                if (database.sourceEvidenceStateDao().get()?.collectedDataEpoch != epoch) {
                    throw LocationImportChangedDuringWrite()
                }
                database.locationFileIdentityDao().recordArchive(archive)
            }
            stage.inputStream().buffered().use { input ->
                LocationFileJsonV1().read(input) { point ->
                    batch += point
                    if (batch.size == 256) writeBatch()
                }
            }
            writeBatch()
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (changed: LocationImportChangedDuringWrite) {
            result + invalid("Saved Location points changed during import; review and retry")
        } catch (invalid: InvalidLocationFile) {
            result + invalid(invalid.message ?: "Invalid Location file")
        }
    }

    private suspend fun writeBatchInTransaction(database: AppDatabase,
        batch: List<LocationFilePointV1>, epoch: Long, pointIds: LocationPointIdIndex,
        choice: LocationFileConflictChoice, archive: LocationFileArchiveEntity,
        firstOrdinal: Int): ImportResult {
        val evidence = database.sourceEvidenceStateDao().get()
            ?: throw LocationImportChangedDuringWrite()
        if (evidence.collectedDataEpoch != epoch) throw LocationImportChangedDuringWrite()
        val ids = batch.map(LocationFilePointV1::pointId)
        val expected = pointIds.expectedExisting(ids)
        val identities = database.locationFileIdentityDao().byPortableIds(ids)
            .associateBy { it.portableId }
        val existing = if (identities.isEmpty()) {
            emptyMap()
        } else {
            database.locationSampleDao().effectiveByIds(identities.values.map { it.sampleId })
                .associateBy { it.id }
        }
        var inserted = 0
        var skipped = 0
        val memberships = ArrayList<LocationFileArchivePointEntity>(batch.size)
        batch.forEachIndexed { index, point ->
            if (evidence.retainedFromMs?.let { point.measurement.timeMs < it } == true) {
                memberships += LocationFileArchivePointEntity(archive.identity,
                    firstOrdinal + index, point.pointId, null)
                skipped++
                return@forEachIndexed
            }
            val identity = identities[point.pointId]
            val sample = identity?.let { existing[it.sampleId] }
            val now = sample?.let { LocationFileMeasurementV1.from(it.toModel()) }
            val before = expected[point.pointId] ?: throw LocationImportChangedDuringWrite()
            if (before.first != (identity != null) ||
                before.second != now?.toString() || (identity != null && sample == null)) {
                throw LocationImportChangedDuringWrite()
            }
            if (identity == null) {
                val savedId = database.locationSampleDao().insert(point.toImportedSample(System.currentTimeMillis()))
                if (savedId <= 0L) throw IOException("Could not save Location point")
                val identityId = database.locationFileIdentityDao().insertIfAbsent(
                    LocationFileIdentityEntity(point.pointId, savedId, imported = true,
                        reportedOrigin = point.origin.name.lowercase()),
                )
                if (identityId <= 0L) throw LocationImportChangedDuringWrite()
                memberships += LocationFileArchivePointEntity(archive.identity,
                    firstOrdinal + index, point.pointId, savedId)
                inserted++
            } else if (now == point.measurement || choice == LocationFileConflictChoice.KEEP_EXISTING) {
                memberships += LocationFileArchivePointEntity(archive.identity,
                    firstOrdinal + index, point.pointId, identity.sampleId)
                skipped++
            } else {
                val value = point.toImportedSample(System.currentTimeMillis())
                val nextRevision = Math.addExact(evidence.revision, 1L)
                database.locationPointCorrectionDao().replace(LocationPointCorrection.from(
                    identity.sampleId, epoch, nextRevision, value))
                memberships += LocationFileArchivePointEntity(archive.identity,
                    firstOrdinal + index, point.pointId, identity.sampleId)
                inserted++
            }
        }
        database.locationFileIdentityDao().recordArchivePoints(memberships)
        if (inserted > 0) database.sourceEvidenceStateDao().incrementRevision(System.currentTimeMillis())
        if (database.sourceEvidenceStateDao().get()?.collectedDataEpoch != epoch) {
            throw LocationImportChangedDuringWrite()
        }
        return ImportResult(successCount = inserted, skippedCount = skipped)
    }

    companion object {
        fun clearOwnedStaging(context: Context) {
            context.cacheDir.listFiles().orEmpty().filter { it.isFile && OWNED_NAME.matches(it.name) }
                .forEach { file ->
                    if (!file.delete() && file.exists()) {
                        throw IOException("Could not remove a temporary Location import")
                    }
                }
        }

        private val OWNED_NAME = Regex("tracker-location-import-.*\\.tmp")
        private fun invalid(reason: String) = ImportResult(failedCount = 1, errors = listOf(reason))
    }
}

private class LocationImportChangedDuringWrite : IOException()
