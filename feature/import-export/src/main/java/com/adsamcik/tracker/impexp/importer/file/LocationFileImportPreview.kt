package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.importer.ImportAdmissionEntryPoint
import com.adsamcik.tracker.impexp.portable.InvalidLocationFile
import com.adsamcik.tracker.impexp.portable.LocationFileJsonV1
import com.adsamcik.tracker.impexp.portable.LocationFileMeasurementV1
import com.adsamcik.tracker.impexp.portable.LocationFilePointV1
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationFileIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.extension.openInputStream
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import dagger.hilt.android.EntryPointAccessors
import java.io.IOException
import java.io.InputStream
import java.io.Closeable
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The conflict choice is bound to these exact file bytes and the current recorded-data epoch. */
data class LocationFileImportPreview(
    val pointCount: Int,
    val conflictCount: Int,
    val matchingCount: Int,
    val fileJobId: String,
    val dataEpoch: Long,
    val existingStateHash: String,
)

/** One import-wide choice for changed values sharing a saved Location point ID. */
enum class LocationFileConflictChoice { KEEP_EXISTING, REPLACE_WITH_FILE }

/** Pairs the exact file and saved-data preview with the user choice for all conflicting points. */
data class LocationFileImportReview(
    val preview: LocationFileImportPreview,
    val choice: LocationFileConflictChoice,
)

/** Reads and validates the whole file before a user can choose how to handle edited point IDs. */
object LocationFileImportPreviewer {
    /**
     * Validates the whole Location file and counts duplicates/conflicts against the current epoch; throws if
     * storage or data changes.
     */
    suspend fun inspect(context: Context, uri: Uri): LocationFileImportPreview {
        val graph = EntryPointAccessors.fromApplication(
            context.applicationContext, ImportAdmissionEntryPoint::class.java,
        )
        val gate = graph.trackingStartupGate()
        val generation = gate.currentGeneration
        if (gate.reconcile() !is TrackingStartupResult.Ready) {
            throw IOException("Tracking data is not ready")
        }
        return gate.withReadyGenerationTransfer(generation) {
            withContext(graph.dispatchersProvider().io) {
                inspectReadyFile(context, uri, graph)
            }
        } ?: throw IOException("Recorded data changed before checking the Location file")
    }

    private suspend fun inspectReadyFile(context: Context, uri: Uri,
        graph: ImportAdmissionEntryPoint): LocationFileImportPreview {
        val file = requireLocationFile(context, uri)
        val epoch = graph.collectedDataLifecycleStore().snapshot().epoch
        val database = AppDatabase.database(context)
        val preview = LocationPointIdIndex.open(context.cacheDir).use { pointIds ->
            val source = file.openInputStream(context)
                ?: throw IOException("Could not read Location file")
            source.use { input -> inspectLocationFile(database, input, epoch, pointIds) }
        }
        if (graph.collectedDataLifecycleStore().snapshot().epoch != epoch) {
            throw IOException("Recorded data changed while checking the Location file")
        }
        return preview
    }

    private fun requireLocationFile(context: Context, uri: Uri): DocumentFile {
        val file = DocumentFile.fromSingleUri(context, uri)
            ?: throw IOException("Could not open Location file")
        if (!file.name.orEmpty().endsWith(".${LocationFileJsonV1.EXTENSION}", ignoreCase = true)) {
            throw InvalidLocationFile("Choose a Location file")
        }
        return file
    }
}

internal suspend fun inspectLocationFile(
    database: AppDatabase,
    source: InputStream,
    dataEpoch: Long,
    pointIds: LocationPointIdIndex,
): LocationFileImportPreview {
    val digest = MessageDigest.getInstance("SHA-256").apply {
        update("tracker-import-job-v1\u0000".toByteArray(Charsets.UTF_8))
        update(LocationFileJsonV1.EXTENSION.toByteArray(Charsets.UTF_8))
        update(0)
    }
    val batch = ArrayList<LocationFilePointV1>(256)
    val existingState = MessageDigest.getInstance("SHA-256").apply {
        update("tracker-location-existing-v1\u0000".toByteArray(Charsets.UTF_8))
    }
    var conflictCount = 0
    var matchingCount = 0
    suspend fun inspectBatch() {
        val counts = inspectLocationBatch(database, batch, pointIds, existingState)
        conflictCount += counts.first
        matchingCount += counts.second
        batch.clear()
    }
    val pointCount = DigestInputStream(source, digest).use { hashed ->
        LocationFileJsonV1().read(hashed) { point ->
            pointIds.add(point.pointId)
            batch += point
            if (batch.size == 256) {
                inspectBatch()
            }
        }
    }
    inspectBatch()
    return LocationFileImportPreview(
        pointCount = pointCount,
        conflictCount = conflictCount,
        matchingCount = matchingCount,
        fileJobId = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
        dataEpoch = dataEpoch,
        existingStateHash = existingState.digest().joinToString("") { byte -> "%02x".format(byte) },
    )
}

