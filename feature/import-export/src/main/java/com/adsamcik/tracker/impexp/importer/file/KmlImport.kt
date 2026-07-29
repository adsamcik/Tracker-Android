package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import android.util.Xml
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.shared.base.Time
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
 * Imports KML files with [Placemark] [LineString] tracks.
 */
internal class KmlImport(
	private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) : FileImport {
	override val supportedExtensions: Collection<String> = listOf("kml")

	override suspend fun import(
		context: Context,
		database: AppDatabase,
		stream: FileImportStream
	): ImportResult = withContext(dispatchers.io) {
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
		var activity: SessionActivity? = null
		var placemarkTimestamp: Long? = null
		var importedLocations = 0
		var nextCursor = syntheticTimeCursor

		var event = parser.next()
		while (!(event == XmlPullParser.END_TAG && parser.depth == placemarkDepth && parser.name == "Placemark")) {
			if (event == XmlPullParser.START_TAG) {
				when (parser.name) {
					"name" -> parser.readTextOrNull()
						?.let { it.trim().takeIf(String::isNotEmpty) }
						?.let { activity = prepareActivity(database, it) }
					"TimeStamp" -> placemarkTimestamp = parseTimeStamp(parser)
					"LineString", "Point" -> {
						val result = importGeometry(
							parser = parser,
							database = database,
							activity = activity,
							startTime = placemarkTimestamp ?: nextCursor,
						)
						importedLocations += result.importedLocations
						nextCursor = result.nextTimeCursor
					}
				}
			}
			event = parser.next()
		}

		return PlacemarkImportResult(importedLocations, nextCursor)
	}

	private suspend fun importGeometry(
		parser: XmlPullParser,
		database: AppDatabase,
		activity: SessionActivity?,
		startTime: Long,
	): PlacemarkImportResult {
		val geometryName = parser.name
		val geometryDepth = parser.depth
		var importedLocations = 0
		var nextCursor = startTime

		var event = parser.next()
		while (!(event == XmlPullParser.END_TAG && parser.depth == geometryDepth && parser.name == geometryName)) {
			if (event == XmlPullParser.START_TAG && parser.name == "coordinates") {
				val result = importCoordinates(parser, database, activity, nextCursor)
				importedLocations += result.importedLocations
				nextCursor = result.nextTimeCursor
			}
			event = parser.next()
		}

		return PlacemarkImportResult(importedLocations, nextCursor)
	}

	private fun parseTimeStamp(parser: XmlPullParser): Long? {
		val timestampDepth = parser.depth
		var timestamp: Long? = null

		var event = parser.next()
		while (!(event == XmlPullParser.END_TAG && parser.depth == timestampDepth && parser.name == "TimeStamp")) {
			if (event == XmlPullParser.START_TAG && parser.name == "when") {
				timestamp = parser.readTextOrNull()?.let(::parseWhenTimestamp)
			}
			event = parser.next()
		}

		return timestamp
	}

	private suspend fun importCoordinates(
		parser: XmlPullParser,
		database: AppDatabase,
		activity: SessionActivity?,
		startTime: Long,
	): PlacemarkImportResult {
		val coordinatesDepth = parser.depth
		val session = MutableTrackerSession(start = startTime, isUserInitiated = true).apply {
			activity?.let { sessionActivityId = it.id }
		}
		val batch = ArrayList<LocationSample>(BATCH_SIZE)
		var timestamp = startTime
		var lastLocation: Location? = null
		var importedLocations = 0
		val pendingCoordinate = StringBuilder()

		fun importPoint(point: KmlPoint) {
			val location = Location(
				time = timestamp,
				latitude = point.latitude,
				longitude = point.longitude,
				altitude = point.altitude,
				horizontalAccuracy = null,
				verticalAccuracy = null,
				speed = null,
				speedAccuracy = null,
			)
			batch.add(location.toLocationSample())
			lastLocation?.let { session.distanceInM += distanceMeters(location, it).toFloat() }
			lastLocation = location
			importedLocations++
			timestamp += POINT_TIME_DELTA_MS
		}

		suspend fun flushBatch() {
			if (batch.isNotEmpty()) {
				database.locationSampleDao().insert(batch.map { it.toEntity() })
				batch.clear()
			}
		}

		suspend fun consumeText(text: String) {
			var tokenStart = 0
			for (index in text.indices) {
				if (text[index].isWhitespace()) {
					if (tokenStart < index) {
						pendingCoordinate.append(text, tokenStart, index)
						parseCoordinate(pendingCoordinate.toString())?.let(::importPoint)
						pendingCoordinate.clear()
						if (batch.size >= BATCH_SIZE) flushBatch()
					}
					tokenStart = index + 1
				}
			}
			if (tokenStart < text.length) pendingCoordinate.append(text, tokenStart, text.length)
		}

		var event = parser.next()
		while (!(event == XmlPullParser.END_TAG && parser.depth == coordinatesDepth && parser.name == "coordinates")) {
			if (event == XmlPullParser.TEXT) consumeText(parser.text.orEmpty())
			event = parser.next()
		}
		if (pendingCoordinate.isNotEmpty()) {
			parseCoordinate(pendingCoordinate.toString())?.let(::importPoint)
		}
		flushBatch()

		if (importedLocations == 0) return PlacemarkImportResult(0, startTime)
		session.collections = importedLocations
		session.end = timestamp - POINT_TIME_DELTA_MS
		saveSession(database, session)
		return PlacemarkImportResult(importedLocations, timestamp)
	}

	private fun parseCoordinate(value: String): KmlPoint? {
		val parts = value.split(',')
		if (parts.size < 2) return null
		val longitude = parts[0].toDoubleOrNull() ?: return null
		val latitude = parts[1].toDoubleOrNull() ?: return null
		val altitude = parts.getOrNull(2)?.toDoubleOrNull()
		if (!latitude.isFinite() || !longitude.isFinite()) return null
		if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
		return KmlPoint(latitude, longitude, altitude?.takeIf(Double::isFinite))
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
			// KML coordinate altitude carries no datum declaration.
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

	private data class KmlPoint(
		val latitude: Double,
		val longitude: Double,
		val altitude: Double?
	)

	private data class PlacemarkImportResult(
		val importedLocations: Int,
		val nextTimeCursor: Long
	)

	internal companion object {
		const val BATCH_SIZE = 100
		const val POINT_TIME_DELTA_MS = Time.SECOND_IN_MILLISECONDS

		fun parseWhenTimestamp(value: String): Long? =
			runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
	}
}
