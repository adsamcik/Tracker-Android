package com.adsamcik.tracker.statistics.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.adsamcik.tracker.feature.statistics.api.export.TripGpxExportResult
import com.adsamcik.tracker.feature.statistics.api.export.TripGpxExporter
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.stats.api.repository.LocationSampleRepository
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import java.io.File
import javax.inject.Inject

/**
 * Shared helper that exports a trip's location data through [TripGpxExporter]
 * and launches the system share sheet.
 *
 * Used by both the statistics list and trip detail screens so that GPX generation
 * logic is not duplicated.
 */
class GpxShareHelper @Inject constructor(
	private val locationSampleRepository: LocationSampleRepository,
	private val tripGpxExporter: TripGpxExporter,
	private val dispatchersProvider: DispatchersProvider,
	private val startupGate: TrackingStartupGate,
) {
	/**
	 * Exports location data for the given time range to a GPX file and opens
	 * the share sheet.
	 *
	 * @param context Android context for file I/O and starting the share intent
	 * @param tripId Used to name the exported file
	 * @param startTimeMs Trip start time in epoch millis
	 * @param endTimeMs Trip end time in epoch millis
	 * @return [TripGpxExportResult] indicating success or failure
	 */
	suspend fun exportAndShare(
		context: Context,
		tripId: Long,
		startTimeMs: Long,
		endTimeMs: Long,
	): TripGpxExportResult {
		val generation = startupGate.currentGeneration
		if (startupGate.reconcile() !is TrackingStartupResult.Ready) return TripGpxExportResult.Failure
		return startupGate.withReadyGenerationTransfer(generation) {
			exportAdmitted(context, tripId, startTimeMs, endTimeMs, generation)
		} ?: TripGpxExportResult.Failure
	}

	private suspend fun exportAdmitted(
		context: Context,
		tripId: Long,
		startTimeMs: Long,
		endTimeMs: Long,
		generation: Long,
	): TripGpxExportResult = withContext(dispatchersProvider.io) {
		val shareableDir = File(context.filesDir, SHARABLE_DIR).apply { mkdirs() }
		val file = File.createTempFile("trip_${tripId}_", ".gpx", shareableDir)
		var exportedPointCount = 0
		var handedOff = false
		try {

			val result = file.outputStream().use { outputStream ->
				tripGpxExporter.export(
					outputStream = outputStream,
					dateRange = startTimeMs..endTimeMs,
				) { emit ->
					exportedPointCount += streamSavedLocations(startTimeMs, endTimeMs, emit)
				}
			}

			if (result is TripGpxExportResult.Failure) return@withContext result
			if (exportedPointCount == 0) {
				return@withContext TripGpxExportResult.Success
			}

			if (result is TripGpxExportResult.Success) {
				val uri = FileProvider.getUriForFile(
					context,
					"${context.packageName}.fileprovider",
					file,
				)
				withContext(dispatchersProvider.main) {
					val shareIntent = Intent(Intent.ACTION_SEND).apply {
						type = MIME_TYPE_GPX
						putExtra(Intent.EXTRA_STREAM, uri)
						addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
					}
					handedOff = startupGate.withRetainedDataGeneration(generation) {
						context.startActivity(Intent.createChooser(shareIntent, null))
						true
					} == true
				}
			}

			if (handedOff) result else TripGpxExportResult.Failure
		} finally {
			if (!handedOff) withContext(NonCancellable + dispatchersProvider.io) {
				check(!file.exists() || file.delete()) { "Could not remove unfinished GPX share" }
			}
		}
	}

	private suspend fun streamSavedLocations(
		startTimeMs: Long, endTimeMs: Long,
		emit: (com.adsamcik.tracker.shared.model.LocationSample) -> Unit,
	): Int {
		var exportedPointCount = 0
		var afterTimeMs: Long? = null
		var afterId: Long? = null

		while (true) {
			val chunk = locationSampleRepository.getOrderedChunkBetween(
				fromMs = startTimeMs,
				toMs = endTimeMs,
				afterTimeMs = afterTimeMs,
				afterId = afterId,
				limit = LOCATION_EXPORT_CHUNK_SIZE,
			)
			if (chunk.isEmpty()) break

			chunk.forEach { sample ->
				afterTimeMs = sample.timeMs
				afterId = sample.id

				if (sample.latE7 != null && sample.lonE7 != null) {
					emit(sample)
					exportedPointCount++
				}
			}
		}
		return exportedPointCount
	}

	companion object {
		private const val SHARABLE_DIR = "sharable"
		private const val MIME_TYPE_GPX = "application/gpx+xml"
		private const val LOCATION_EXPORT_CHUNK_SIZE = 500
	}
}
