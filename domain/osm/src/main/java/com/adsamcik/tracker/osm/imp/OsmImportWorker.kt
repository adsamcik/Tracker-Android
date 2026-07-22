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
import com.adsamcik.tracker.osm.io.OsmParseProgress
import com.adsamcik.tracker.osm.io.OsmParseStats
import com.adsamcik.tracker.osm.io.OsmPbfStreamingParser
import com.adsamcik.tracker.osm.io.ParsedOsmWay
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.OsmImportEntity
import com.adsamcik.tracker.shared.base.database.data.OsmWayCellEntity
import com.adsamcik.tracker.shared.base.database.data.OsmWayEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Foreground [CoroutineWorker] for the legacy user-selected `.osm.pbf` path.
 *
 * The current parser is not safe for untrusted input. Consequently, the
 * centrally owned [OfflinePbfImportCapability] rejects release execution at
 * the first line of [doWork], before URI access, foreground work, private
 * files, parser invocation, or database insertion. The remaining code is
 * retained only for explicitly wired test characterization while safe intake
 * is independently reviewed.
 *
 * Inputs (via [WorkerParameters.getInputData]):
 *  - [KEY_FILE_URI] — required, the SAF content URI returned by the SAF picker.
 *  - [KEY_DISPLAY_NAME] — required, the user-facing region name (e.g. "Prague").
 *  - [KEY_FILE_SIZE] — required only by gated characterization wiring.
 *
 * Outputs:
 *  - [KEY_OUT_WAY_COUNT], [KEY_OUT_NODE_COUNT] — populated on success.
 *
 * Lifecycle:
 *  - Promotes itself to a foreground service via [setForeground] before
 *    reading. Foreground type `dataSync` (declared in osm/AndroidManifest.xml)
 *    is the Android 14+ requirement for "process a user-provided file" tasks.
 *  - Inserts the `osm_import` header row as BUILDING so all [OsmWayEntity]
 *    rows can FK to it as they are streamed in. The final metadata and READY
 *    publication state are committed together only after the parser drains.
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
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
	private val offlinePbfImportCapability: OfflinePbfImportCapability,
) : CoroutineWorker(appContext, params) {

	private val emitBuffer = ArrayList<ParsedOsmWay>(DB_BATCH_SIZE)
	private var currentImportId: Long = 0L
	private var parser: OsmImportParser = StreamingOsmImportParser
	private var inputStreamOpener: (Uri) -> InputStream = ::openInputStreamOrThrow

	internal constructor(
		appContext: Context,
		params: WorkerParameters,
		appDatabase: AppDatabase,
		ioDispatcher: CoroutineDispatcher,
		parser: OsmImportParser,
		offlinePbfImportCapability: OfflinePbfImportCapability,
		inputStreamOpener: (Uri) -> InputStream = { uri ->
			appContext.contentResolver.openInputStream(uri)
				?: throw FileNotFoundException("content resolver returned no stream")
		},
	) : this(
		appContext,
		params,
		appDatabase,
		ioDispatcher,
		offlinePbfImportCapability,
	) {
		this.parser = parser
		this.inputStreamOpener = inputStreamOpener
	}

	override suspend fun getForegroundInfo(): ForegroundInfo {
		return buildForegroundInfo(0L)
	}

	override suspend fun doWork(): Result {
		if (!offlinePbfImportCapability.isAvailable) {
			return Result.failure(failureData(OsmImportFailureCode.PBF_IMPORT_UNAVAILABLE))
		}

		val rawUri = inputData.getString(KEY_FILE_URI)
			?: return Result.failure(failureData(OsmImportFailureCode.INVALID_REQUEST))
		val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: ""
		val fileSize = inputData.getLong(KEY_FILE_SIZE, -1L)
		if (fileSize <= 0L) return Result.failure(failureData(OsmImportFailureCode.INVALID_REQUEST))
		val uri = Uri.parse(rawUri)

		setForeground(buildForegroundInfo(0L))

		// Insert the header row up front so child ways can FK to it.
		currentImportId = withContext(ioDispatcher) {
			appDatabase.osmImportDao().insert(
				OsmImportEntity(
					displayName = displayName,
					fileUri = uri.toString(),
					importedAt = System.currentTimeMillis(),
					wayCount = 0L,
					nodeCount = 0L,
					diagnosticMinLatitudeE7 = 0,
					diagnosticMaxLatitudeE7 = 0,
					diagnosticMinLongitudeE7 = 0,
					diagnosticMaxLongitudeE7 = 0,
					wayBboxEncodingVersion = OsmImportEntity.WAY_BBOX_ENCODING_DIRECTED_V1,
					status = OsmImportEntity.STATUS_BUILDING,
				),
			)
		}

		return try {
			val stats = parser.parse(
				fileSizeBytes = fileSize,
				openInputStream = { inputStreamOpener(uri) },
				wayBatchSize = OsmPbfStreamingParser.DEFAULT_WAY_BATCH_SIZE,
				onProgress = { progress ->
					if (progress.phase == OsmParsePhase.EMIT_WAYS) {
						setForeground(buildForegroundInfo(progress.itemsProcessed))
					}
				},
				onWayBatch = { batch -> persistBatch(batch) },
			)
			flushBuffer()
			withContext(ioDispatcher) {
				val updated = appDatabase.osmImportDao().markReady(
					importId = currentImportId,
					wayCount = stats.wayCount,
					nodeCount = stats.nodeCount,
					diagnosticMinLatitudeE7 = stats.diagnosticMinLatitudeE7,
					diagnosticMaxLatitudeE7 = stats.diagnosticMaxLatitudeE7,
					diagnosticMinLongitudeE7 = stats.diagnosticMinLongitudeE7,
					diagnosticMaxLongitudeE7 = stats.diagnosticMaxLongitudeE7,
				)
				check(updated == 1) {
					"OSM import $currentImportId was not in BUILDING state during publication"
				}
			}

			NotificationManagerCompat.from(applicationContext).notify(
				OsmImportNotifications.NOTIFICATION_ID_COMPLETED,
				OsmImportNotifications.buildCompleted(applicationContext),
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
			notifyFailure()
			Result.failure(failureData(OsmImportFailureCode.PARSE_FAILED))
		} catch (io: IOException) {
			cleanupFailedImport()
			notifyFailure()
			Result.failure(failureData(OsmImportFailureCode.SOURCE_UNAVAILABLE))
		} catch (failure: Exception) {
			cleanupFailedImport()
			notifyFailure()
			Result.failure(failureData(OsmImportFailureCode.INTERNAL_ERROR))
		}
	}

	internal fun interface OsmImportParser {
		suspend fun parse(
			fileSizeBytes: Long,
			openInputStream: () -> InputStream,
			wayBatchSize: Int,
			onProgress: (suspend (OsmParseProgress) -> Unit)?,
			onWayBatch: suspend (List<ParsedOsmWay>) -> Unit,
		): OsmParseStats
	}

	private object StreamingOsmImportParser : OsmImportParser {
		override suspend fun parse(
			fileSizeBytes: Long,
			openInputStream: () -> InputStream,
			wayBatchSize: Int,
			onProgress: (suspend (OsmParseProgress) -> Unit)?,
			onWayBatch: suspend (List<ParsedOsmWay>) -> Unit,
		): OsmParseStats = OsmPbfStreamingParser().parse(
			fileSizeBytes = fileSizeBytes,
			openInputStream = openInputStream,
			wayBatchSize = wayBatchSize,
			onProgress = onProgress,
			onWayBatch = onWayBatch,
		)
	}

	private fun openInputStreamOrThrow(uri: Uri): InputStream {
		return applicationContext.contentResolver.openInputStream(uri)
			?: throw FileNotFoundException("content resolver returned no stream")
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
		withContext(ioDispatcher) {
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
			withContext(ioDispatcher) {
				appDatabase.osmImportDao().delete(id)
			}
		} catch (cleanup: Exception) {
			// Best-effort cleanup; never mask the original failure.
		}
	}

	private fun buildForegroundInfo(waysProcessed: Long): ForegroundInfo {
		val notification = OsmImportNotifications.buildProgress(
			applicationContext,
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

	private fun notifyFailure() {
		NotificationManagerCompat.from(applicationContext).notify(
			OsmImportNotifications.NOTIFICATION_ID_FAILED,
			OsmImportNotifications.buildFailed(applicationContext),
		)
	}

	private fun failureData(failureCode: OsmImportFailureCode): Data =
		Data.Builder().putString(KEY_OUT_ERROR_CODE, failureCode.name).build()

	companion object {
		const val KEY_FILE_URI: String = "osm_file_uri"
		const val KEY_DISPLAY_NAME: String = "osm_display_name"
		const val KEY_FILE_SIZE: String = "osm_file_size"
		const val KEY_OUT_WAY_COUNT: String = "osm_out_way_count"
		const val KEY_OUT_NODE_COUNT: String = "osm_out_node_count"
		const val KEY_OUT_IMPORT_ROW_ID: String = "osm_out_import_row_id"
		const val KEY_OUT_ERROR_CODE: String = "osm_out_error_code"

		const val UNIQUE_WORK_NAME: String = "osm_import"

		/** DB transaction size — chosen large enough to amortize COMMIT cost. */
		private const val DB_BATCH_SIZE: Int = 5_000

		/** Rough average cell count per way (urban dense ~ 1.5, rural ~ 2-3). */
		private const val AVG_CELLS_PER_WAY: Int = 2
	}
}