private suspend fun inspectLocationBatch(
    database: AppDatabase, batch: List<LocationFilePointV1>,
    pointIds: LocationPointIdIndex, existingState: MessageDigest,
): Pair<Int, Int> {
    var conflictCount = 0
    var matchingCount = 0
    if (batch.isEmpty()) {
        return 0 to 0
    }
    currentCoroutineContext().ensureActive()
    // SQLite transactions are thread-bound. Finish this parser batch before Room suspends.
    pointIds.finish()
    val identities = database.locationFileIdentityDao()
        .byPortableIds(batch.map(LocationFilePointV1::pointId))
        .associateBy { it.portableId }
    val samples = if (identities.isEmpty()) {
        emptyMap()
    } else {
        database.locationSampleDao().effectiveByIds(identities.values.map { it.sampleId })
            .associateBy { it.id }
    }
    for (point in batch) {
        val identity = identities[point.pointId]
        when (pointIds.inspectExistingPoint(point, identity,
            identity?.let { samples[it.sampleId] }, existingState)) {
            true -> matchingCount++
            false -> conflictCount++
            null -> Unit
        }
    }
    pointIds.finish()
    return conflictCount to matchingCount
}

private fun LocationPointIdIndex.inspectExistingPoint(
    point: LocationFilePointV1,
    identity: LocationFileIdentityEntity?,
    sample: LocationSample?,
    existingState: MessageDigest,
): Boolean? {
    if (identity == null) {
        return null
    }
    val saved = sample ?: throw InvalidLocationFile("A saved Location point is missing")
    val existing = LocationFileMeasurementV1.from(saved.toModel())
    recordExisting(point.pointId, existing.toString())
    existingState.updateLengthPrefixed(point.pointId)
    existingState.updateLengthPrefixed(existing.toString())
    return existing == point.measurement
}

private fun MessageDigest.updateLengthPrefixed(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    update((bytes.size ushr LENGTH_PREFIX_HIGH_BYTE_SHIFT).toByte())
    update((bytes.size ushr LENGTH_PREFIX_MIDDLE_HIGH_BYTE_SHIFT).toByte())
    update((bytes.size ushr LENGTH_PREFIX_MIDDLE_LOW_BYTE_SHIFT).toByte())
    update(bytes.size.toByte())
    update(bytes)
}

