package com.adsamcik.tracker.statistics.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.GpxExporter
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.stats.api.repository.LocationSampleRepository
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Shared helper that exports a trip's location data to GPX via [GpxExporter]
 * and launches the system share sheet.
 *
 * Used by both the statistics list and trip detail screens so that GPX generation
 * logic is not duplicated.
 */
class GpxShareHelper @Inject constructor(
	private val locationSampleRepository: LocationSampleRepository,
	private val dispatchersProvider: DispatchersProvider,
) {
	/**
	 * Exports location data for the given time range to a GPX file and opens
	 * the share sheet.
	 *
	 * @param context Android context for file I/O and starting the share intent
	 * @param tripId Used to name the exported file
	 * @param startTimeMs Trip start time in epoch millis
	 * @param endTimeMs Trip end time in epoch millis
	 * @return [ExportResult] indicating success or failure
	 */
	suspend fun exportAndShare(
		context: Context,
		tripId: Long,
		startTimeMs: Long,
		endTimeMs: Long,
	): ExportResult = withContext(dispatchersProvider.io) {
		val exporter = GpxExporter()
		val shareableDir = File(context.filesDir, SHARABLE_DIR).apply { mkdirs() }
		val file = File(shareableDir, "trip_$tripId.gpx")
		var exportedPointCount = 0

		val result = file.outputStream().use { outputStream ->
			exporter.export(
				context = context,
				outputStream = outputStream,
				dateRange = startTimeMs..endTimeMs,
			) { emit ->
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
			}
		}

		if (exportedPointCount == 0) {
			file.delete()
			return@withContext ExportResult.Success
		}

		if (result is ExportResult.Success) {
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
				context.startActivity(Intent.createChooser(shareIntent, null))
			}
		}

		result
	}

	companion object {
		private const val SHARABLE_DIR = "sharable"
		private const val MIME_TYPE_GPX = "application/gpx+xml"
		private const val LOCATION_EXPORT_CHUNK_SIZE = 500
	}
}
