package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.extension.applicationName
import com.adsamcik.tracker.shared.base.extension.formatAsDateTime
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import io.jenetics.jpx.GPX
import io.jenetics.jpx.WayPoint
import java.io.IOException
import java.io.OutputStream

/**
 * Exports locations to GPX format.
 */
class GpxExporter : Exporter {
	override val canSelectDateRange: Boolean = true

	override val mimeType: String = "application/gpx+xml"

	override val extension: String = "gpx"

	override suspend fun export(
			context: Context,
			locationData: Sequence<LocationSample>,
			outputStream: OutputStream,
			dateRange: LongRange?
	): ExportResult {
		val gpxBuilder = GPX.builder()

		if (dateRange != null) {
			gpxBuilder.metadata {
				it.author(context.applicationName)
				it.desc(
						context.getString(
								R.string.export_gpx_description,
								dateRange.first.formatAsDateTime(),
								dateRange.last.formatAsDateTime()
						)
				)
			}
		}

		gpxBuilder.addTrack { track ->
			track.addSegment { segment ->
				locationData.forEach { sample ->
					val lat = sample.latE7 ?: return@forEach
					val lon = sample.lonE7 ?: return@forEach
					val latitude = lat / 1e7
					val longitude = lon / 1e7
					val altitude = sample.altitudeM?.toDouble()

					val waypoint = when {
						altitude != null -> WayPoint.of(
								latitude,
								longitude,
								altitude,
								sample.timeMs
						)
						else -> WayPoint.of(latitude, longitude, sample.timeMs)
					}

					segment.addPoint(waypoint)
				}
			}
		}

		val gpx = gpxBuilder.build()

		try {
			GPX.Writer.DEFAULT.write(gpx, outputStream)
		} catch (e: IOException) {
			val message = e.localizedMessage ?: e.message ?: e.javaClass.name
			return ExportResult.Error(LocalizedString(R.string.export_gpx_error, message))
		}

		return ExportResult.Success
	}

	companion object {
		private const val SPLIT_TRACKS_MINUTES = 45
	}
}

