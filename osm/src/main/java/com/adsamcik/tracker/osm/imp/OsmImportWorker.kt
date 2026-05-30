package com.adsamcik.tracker.osm.imp

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.adsamcik.tracker.osm.io.OsmParseException
import com.adsamcik.tracker.osm.io.OsmParsePhase
import com.adsamcik.tracker.osm.io.OsmPbfStreamingParser
import com.adsamcik.tracker.osm.io.ParsedOsmWay
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.OsmImportEntity
import com.adsamcik.tracker.shared.base.database.data.OsmWayCellEntity
import com.adsamcik.tracker.shared.base.database.data.OsmWayEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Foreground [CoroutineWorker] that parses a user-selected `.osm.pbf` file
 * into the local [AppDatabase] (`osm_import` / `osm_way` / `osm_way_cell`).
 *
 * Inputs (via [WorkerParameters.getInputData]):
 *  - [KEY_FILE_URI] — required, the SAF content URI returned by the SAF picker.
 *  - [KEY_DISPLAY_NAME] — required, the user-facing region name (e.g. "Prague").
 *  - [KEY_FILE_SIZE] — required, the file size in bytes (used for the size guard
 *    BEFORE we open the stream, to fail fast on country-sized files).
 *
 * Outputs:
 *  - [KEY_OUT_WAY_COUNT], [KEY_OUT_NODE_COUNT] — populated on success.
 *
 * Lifecycle:
 *  - Promotes itself to a foreground service via [setForeground] before
 *    reading. Foreground type `dataSync` (declared in osm/AndroidManifest.xml)
 *    is the Android 14+ requirement for "process a user-provided file" tasks.
 *  - Inserts the `osm_import` header row with placeholder counts FIRST so all
 *    [OsmWayEntity] rows can FK to it as they are streamed in. The header row
 *    is then finalized via [com.adsamcik.tracker.shared.base.database.dao.OsmImportDao.updateCounts]
 *    after the parser drains.
 *  - Wraps DB writes in [DB_BATCH_SIZE] transactions. A larger batch than the
 *    parser's emit size keeps INSERT contention low.
 *  - On WorkManager cancellation the parser polls cooperatively via its
 *    `ensureActive()` checkpoints; the in-progress import row is deleted to
 *    avoid leaving a half-imported region in the UI list.
 */
