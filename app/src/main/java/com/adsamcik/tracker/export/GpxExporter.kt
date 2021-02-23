package com.adsamcik.tracker.export

import android.content.Context
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.shared.base.extension.applicationName
import com.adsamcik.tracker.shared.base.extension.formatAsDateTime
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
			locationData: List<DatabaseLocation>,
			outputStream: OutputStream
	): ExportResult {
		val gpx = GPX.builder().metadata {
			it.author(context.applicationName)
			it.desc(
					context.getString(
							R.string.export_gpx_description,
							locationData.first().time.formatAsDateTime(),
							locationData.last().time.formatAsDateTime()
					)
			)
		}.addTrack { track ->
			track.addSegment { segment ->
				//todo add support for multiple segments
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
		}.build()

		try {
			GPX.write(gpx, outputStream)
		} catch (e: IOException) {
			return ExportResult(false, e.message)
		}

		return ExportResult(true)
	}

	companion object {
		private const val SPLIT_TRACKS_MINUTES = 45
	}
}

