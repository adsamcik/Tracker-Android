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
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.LocationPointCorrection
import com.adsamcik.tracker.shared.base.mapper.toModel
import java.io.File
import java.io.Closeable
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
        return Closeable { deleteOwnedLocationTempFile(stage, "Location import") }.use staged@{
            copyLocationInput(stream, stage)?.let { return@staged it }
            importStagedFile(context, database, stream, stage, epoch)
        }
    }

    private suspend fun copyLocationInput(stream: FileImportStream, stage: File): ImportResult? {
        stage.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = stream.readWithProgress(buffer)
                if (read < 0) {
                    break
                }
                total = Math.addExact(total, read.toLong())
                if (total > LocationFileJsonV1.MAX_FILE_BYTES) {
                    return invalid("Location file exceeds its size limit")
                }
                output.write(buffer, 0, read)
            }
        }
        return null
    }

    private suspend fun importStagedFile(context: Context, database: AppDatabase,
        stream: FileImportStream, stage: File, epoch: Long): ImportResult {
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
            locationReviewProblem(approved, preview, epoch)?.let { return@use invalid(it) }
            pointIds.finish()
            importValidatedFile(database, stage, epoch, preview, pointIds,
                approved?.choice ?: LocationFileConflictChoice.KEEP_EXISTING)
        }
    }

    private suspend fun importValidatedFile(database: AppDatabase, stage: File, epoch: Long,
        preview: LocationFileImportPreview,
        pointIds: LocationPointIdIndex, choice: LocationFileConflictChoice): ImportResult {
        var result = ImportResult.EMPTY
        val batch = ArrayList<LocationFilePointV1>(LOCATION_IMPORT_BATCH_SIZE)
        var firstOrdinal = 0
        val archive = LocationFileArchiveEntity(preview.fileJobId, LocationFileJsonV1.FORMAT,
            LocationFileJsonV1.VERSION, epoch, preview.pointCount)
        suspend fun writeBatch() {
            if (batch.isEmpty()) {
                return
            }
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
                    if (batch.size == LOCATION_IMPORT_BATCH_SIZE) {
                        writeBatch()
                    }
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
        val evidence = database.requireLocationImportEpoch(epoch)
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
            val (savedId, changed) = database.restoreLocationPoint(point, expected[point.pointId],
                identity, identity?.let { existing[it.sampleId] }, choice, evidence)
            memberships += LocationFileArchivePointEntity(archive.identity,
                firstOrdinal + index, point.pointId, savedId)
            if (changed) {
                inserted++
            } else {
                skipped++
            }
        }
        database.locationFileIdentityDao().recordArchivePoints(memberships)
        if (inserted > 0) {
            database.sourceEvidenceStateDao().incrementRevision(System.currentTimeMillis())
        }
        database.requireLocationImportEpoch(epoch)
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

private fun FileImportStream.readWithProgress(buffer: ByteArray): Int {
    val read = read(buffer)
    if (read != 0) {
        return read
    }
    val single = read()
    if (single < 0) {
        return -1
    }
    buffer[0] = single.toByte()
    return 1
}

private fun locationReviewProblem(approved: LocationFileImportReview?,
    preview: LocationFileImportPreview, epoch: Long): String? = when {
    approved != null && !approved.preview.matchesReviewedFile(preview, epoch) ->
        "Location file or saved points changed; review it again"
    approved == null && preview.conflictCount > 0 ->
        "Location file changes saved points; review it before importing"
    else -> null
}

private suspend fun AppDatabase.requireLocationImportEpoch(epoch: Long): SourceEvidenceState {
    val evidence = sourceEvidenceStateDao().get() ?: throw LocationImportChangedDuringWrite()
    if (evidence.collectedDataEpoch != epoch) {
        throw LocationImportChangedDuringWrite()
    }
    return evidence
}

private fun LocationFileImportPreview.matchesReviewedFile(other: LocationFileImportPreview, epoch: Long): Boolean {
    if (fileJobId != other.fileJobId || dataEpoch != epoch) {
        return false
    }
    return pointCount == other.pointCount && conflictCount == other.conflictCount &&
        existingStateHash == other.existingStateHash
}

private suspend fun AppDatabase.restoreLocationPoint(
    point: LocationFilePointV1,
    expected: Pair<Boolean, String?>?,
    identity: LocationFileIdentityEntity?,
    sample: LocationSample?,
    choice: LocationFileConflictChoice,
    evidence: SourceEvidenceState,
): Pair<Long, Boolean> {
    val now = sample?.let { LocationFileMeasurementV1.from(it.toModel()) }
    val before = expected ?: throw LocationImportChangedDuringWrite()
    if (before.first != (identity != null) ||
        before.second != now?.toString() || (identity != null && sample == null)) {
        throw LocationImportChangedDuringWrite()
    }
    return if (identity == null) {
        insertImportedLocationPoint(point) to true
    } else if (now == point.measurement || choice == LocationFileConflictChoice.KEEP_EXISTING) {
        identity.sampleId to false
    } else {
        val value = point.toImportedSample(System.currentTimeMillis())
        val nextRevision = Math.addExact(evidence.revision, 1L)
        locationPointCorrectionDao().replace(LocationPointCorrection.from(
            identity.sampleId, evidence.collectedDataEpoch, nextRevision, value))
        identity.sampleId to true
    }
}

private suspend fun AppDatabase.insertImportedLocationPoint(point: LocationFilePointV1): Long {
    val savedId = locationSampleDao().insert(point.toImportedSample(System.currentTimeMillis()))
    if (savedId <= 0L) {
        throw IOException("Could not save Location point")
    }
    val identityId = locationFileIdentityDao().insertIfAbsent(
        LocationFileIdentityEntity(point.pointId, savedId, imported = true,
            reportedOrigin = point.origin.name.lowercase()),
    )
    if (identityId <= 0L) {
        throw LocationImportChangedDuringWrite()
    }
    return savedId
}

private class LocationImportChangedDuringWrite : IOException()

private const val LOCATION_IMPORT_BATCH_SIZE = 256
