package com.adsamcik.signalcollector.export

import android.annotation.SuppressLint
import com.adsamcik.signalcollector.database.data.DatabaseLocation
import com.adsamcik.signalcollector.tracker.data.collection.Location
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*

class KmlExport : IExport {
	override fun export(locationData: List<DatabaseLocation>, destinationDirectory: File, desiredName: String): ExportResult {
		val targetFile = File(destinationDirectory, "$desiredName.kml")
		serialize(targetFile, locationData)

		return ExportResult(targetFile, "application/vnd.google-earth.kml+xml")
	}


	fun serialize(file: File, locationData: List<DatabaseLocation>) {
		FileOutputStream(file, false).let { outputStream ->
			outputStream.channel.lock()
			OutputStreamWriter(outputStream).use { osw ->
				writeBeginning(osw)
				locationData.forEach { writeLocation(osw, it.location) }
				writeEnding(osw)
			}
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
		streamWriter.write("<Point><coordinates>${location.longitude},${location.latitude},${location.altitude}</coordinates></Point></Placemark>")
	}

	private fun writeBeginning(streamWriter: OutputStreamWriter) {
		streamWriter.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>")
	}

	private fun writeEnding(streamWriter: OutputStreamWriter) {
		streamWriter.write("</Document></kml>")
	}

}