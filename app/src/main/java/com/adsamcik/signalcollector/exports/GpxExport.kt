package com.adsamcik.signalcollector.exports

import android.annotation.SuppressLint
import android.os.Build
import com.adsamcik.signalcollector.BuildConfig
import com.adsamcik.signalcollector.data.RawData
import com.adsamcik.signalcollector.data.TimeLocation
import com.squareup.moshi.Moshi
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*

class GpxExport : IExport {
    val locations = ArrayList<TimeLocation>()

    fun addLocations(rawData: Array<RawData>) {
        locations.ensureCapacity(locations.size + rawData.size)
        rawData.forEach {
            if (it.location != null) {
                locations.add(TimeLocation(it))
            }
        }
    }

    override fun export(files: ArrayList<File>, destinationDirectory: File): ExportResult {
        val moshi = Moshi.Builder().build().adapter<Array<RawData>>(Array<RawData>::class.java)
        files.forEach {
            //todo deserialize from stream
            var json = it.readText()
            if (!json.endsWith(']'))
                json += ']'

            addLocations(moshi.fromJson(json)!!)
        }

        val targetFile = File(destinationDirectory, "Signals-Locations-${locations[0].time}.gpx")
        serialize(targetFile)

        return ExportResult(targetFile, "application/gpx+xml")
    }


    fun serialize(file: File) {
        FileOutputStream(file, false).let { outputStream ->
            outputStream.channel.lock()
            OutputStreamWriter(outputStream).use { osw ->
                writeBeginning(osw)
                locations.forEach { writeLocation(osw, it) }
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

    private fun writeLocation(streamWriter: OutputStreamWriter, location: TimeLocation) {
        streamWriter.write("<trkpt lat=\"${location.location.latitude}\" lon=\"${location.location.longitude}\">" +
                "<ele>${location.location.altitude}</ele>" +
                "<time>${formatTime(location.time)}</time>" +
                "<fix>3d</fix>" +
                "<pdop>${location.location.horizontalAccuracy / 5f}</pdop>" +
                "</trkpt>\n")
    }

    private fun writeBeginning(streamWriter: OutputStreamWriter) {
        val minLat = locations.minBy { it.location.latitude }!!.location.latitude
        val maxLat = locations.maxBy { it.location.latitude }!!.location.latitude
        val minLon = locations.minBy { it.location.longitude }!!.location.longitude
        val maxLon = locations.maxBy { it.location.longitude }!!.location.longitude

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