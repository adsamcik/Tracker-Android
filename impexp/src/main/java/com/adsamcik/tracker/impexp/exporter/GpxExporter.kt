package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.extension.applicationName
import com.adsamcik.tracker.shared.base.extension.formatAsDateTime
import com.adsamcik.tracker.shared.base.misc.LocalizedString
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
dateRange: LongRange?
): ExportResult {
try {
val writer = BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8))

writer.write("""<?xml version="1.0" encoding="UTF-8"?>""")
writer.newLine()
writer.write(
"""<gpx version="1.1" creator="Tracker Android" """ +
"""xmlns="http://www.topografix.com/GPX/1/1">"""
)
writer.newLine()

if (dateRange != null) {
writeMetadata(writer, context, dateRange)
}

writer.write("<trk>")
writer.newLine()
writer.write("<trkseg>")
writer.newLine()

locationData.forEach { sample ->
val lat = sample.latE7 ?: return@forEach
val lon = sample.lonE7 ?: return@forEach
val latitude = lat / 1e7
val longitude = lon / 1e7

writer.write("""<trkpt lat="$latitude" lon="$longitude">""")
sample.altitudeM?.let { writer.write("<ele>${it.toDouble()}</ele>") }
writer.write("<time>${Instant.ofEpochMilli(sample.timeMs)}</time>")
writer.write("</trkpt>")
writer.newLine()
}

writer.write("</trkseg>")
writer.newLine()
writer.write("</trk>")
writer.newLine()
writer.write("</gpx>")
writer.flush()
} catch (e: IOException) {
val message = e.localizedMessage ?: e.message ?: e.javaClass.name
return ExportResult.Error(LocalizedString(R.string.export_gpx_error, message))
}

return ExportResult.Success
}

private fun writeMetadata(
writer: BufferedWriter,
context: Context,
dateRange: LongRange
) {
val author = escapeXml(context.applicationName)
val description = escapeXml(
context.getString(
R.string.export_gpx_description,
dateRange.first.formatAsDateTime(),
dateRange.last.formatAsDateTime()
)
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