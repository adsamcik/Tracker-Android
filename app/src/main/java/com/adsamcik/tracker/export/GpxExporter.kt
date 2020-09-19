package com.adsamcik.tracker.export

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.shared.base.extension.applicationName
import com.adsamcik.tracker.shared.base.extension.formatAsDateTime
import io.jenetics.jpx.GPX
import io.jenetics.jpx.WayPoint
import java.io.File
import java.io.FileOutputStream

@RequiresApi(Build.VERSION_CODES.O)
class GpxExporter : Exporter {
	override val canSelectDateRange: Boolean = true

	override fun export(
			context: Context,
			locationData: List<DatabaseLocation>,
			destinationDirectory: File,
			desiredName: String
	): ExportResult {
		val targetFile = File(destinationDirectory, "$desiredName.gpx")
		serialize(context, targetFile, locationData)
		return ExportResult(targetFile, "application/gpx+xml")
	}


	private fun serialize(context: Context, file: File, locationData: List<DatabaseLocation>) {
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

		FileOutputStream(file, false).let { outputStream ->
			GPX.write(gpx, outputStream)
		}
	}
}

