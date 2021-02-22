package com.adsamcik.tracker.export

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.shared.base.extension.applicationName
import com.adsamcik.tracker.shared.base.extension.formatAsDateTime
import com.anggrayudi.storage.file.openOutputStream
import io.jenetics.jpx.GPX
import io.jenetics.jpx.WayPoint

class GpxExporter : Exporter {
	override val canSelectDateRange: Boolean = true

	override fun export(
			context: Context,
			locationData: List<DatabaseLocation>,
			destinationDirectory: DocumentFile,
			desiredName: String
	): ExportResult {
		val fileName = "$desiredName.gpx"
		val targetFile = destinationDirectory.findFile(fileName) ?: destinationDirectory.createFile(
				mime, fileName
		) ?: throw RuntimeException("Could not access or create file $fileName")
		serialize(context, targetFile, locationData)
		return ExportResult(targetFile, mime)
	}


	private fun serialize(
			context: Context,
			file: DocumentFile,
			locationData: List<DatabaseLocation>
	) {
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

		// todo report if something is wrong
		file.openOutputStream(context)?.use { outputStream ->
			GPX.write(gpx, outputStream)
		}
	}

	companion object {
		private const val mime = "application/gpx+xml"
	}
}