/** Private on-disk index bounds memory while rejecting repeated IDs anywhere in a large file. */
internal class LocationPointIdIndex private constructor(
    private val file: File,
    private val database: SQLiteDatabase,
    private val insert: SQLiteStatement,
    private val update: SQLiteStatement,
) : Closeable {
    fun add(pointId: String) {
        beginIfNeeded()
        insert.clearBindings()
        insert.bindString(1, pointId)
        if (insert.executeInsert() == -1L) {
            throw InvalidLocationFile("Location file repeats a point ID")
        }
    }

    fun recordExisting(pointId: String, measurement: String) {
        beginIfNeeded()
        update.clearBindings()
        update.bindString(1, measurement)
        update.bindString(2, pointId)
        check(update.executeUpdateDelete() == 1)
    }

    fun finish() {
        if (database.inTransaction()) {
            database.setTransactionSuccessful()
            database.endTransaction()
        }
    }

    private fun beginIfNeeded() {
        if (!database.inTransaction()) {
            database.beginTransactionNonExclusive()
        }
    }

    fun expectedExisting(pointIds: List<String>): Map<String, Pair<Boolean, String?>> {
        if (pointIds.isEmpty()) {
            return emptyMap()
        }
        val placeholders = List(pointIds.size) { "?" }.joinToString(",")
        return database.rawQuery(
            "SELECT point_id, existing_present, existing_measurement FROM seen " +
                "WHERE point_id IN ($placeholders)",
            pointIds.toTypedArray(),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getString(0),
                        (cursor.getInt(1) != 0) to if (cursor.isNull(2)) {
                            null
                        } else {
                            cursor.getString(2)
                        })
                }
            }
        }
    }

    override fun close() {
        var closeFailure: Throwable? = null
        try {
            closeIndexResources()
        } catch (
        	// Close all owned index files and propagate the original failure after cleanup.
        	@Suppress("TooGenericExceptionCaught") failure: Throwable
        ) {
            closeFailure = failure
        } finally {
            listOf(file, File(file.path + "-journal"), File(file.path + "-wal"),
                File(file.path + "-shm")).forEach { owned ->
                try {
                    deleteOwnedLocationTempFile(owned, "Location point index")
                } catch (
                	// Continue retiring sibling files while retaining every cleanup failure.
                	@Suppress("TooGenericExceptionCaught") cleanup: Throwable
                ) {
                    val original = closeFailure
                    if (original == null) {
                        closeFailure = cleanup
                    }
                    else {
                        original.addSuppressed(cleanup)
                    }
                }
            }
        }
        closeFailure?.let { throw it }
    }

    private fun closeIndexResources() {
        try {
            insert.close()
        } finally {
            try {
                update.close()
            } finally {
                closeIndexDatabase()
            }
        }
    }

    private fun closeIndexDatabase() {
        try {
            if (database.inTransaction()) {
                database.endTransaction()
            }
        } finally {
            database.close()
        }
    }

    companion object {
        /** Called after Clear all has fenced import work; never touches user-selected files. */
        fun clearOwnedStaging(context: Context) {
            context.cacheDir.listFiles().orEmpty().filter { file ->
                file.isFile && OWNED_NAME.matches(file.name)
            }.forEach { file ->
                if (!file.delete() && file.exists()) {
                    throw IOException("Could not remove a temporary Location index")
                }
            }
        }

        fun open(directory: File): LocationPointIdIndex {
            val file = File.createTempFile("tracker-location-point-ids-", ".db", directory)
            var opened: SQLiteDatabase? = null
            try {
                val database = SQLiteDatabase.openOrCreateDatabase(file, null)
                opened = database
                database.execSQL("CREATE TABLE seen (point_id TEXT PRIMARY KEY NOT NULL, " +
                    "existing_present INTEGER NOT NULL DEFAULT 0, existing_measurement TEXT)")
                val insert = database.compileStatement("INSERT OR IGNORE INTO seen(point_id) VALUES (?)")
                val update = database.compileStatement(
                    "UPDATE seen SET existing_present = 1, existing_measurement = ? WHERE point_id = ?",
                )
                return LocationPointIdIndex(file, database, insert, update)
            } catch (
            	// Index setup failure must release its partially acquired resources before rethrowing.
            	@Suppress("TooGenericExceptionCaught") failure: Exception
            ) {
                runCatching { opened?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                listOf(file, File(file.path + "-journal"), File(file.path + "-wal"),
                    File(file.path + "-shm")).forEach { owned ->
                    runCatching { deleteOwnedLocationTempFile(owned, "Location point index") }
                        .exceptionOrNull()?.let(failure::addSuppressed)
                }
                throw failure
            }
        }

        private val OWNED_NAME = Regex("tracker-location-point-ids-.*\\.db(?:-journal|-wal|-shm)?")
    }
}

/** A failed private-file removal is surfaced so the caller can retry cleanup. */
internal fun deleteOwnedLocationTempFile(file: File, label: String) {
    if (!file.delete() && file.exists()) {
        throw IOException("Could not remove temporary $label")
    }
}

private const val LENGTH_PREFIX_HIGH_BYTE_SHIFT = 24
private const val LENGTH_PREFIX_MIDDLE_HIGH_BYTE_SHIFT = 16
private const val LENGTH_PREFIX_MIDDLE_LOW_BYTE_SHIFT = 8
