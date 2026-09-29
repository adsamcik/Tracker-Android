package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import android.util.Xml
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.mapper.toEntity
import com.adsamcik.tracker.shared.model.AltitudeConversionStatus
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.SampleQuality
import java.io.IOException
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/** Imports KML points only when the file supplies their actual observation time. */
internal class KmlImport(
    private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) : FileImport {
    override val supportedExtensions: Collection<String> = listOf("kml")

    override suspend fun import(
        context: Context,
        database: AppDatabase,
        stream: FileImportStream,
    ): ImportResult = withContext(dispatchers.io) {
        val parser = createParser().apply { setInput(stream, null) }
        var imported = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Placemark") {
                parsePlacemark(parser)?.let { (point, timeMs) ->
                    database.locationSampleDao().insert(listOf(point.toSample(timeMs).toEntity()))
                    imported++
                }
            }
            event = parser.next()
        }
        ImportResult(successCount = imported)
    }

    private fun parsePlacemark(parser: XmlPullParser): Pair<KmlPoint, Long>? {
        val depth = parser.depth
        var point: KmlPoint? = null
        var timestamp: Long? = null
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "Placemark")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "TimeStamp" -> timestamp = parseTimeStamp(parser)
                    "Point" -> {
                        if (point != null) throw IOException("KML placemark has multiple points")
                        point = parsePoint(parser)
                    }
                    "LineString" -> throw IOException("KML line has no time for each point; import timed points instead")
                }
            }
            event = parser.next()
        }
        val savedPoint = point ?: return null
        val savedTime = timestamp ?: throw IOException("KML point has no valid timestamp")
        if (savedTime < 0L) throw IOException("KML point timestamp is invalid")
        return savedPoint to savedTime
    }

    private fun parseTimeStamp(parser: XmlPullParser): Long? {
        val depth = parser.depth
        var timestamp: Long? = null
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "TimeStamp")) {
            if (event == XmlPullParser.START_TAG && parser.name == "when") {
                timestamp = parser.nextText()?.trim()?.let(::parseWhenTimestamp)
            }
            event = parser.next()
        }
        return timestamp
    }

    private fun parsePoint(parser: XmlPullParser): KmlPoint {
        val depth = parser.depth
        var point: KmlPoint? = null
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "Point")) {
            if (event == XmlPullParser.START_TAG && parser.name == "coordinates") {
                if (point != null) throw IOException("KML point has multiple coordinate sets")
                val coordinates = parser.nextText()?.trim().orEmpty()
                if (coordinates.length > MAX_COORDINATE_TEXT || coordinates.any(Char::isWhitespace)) {
                    throw IOException("KML point coordinates are invalid")
                }
                point = parseCoordinate(coordinates)
                    ?: throw IOException("KML point coordinates are invalid")
            }
            event = parser.next()
        }
        return point ?: throw IOException("KML point has no coordinates")
    }

    private fun parseCoordinate(value: String): KmlPoint? {
        val parts = value.split(',')
        if (parts.size !in 2..3) return null
        val longitude = parts[0].toDoubleOrNull() ?: return null
        val latitude = parts[1].toDoubleOrNull() ?: return null
        val altitude = parts.getOrNull(2)?.toDoubleOrNull()
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        if (parts.size == 3 && (altitude == null || !altitude.isFinite())) return null
        return KmlPoint(latitude, longitude, altitude)
    }

    private fun KmlPoint.toSample(timeMs: Long): LocationSample = LocationSample(
        timeMs = timeMs,
        elapsedRealtimeNanos = 0L,
        latE7 = (latitude * 1e7).roundToInt(),
        lonE7 = (longitude * 1e7).roundToInt(),
        altitudeM = altitude?.toFloat(),
        rawGpsAltitudeM = null,
        hAccM = null,
        vAccM = null,
        speedMps = null,
        speedAccuracyMps = null,
        provider = "import",
        // KML provides no accuracy measurement; do not invent medium precision.
        quality = SampleQuality.COARSE,
        motionState = null,
        policy = null,
        bucketId = null,
        createdAt = System.currentTimeMillis(),
        altitudeDatum = AltitudeDatum.UNKNOWN_LEGACY,
        altitudeSource = AltitudeSource.IMPORTED,
        altitudeConversionStatus = AltitudeConversionStatus.UNKNOWN_LEGACY,
    )

    private fun createParser(): XmlPullParser = try {
        Class.forName("org.kxml2.io.KXmlParser")
            .getDeclaredConstructor()
            .newInstance() as XmlPullParser
    } catch (_: ReflectiveOperationException) {
        Xml.newPullParser()
    }

    private data class KmlPoint(val latitude: Double, val longitude: Double, val altitude: Double?)

    internal companion object {
        private const val MAX_COORDINATE_TEXT = 256

        fun parseWhenTimestamp(value: String): Long? =
            runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
    }
}
