package com.adsamcik.signalcollector.exports

import android.annotation.SuppressLint
import android.os.Build
import com.adsamcik.signalcollector.BuildConfig
import com.adsamcik.signalcollector.data.Location
import com.adsamcik.signalcollector.database.data.DatabaseLocation
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*

class GpxExport : IExport {
	override fun export(locationData: List<DatabaseLocation>, destinationDirectory: File, desiredName: String): ExportResult {
		val targetFile = File(destinationDirectory, "$desiredName.gpx")
		serialize(targetFile, locationData)

		return ExportResult(targetFile, "application/gpx+xml")
	}


	fun serialize(file: File, locationData: List<DatabaseLocation>) {
		FileOutputStream(file, false).let { outputStream ->
			outputStream.channel.lock()
			OutputStreamWriter(outputStream).use { osw ->
				writeBeginning(osw, locationData)
				locationData.forEach { writeLocation(osw, it.location) }
				writeEnding(osw)
			}
		}
	}

	@SuppressLint("SimpleDateFormat")
	private fun formatTime(time: Long): String {
		val date = Date(time)
		val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
		return format.format(date)
	}

	private fun writeLocation(streamWriter: OutputStreamWriter, location: Location) {
		streamWriter.write("<trkpt lat=\"${location.latitude}\" lon=\"${location.longitude}\">" +
				"<ele>${location.altitude}</ele>" +
				"<time>${formatTime(location.time)}</time>" +
				"<fix>3d</fix>" +
				"<pdop>${location.horizontalAccuracy / 5f}</pdop>" +
				"</trkpt>\n")
	}

	private fun writeBeginning(streamWriter: OutputStreamWriter, locationData: List<DatabaseLocation>) {
		val minLat = locationData.minBy { it.latitude }!!.latitude
		val maxLat = locationData.maxBy { it.latitude }!!.latitude
		val minLon = locationData.minBy { it.longitude }!!.longitude
		val maxLon = locationData.maxBy { it.longitude }!!.longitude

		streamWriter.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\" ?>\n" +
				"<gpx version=\"1.1\" creator=\"Signals ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"http://www.topografix.com/GPX/1/1\" xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n" +
				"<metadata>\n" +
				"<name>Signals log from ${Build.MANUFACTURER} ${Build.DEVICE}</name>\n" +
				"<time>${formatTime(System.currentTimeMillis())}</time>\n" +
				"<bounds minlat=\"$minLat\" maxlat=\"$maxLat\" minlon=\"$minLon\" maxlon=\"$maxLon\"/>\n" +
				"</metadata>\n" +
				"<trk>\n" +
				"<trkseg>\n"
		)
	}

	private fun writeEnding(streamWriter: OutputStreamWriter) {
		streamWriter.write("</trkseg></trk></gpx>")
	}
}