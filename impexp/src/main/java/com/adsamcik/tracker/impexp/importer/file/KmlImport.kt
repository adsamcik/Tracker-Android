package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import android.util.Xml
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.LengthUnit
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.MutableTrackerSession
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.time.Instant

/**
 * Imports KML files with [Placemark] [LineString] tracks.
 */
internal class KmlImport : FileImport {
	override val supportedExtensions: Collection<String> = listOf("kml")

	override suspend fun import(
		context: Context,
		database: AppDatabase,
		stream: FileImportStream
	): ImportResult = withContext(Dispatchers.IO) {
		val parser = createParser().apply {
			setInput(stream, null)
		}

		var successCount = 0
		var syntheticTimeCursor = Time.nowMillis

		var event = parser.eventType
		while (event != XmlPullParser.END_DOCUMENT) {
			if (event == XmlPullParser.START_TAG && parser.name == "Placemark") {
				val result = importPlacemark(parser, database, syntheticTimeCursor)
				successCount += result.importedLocations
				syntheticTimeCursor = result.nextTimeCursor
			}
			event = parser.next()
		}

		ImportResult(successCount = successCount)
	}

	private suspend fun importPlacemark(
		parser: XmlPullParser,
		database: AppDatabase,
		syntheticTimeCursor: Long
	): PlacemarkImportResult {
		val placemarkDepth = parser.depth
		var placemarkName: String? = null
		var placemarkTimestamp: Long? = null
		val coordinateSequences = mutableListOf<List<KmlPoint>>()

		var event = parser.next()
		while (!(event == XmlPullParser.END_TAG && parser.depth == placemarkDepth && parser.name == "Placemark")) {
			if (event == XmlPullParser.START_TAG) {
				when (parser.name) {
					"name" -> placemarkName = parser.readTextOrNull()
					"LineString" -> coordinateSequences.addAll(parseLineString(parser))
					"Point" -> parsePoint(parser)?.let(coordinateSequences::add)
					"TimeStamp" -> placemarkTimestamp = parseTimeStamp(parser)
					else -> Unit
				}
			}
			event = parser.next()
		}

		if (coordinateSequences.isEmpty()) {
			return PlacemarkImportResult(0, syntheticTimeCursor)
		}

		val activity = placemarkName
			?.trim()
			?.takeIf { it.isNotEmpty() }
			?.let { prepareActivity(database, it) }
		var importedLocations = 0
		var nextCursor = placemarkTimestamp ?: syntheticTimeCursor

		for (sequence in coordinateSequences) {
			val importResult = importCoordinateSequence(database, sequence, activity, nextCursor)
			importedLocations += importResult.importedLocations
			nextCursor = importResult.nextTimeCursor
		}

		return PlacemarkImportResult(importedLocations, nextCursor)
	}

	private fun parseLineString(parser: XmlPullParser): List<List<KmlPoint>> {
		val lineStringDepth = parser.depth
		val sequences = mutableListOf<List<KmlPoint>>()

		var event = parser.next()
		while (!(event == XmlPullParser.END_TAG && parser.depth == lineStringDepth && parser.name == "LineString")) {
			if (event == XmlPullParser.START_TAG && parser.name == "coordinates") {
				parseCoordinates(parser.readTextOrNull()).takeIf { it.isNotEmpty() }?.let {
					sequences.add(it)
				}
			}
			event = parser.next()
		}

		return sequences
	}

	private fun parsePoint(parser: XmlPullParser): List<KmlPoint>? {
		val pointDepth = parser.depth
		var pointCoordinates: List<KmlPoint>? = null

		var event = parser.next()
		while (!(event == XmlPullParser.END_TAG && parser.depth == pointDepth && parser.name == "Point")) {
			if (event == XmlPullParser.START_TAG && parser.name == "coordinates") {
				pointCoordinates = parseCoordinates(parser.readTextOrNull()).takeIf { it.isNotEmpty() }
			}
			event = parser.next()
		}

		return pointCoordinates
	}

	private fun parseTimeStamp(parser: XmlPullParser): Long? {
		val timestampDepth = parser.depth
		var timestamp: Long? = null

		var event = parser.next()
		while (!(event == XmlPullParser.END_TAG && parser.depth == timestampDepth && parser.name == "TimeStamp")) {
			if (event == XmlPullParser.START_TAG && parser.name == "when") {
				timestamp = parser.readTextOrNull()
					?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
			}
			event = parser.next()
		}

		return timestamp
	}

	private fun parseCoordinates(rawCoordinates: String?): List<KmlPoint> {
		if (rawCoordinates.isNullOrBlank()) return emptyList()

		return rawCoordinates
			.trim()
			.split(Regex("\\s+"))
			.mapNotNull { point ->
				val parts = point.split(',')
				if (parts.size < 2) return@mapNotNull null

				val longitude = parts[0].toDoubleOrNull() ?: return@mapNotNull null
				val latitude = parts[1].toDoubleOrNull() ?: return@mapNotNull null
				val altitude = parts.getOrNull(2)?.toDoubleOrNull()

				if (!latitude.isFinite() || !longitude.isFinite()) return@mapNotNull null
				if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return@mapNotNull null

				KmlPoint(
					latitude = latitude,
					longitude = longitude,
					altitude = altitude?.takeIf { it.isFinite() }
				)
			}
	}

	private suspend fun importCoordinateSequence(
		database: AppDatabase,
		coordinates: List<KmlPoint>,
		activity: SessionActivity?,
		startTime: Long
	): PlacemarkImportResult {
		if (coordinates.isEmpty()) return PlacemarkImportResult(0, startTime)

		val session = MutableTrackerSession(start = startTime, isUserInitiated = true)
		if (activity != null) {
			session.sessionActivityId = activity.id
		}

		var timestamp = startTime
		var lastLocation: Location? = null
		val sampleList = ArrayList<LocationSample>(coordinates.size)

		coordinates.forEach { point ->
			val location = Location(
				time = timestamp,
				latitude = point.latitude,
				longitude = point.longitude,
				altitude = point.altitude,
				horizontalAccuracy = null,
				verticalAccuracy = null,
				speed = null,
				speedAccuracy = null
			)
			sampleList.add(location.toLocationSample())

			lastLocation?.let {
				session.distanceInM += location.distance(it, LengthUnit.Meter).toFloat()
			}

			lastLocation = location
			timestamp += POINT_TIME_DELTA_MS
		}

		session.collections = sampleList.size
		session.end = timestamp - POINT_TIME_DELTA_MS

		database.locationSampleDao().let { dao ->
			for (chunk in sampleList.chunked(BATCH_SIZE)) {
				dao.insert(chunk)
			}
		}
		saveSession(database, session)

		return PlacemarkImportResult(
			importedLocations = sampleList.size,
			nextTimeCursor = session.end + POINT_TIME_DELTA_MS
		)
	}

	private suspend fun prepareActivity(database: AppDatabase, name: String): SessionActivity {
		val activityDao = database.activityDao()
		return activityDao.find(name) ?: SessionActivity(name = name).also {
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
			latE7 = (latitude * 1e7).toInt(),
			lonE7 = (longitude * 1e7).toInt(),
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
		)
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

	private data class KmlPoint(
		val latitude: Double,
		val longitude: Double,
		val altitude: Double?
	)

	private data class PlacemarkImportResult(
		val importedLocations: Int,
		val nextTimeCursor: Long
	)

	private companion object {
		const val BATCH_SIZE = 100
		const val POINT_TIME_DELTA_MS = Time.SECOND_IN_MILLISECONDS
	}
}