@HiltWorker
class OsmImportWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val appDatabase: AppDatabase,
) : CoroutineWorker(appContext, params) {

	private val emitBuffer = ArrayList<ParsedOsmWay>(DB_BATCH_SIZE)
	private var currentImportId: Long = 0L

	override suspend fun getForegroundInfo(): ForegroundInfo {
		val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: ""
		return buildForegroundInfo(displayName, 0L)
	}

	override suspend fun doWork(): Result {
		val rawUri = inputData.getString(KEY_FILE_URI)
			?: return Result.failure(failureData("missing uri"))
		val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: ""
		val fileSize = inputData.getLong(KEY_FILE_SIZE, -1L)
		if (fileSize <= 0L) return Result.failure(failureData("missing file size"))
		val uri = Uri.parse(rawUri)

		setForeground(buildForegroundInfo(displayName, 0L))

		// Insert the header row up front so child ways can FK to it.
		currentImportId = withContext(Dispatchers.IO) {
			appDatabase.osmImportDao().insert(
				OsmImportEntity(
					displayName = displayName,
					fileUri = uri.toString(),
					importedAt = System.currentTimeMillis(),
					wayCount = 0L,
					nodeCount = 0L,
					minLatE7 = 0,
					maxLatE7 = 0,
					minLonE7 = 0,
					maxLonE7 = 0,
				),
			)
		}

		val parser = OsmPbfStreamingParser()
		return try {
			val stats = parser.parse(
				fileSizeBytes = fileSize,
				openInputStream = { openInputStreamOrThrow(uri) },
				wayBatchSize = OsmPbfStreamingParser.DEFAULT_WAY_BATCH_SIZE,
				onProgress = { progress ->
					if (progress.phase == OsmParsePhase.EMIT_WAYS) {
						setForeground(buildForegroundInfo(displayName, progress.itemsProcessed))
					}
				},
				onWayBatch = { batch -> persistBatch(batch) },
			)
			flushBuffer()
			withContext(Dispatchers.IO) {
				appDatabase.osmImportDao().updateCounts(
					importId = currentImportId,
					wayCount = stats.wayCount,
					nodeCount = stats.nodeCount,
					minLatE7 = stats.minLatE7,
					maxLatE7 = stats.maxLatE7,
					minLonE7 = stats.minLonE7,
					maxLonE7 = stats.maxLonE7,
				)
			}
			NotificationManagerCompat.from(applicationContext).notify(
				OsmImportNotifications.NOTIFICATION_ID_COMPLETED,
				OsmImportNotifications.buildCompleted(applicationContext, displayName),
			)
			Result.success(
				Data.Builder()
					.putLong(KEY_OUT_WAY_COUNT, stats.wayCount)
					.putLong(KEY_OUT_NODE_COUNT, stats.nodeCount)
					.putLong(KEY_OUT_IMPORT_ROW_ID, currentImportId)
					.build(),
			)
		} catch (cancelled: CancellationException) {
			cleanupFailedImport()
			throw cancelled
		} catch (parse: OsmParseException) {
			cleanupFailedImport()
			notifyFailure(displayName, parse.message ?: "Parse error")
			Result.failure(failureData(parse.message ?: "parse error"))
		} catch (io: FileNotFoundException) {
			cleanupFailedImport()
			notifyFailure(displayName, io.message ?: "File not found")
			Result.failure(failureData(io.message ?: "file not found"))
		} catch (t: Throwable) {
			cleanupFailedImport()
			notifyFailure(displayName, t.message ?: t.javaClass.simpleName)
			Result.failure(failureData(t.message ?: t.javaClass.simpleName))
		}
	}

	private fun openInputStreamOrThrow(uri: Uri): InputStream {
		return applicationContext.contentResolver.openInputStream(uri)
			?: throw FileNotFoundException("contentResolver returned null for $uri")
	}

	private suspend fun persistBatch(batch: List<ParsedOsmWay>) {
		if (batch.isEmpty()) return
		emitBuffer.addAll(batch)
		if (emitBuffer.size >= DB_BATCH_SIZE) {
			flushBuffer()
		}
	}

	private suspend fun flushBuffer() {
		if (emitBuffer.isEmpty()) return
		val snapshot = emitBuffer.toList()
		emitBuffer.clear()
		val importId = currentImportId
		withContext(Dispatchers.IO) {
			val wayEntities = ArrayList<OsmWayEntity>(snapshot.size)
			val cellEntities = ArrayList<OsmWayCellEntity>(snapshot.size * AVG_CELLS_PER_WAY)
			for (way in snapshot) {
				wayEntities.add(
					OsmWayEntity(
						id = way.osmId,
						importId = importId,
						name = way.name,
						roadClass = way.roadClass.name,
						maxspeedKmh = way.maxspeedKmh,
						maxspeedExplicit = if (way.maxspeedExplicit) 1 else 0,
						isOneway = if (way.isOneway) 1 else 0,
						geomPolylineE7 = way.geomPolylineE7,
						bboxMinLatE7 = way.bboxMinLatE7,
						bboxMaxLatE7 = way.bboxMaxLatE7,
						bboxMinLonE7 = way.bboxMinLonE7,
						bboxMaxLonE7 = way.bboxMaxLonE7,
					),
				)
				for (cellKey in way.cellKeys) {
					cellEntities.add(
						OsmWayCellEntity(cellKey = cellKey, wayId = way.osmId),
					)
				}
			}
			appDatabase.osmWayDao().insertAll(wayEntities)
			appDatabase.osmWayCellDao().insertAll(cellEntities)
		}
	}

	private suspend fun cleanupFailedImport() {
		val id = currentImportId
		if (id <= 0L) return
		try {
			withContext(Dispatchers.IO) {
				appDatabase.osmImportDao().delete(id)
			}
		} catch (cleanup: Throwable) {
			// Best-effort cleanup; never mask the original failure.
		}
	}

	private fun buildForegroundInfo(displayName: String, waysProcessed: Long): ForegroundInfo {
		val notification = OsmImportNotifications.buildProgress(
			applicationContext,
			displayName,
			waysProcessed,
		)
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			ForegroundInfo(
				OsmImportNotifications.NOTIFICATION_ID,
				notification,
				ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
			)
		} else {
			ForegroundInfo(OsmImportNotifications.NOTIFICATION_ID, notification)
		}
	}

	private fun notifyFailure(displayName: String, reason: String) {
		NotificationManagerCompat.from(applicationContext).notify(
			OsmImportNotifications.NOTIFICATION_ID_FAILED,
			OsmImportNotifications.buildFailed(applicationContext, displayName, reason),
		)
	}

	private fun failureData(message: String): Data =
		Data.Builder().putString(KEY_OUT_ERROR, message).build()

	companion object {
		const val KEY_FILE_URI: String = "osm_file_uri"
		const val KEY_DISPLAY_NAME: String = "osm_display_name"
		const val KEY_FILE_SIZE: String = "osm_file_size"
		const val KEY_OUT_WAY_COUNT: String = "osm_out_way_count"
		const val KEY_OUT_NODE_COUNT: String = "osm_out_node_count"
		const val KEY_OUT_IMPORT_ROW_ID: String = "osm_out_import_row_id"
		const val KEY_OUT_ERROR: String = "osm_out_error"

		const val UNIQUE_WORK_NAME: String = "osm_import"

		/** DB transaction size — chosen large enough to amortize COMMIT cost. */
		private const val DB_BATCH_SIZE: Int = 5_000

		/** Rough average cell count per way (urban dense ~ 1.5, rural ~ 2-3). */
		private const val AVG_CELLS_PER_WAY: Int = 2
	}
}
