package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import android.util.Xml
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.MutableTrackerSession
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.mapper.toEntity
import com.adsamcik.tracker.shared.model.AltitudeConversionStatus
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.SampleQuality
import com.adsamcik.tracker.shared.model.SegmentSource
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.roundToInt

/**
 * Imports GPX files using a streaming [XmlPullParser].
 *
 * Unlike the previous jpx-based implementation, this reader processes the
 * XML document in a single forward-only pass, keeping memory usage O(batch size)
 * regardless of file size.
 */
internal class GpxImport(
private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) : FileImport {
override val supportedExtensions: Collection<String> = listOf("gpx")

override suspend fun import(
context: Context,
database: AppDatabase,
stream: FileImportStream
): ImportResult = withContext(dispatchers.io) {
val parser = createParser().apply {
setInput(stream, null)
}

var successCount = 0
var currentActivity: SessionActivity? = null

var event = parser.eventType
while (event != XmlPullParser.END_DOCUMENT) {
if (event == XmlPullParser.START_TAG) {
when (parser.name) {
"trk" -> {
currentActivity = null
}
"type" -> {
// <type> inside <trk> designates the activity type
val type = parser.readTextOrNull()
if (type != null) {
currentActivity = prepareActivity(database, type)
}
}
"trkseg" -> {
successCount += importSegment(
parser,
database,
currentActivity
)
}
}
}
event = parser.next()
}

ImportResult(successCount = successCount)
}

private suspend fun importSegment(
parser: XmlPullParser,
database: AppDatabase,
activity: SessionActivity?
): Int {
val segmentDepth = parser.depth
val batch = mutableListOf<LocationSample>()
var lastLocation: Location? = null
var startTime: Long? = null
var endTime: Long? = null
var distanceM = 0f
var sampleCount = 0

var event = parser.next()
while (!(event == XmlPullParser.END_TAG && parser.depth == segmentDepth && parser.name == "trkseg")) {
if (event == XmlPullParser.START_TAG && parser.name == "trkpt") {
val location = parseTrkpt(parser)
if (location != null) {
if (startTime == null) startTime = location.time
endTime = location.time

val sample = location.toLocationSample()
batch.add(sample)
sampleCount++

lastLocation?.let {
distanceM += distanceMeters(location, it).toFloat()
}
lastLocation = location

if (batch.size >= BATCH_SIZE) {
database.locationSampleDao().insert(batch.map { it.toEntity() })
batch.clear()
}
}
}
event = parser.next()
}

if (batch.isNotEmpty()) {
database.locationSampleDao().insert(batch.map { it.toEntity() })
}

// Only create a session if we got at least one timed waypoint
if (startTime != null && endTime != null && sampleCount > 0) {
val session = MutableTrackerSession(start = startTime, isUserInitiated = true)
session.end = endTime
session.collections = sampleCount
session.distanceInM = distanceM
if (activity != null) {
session.sessionActivityId = activity.id
}
saveSession(database, session)
}

return sampleCount
}

private fun parseTrkpt(parser: XmlPullParser): Location? {
val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: return null
val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: return null
if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null

val trkptDepth = parser.depth
var elevation: Double? = null
var time: Long? = null

var event = parser.next()
while (!(event == XmlPullParser.END_TAG && parser.depth == trkptDepth && parser.name == "trkpt")) {
if (event == XmlPullParser.START_TAG) {
when (parser.name) {
"ele" -> elevation = parser.readTextOrNull()?.toDoubleOrNull()
"time" -> time = parser.readTextOrNull()?.let { parseIso8601(it) }
}
}
event = parser.next()
}

// Waypoints without time are excluded (matching old behaviour)
if (time == null) return null

return Location(time, lat, lon, elevation, null, null, null, null)
}

private fun parseIso8601(text: String): Long? {
return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}

private suspend fun prepareActivity(database: AppDatabase, type: String): SessionActivity {
val activityDao = database.activityDao()
return activityDao.find(type) ?: SessionActivity(name = type).also {
val id = activityDao.insert(it)
it.id = id
}
}

private suspend fun saveSession(
database: AppDatabase,
session: MutableTrackerSession
) {
val segment = SessionSegment(
startTimeMs = session.start,
endTimeMs = session.end,
distanceM = session.distanceInM,
steps = session.steps.takeIf { it > 0 },
primaryActivity = session.sessionActivityId?.toInt(),
activityConfidence = null,
sampleCount = session.collections,
source = SegmentSource.USER_CREATED,
inferenceVersion = null,
createdAt = System.currentTimeMillis(),
)
database.sessionSegmentDao().insert(segment)
}

private fun Location.toLocationSample(): LocationSample {
return LocationSample(
timeMs = time,
elapsedRealtimeNanos = 0L,
latE7 = (latitude * 1e7).roundToInt(),
lonE7 = (longitude * 1e7).roundToInt(),
altitudeM = altitude?.toFloat(),
rawGpsAltitudeM = null,
hAccM = horizontalAccuracy,
vAccM = verticalAccuracy,
speedMps = speed,
speedAccuracyMps = speedAccuracy,
provider = "import",
quality = SampleQuality.MEDIUM,
motionState = null,
policy = null,
bucketId = null,
createdAt = System.currentTimeMillis(),
	// GPX <ele> has no datum declaration, so imported altitude remains explicitly unknown.
	altitudeDatum = AltitudeDatum.UNKNOWN_LEGACY,
	altitudeSource = AltitudeSource.IMPORTED,
	altitudeConversionStatus = AltitudeConversionStatus.UNKNOWN_LEGACY,
)
}

private fun distanceMeters(first: Location, second: Location): Double {
val earthRadiusMeters = 6_371_000.0
val firstLat = Math.toRadians(first.latitude)
val secondLat = Math.toRadians(second.latitude)
val deltaLat = Math.toRadians(second.latitude - first.latitude)
val deltaLon = Math.toRadians(second.longitude - first.longitude)
val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
cos(firstLat) * cos(secondLat) * sin(deltaLon / 2) * sin(deltaLon / 2)
return earthRadiusMeters * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private fun XmlPullParser.readTextOrNull(): String? {
return nextText()?.trim()?.takeIf { it.isNotEmpty() }
}

private fun createParser(): XmlPullParser {
return try {
Class.forName("org.kxml2.io.KXmlParser")
.getDeclaredConstructor()
.newInstance() as XmlPullParser
} catch (_: Throwable) {
Xml.newPullParser()
}
}

private companion object {
const val BATCH_SIZE = 100
}
}
