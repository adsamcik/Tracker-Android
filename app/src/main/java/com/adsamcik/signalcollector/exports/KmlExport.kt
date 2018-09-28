package com.adsamcik.signalcollector.exports

import android.annotation.SuppressLint
import com.adsamcik.signalcollector.data.RawData
import com.adsamcik.signalcollector.data.TimeLocation
import com.squareup.moshi.Moshi
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*

class KmlExport : IExport {
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

        val targetFile = File(destinationDirectory, "Signals-Locations-${locations[0].time}.kml")
        serialize(targetFile)

        return ExportResult(targetFile, "application/vnd.google-earth.kml+xml")
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
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
        return format.format(date)
    }

    private fun writeLocation(streamWriter: OutputStreamWriter, location: TimeLocation) {
        streamWriter.write("<Placemark><TimeStamp><when>${formatTime(location.time)}</when></TimeStamp>")
        streamWriter.write("<Point><coordinates>${location.location.longitude},${location.location.latitude},${location.location.altitude}</coordinates></Point></Placemark>")
    }

    private fun writeBeginning(streamWriter: OutputStreamWriter) {
        streamWriter.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>")
    }

    private fun writeEnding(streamWriter: OutputStreamWriter) {
        streamWriter.write("</Document></kml>")
    }

}