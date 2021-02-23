package com.adsamcik.tracker.export

import android.annotation.SuppressLint
import android.content.Context
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Exports locations to KML format.
 */
class KmlExporter : Exporter {
	override val canSelectDateRange: Boolean = true
	override val mimeType: String = "application/vnd.google-earth.kml+xml"
	override val extension: String = "kml"

	override fun export(
			context: Context,
			locationData: List<DatabaseLocation>,
			outputStream: OutputStream
	): ExportResult {
		serialize(outputStream, locationData)

		return ExportResult(true)
	}


	private fun serialize(
			stream: OutputStream,
			locationData: List<DatabaseLocation>
	) {
		OutputStreamWriter(stream).use { osw ->
			writeBeginning(osw)
			locationData.forEach { writeLocation(osw, it.location) }
			writeEnding(osw)
		}
	}

	@SuppressLint("SimpleDateFormat")
	private fun formatTime(time: Long): String {
		val date = Date(time)
		val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
		return format.format(date)
	}

	private fun writeLocation(streamWriter: OutputStreamWriter, location: Location) {
		streamWriter.write("<Placemark><TimeStamp><when>${formatTime(location.time)}</when></TimeStamp>")
		streamWriter.write(
				"""<Point><coordinates>${location.longitude},${location.latitude},
					|${location.altitude}</coordinates></Point></Placemark>""".trimMargin()
		)
	}

	private fun writeBeginning(streamWriter: OutputStreamWriter) {
		streamWriter.write(
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>"
		)
	}

	private fun writeEnding(streamWriter: OutputStreamWriter) {
		streamWriter.write("</Document></kml>")
	}
}

