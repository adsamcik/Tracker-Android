package com.adsamcik.tracker.export

import android.annotation.SuppressLint
import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.anggrayudi.storage.file.findFileLiterally
import com.anggrayudi.storage.file.openOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*

class KmlExporter : Exporter {
	override val canSelectDateRange: Boolean = true

	override fun export(
			context: Context,
			locationData: List<DatabaseLocation>,
			destinationDirectory: DocumentFile,
			desiredName: String
	): ExportResult {
		val targetFile = destinationDirectory.findFileLiterally(desiredName)
				?: destinationDirectory.createFile(mime, desiredName)
				?: throw RuntimeException("Could not access or create file $desiredName")
		serialize(context, targetFile, locationData)

		return ExportResult(targetFile, mime)
	}


	private fun serialize(
			context: Context,
			file: DocumentFile,
			locationData: List<DatabaseLocation>
	) {
		file.openOutputStream(context).use { outputStream ->
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
		streamWriter.write(
				"<Point><coordinates>${location.longitude},${location.latitude},${location.altitude}</coordinates></Point></Placemark>"
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

	companion object {
		private const val mime = "application/vnd.google-earth.kml+xml"
	}
}

