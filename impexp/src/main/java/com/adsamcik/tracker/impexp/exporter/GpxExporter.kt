package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
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

	override fun export(
			context: Context,
			locationData: Sequence<DatabaseLocation>,
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
				// Single segment per track; future enhancement: split on time gaps or motion state
				locationData.forEach {
					val altitude = it.altitude

					val waypoint = when {
						altitude != null -> WayPoint.of(
								it.latitude,
								it.longitude,
								altitude,
								it.time
						)
						else -> WayPoint.of(it.latitude, it.longitude, it.time)
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
			return ExportResult(false, LocalizedString(R.string.export_gpx_error, message))
		}

		return ExportResult(true)
	}

	companion object {
		private const val SPLIT_TRACKS_MINUTES = 45
	}
}

