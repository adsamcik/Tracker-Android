package com.adsamcik.tracker.impexp.exporter

import android.annotation.SuppressLint
import android.content.Context
import com.adsamcik.tracker.shared.base.database.data.LocationSample
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
			locationData: Sequence<LocationSample>,
			outputStream: OutputStream,
			dateRange: LongRange?
	): ExportResult {
		serialize(outputStream, locationData)

		return ExportResult.Success
	}


	private fun serialize(
			stream: OutputStream,
			locationData: Sequence<LocationSample>
	) {
		OutputStreamWriter(stream).use { osw ->
			writeBeginning(osw)
			locationData.forEach { writeSample(osw, it) }
			writeEnding(osw)
		}
	}

	@SuppressLint("SimpleDateFormat")
	private fun formatTime(time: Long): String {
		val date = Date(time)
		val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
		return format.format(date)
	}

	private fun writeSample(streamWriter: OutputStreamWriter, sample: LocationSample) {
		val lat = sample.latE7?.let { it / 1e7 } ?: return
		val lon = sample.lonE7?.let { it / 1e7 } ?: return
		if (!lat.isFinite() || !lon.isFinite()) return

		streamWriter.write("<Placemark><TimeStamp><when>${formatTime(sample.timeMs)}</when></TimeStamp>")
		val alt = sample.altitudeM?.toDouble()?.takeIf { it.isFinite() } ?: 0.0
		streamWriter.write(
				"<Point><coordinates>${lon},${lat},${alt}</coordinates></Point></Placemark>"
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

