package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.shared.base.extension.applicationName
import com.adsamcik.tracker.shared.base.extension.formatAsDateTime
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant

/**
 * Exports locations to GPX format using streaming XML writes.
 *
 * Unlike the previous jpx-based implementation this writer is O(1) memory
 * regardless of dataset size - each track point is written and flushed
 * directly to the output stream without building an in-memory DOM.
 */
class GpxExporter : Exporter {
	override val canSelectDateRange: Boolean = true

	override val mimeType: String = "application/gpx+xml"

	override val extension: String = "gpx"

	override suspend fun export(
		context: Context,
		locationData: Sequence<LocationSample>,
		outputStream: OutputStream,
		dateRange: LongRange?,
	): ExportResult {
		return export(
			context = context,
			outputStream = outputStream,
			dateRange = dateRange,
		) { emit ->
			locationData.forEach(emit)
		}
	}

	suspend fun export(
		context: Context,
		outputStream: OutputStream,
		dateRange: LongRange? = null,
		streamLocations: suspend ((LocationSample) -> Unit) -> Unit,
	): ExportResult {
		try {
			val writer = BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8))
			writeHeader(writer, context, dateRange)
			streamLocations { sample ->
				writeTrackPoint(writer, sample)
			}
			writeFooter(writer)
			writer.flush()
		} catch (e: IOException) {
			val message = e.localizedMessage ?: e.message ?: e.javaClass.name
			return ExportResult.Error(LocalizedString(R.string.export_gpx_error, message))
		}

		return ExportResult.Success
	}

	private fun writeHeader(
		writer: BufferedWriter,
		context: Context,
		dateRange: LongRange?,
	) {
		writer.write("""<?xml version="1.0" encoding="UTF-8"?>""")
		writer.newLine()
		writer.write(
			"""<gpx version="1.1" creator="Tracker Android" """ +
				"""xmlns="http://www.topografix.com/GPX/1/1">""",
		)
		writer.newLine()

		if (dateRange != null) {
			writeMetadata(writer, context, dateRange)
		}

		writer.write("<trk>")
		writer.newLine()
		writer.write("<trkseg>")
		writer.newLine()
	}

	private fun writeTrackPoint(
		writer: BufferedWriter,
		sample: LocationSample,
	) {
		val lat = sample.latE7 ?: return
		val lon = sample.lonE7 ?: return
		val latitude = lat / 1e7
		val longitude = lon / 1e7

		writer.write("""<trkpt lat="$latitude" lon="$longitude">""")
		// GPX's <ele> has no datum field. Emit it only when Tracker can truthfully provide
		// Android-model MSL; relative, ellipsoid, and unknown values would otherwise be mislabeled.
		sample.altitudeM
			?.takeIf { it.isFinite() && sample.altitudeDatum.isAndroidModelMsl }
			?.let { writer.write("<ele>$it</ele>") }
		writer.write("<time>${Instant.ofEpochMilli(sample.timeMs)}</time>")
		writer.write("</trkpt>")
		writer.newLine()
	}

	private fun writeFooter(writer: BufferedWriter) {
		writer.write("</trkseg>")
		writer.newLine()
		writer.write("</trk>")
		writer.newLine()
		writer.write("</gpx>")
	}

	private fun writeMetadata(
		writer: BufferedWriter,
		context: Context,
		dateRange: LongRange,
	) {
		val author = escapeXml(context.applicationName)
		val description = escapeXml(
			context.getString(
				R.string.export_gpx_description,
				dateRange.first.formatAsDateTime(),
				dateRange.last.formatAsDateTime(),
			),
		)
		writer.write("<metadata>")
		writer.write("<author><name>$author</name></author>")
		writer.write("<desc>$description</desc>")
		writer.write("</metadata>")
		writer.newLine()
	}

	private fun escapeXml(text: String): String = text
		.replace("&", "&amp;")
		.replace("<", "&lt;")
		.replace(">", "&gt;")
		.replace("\"", "&quot;")
		.replace("'", "&apos;")
}
