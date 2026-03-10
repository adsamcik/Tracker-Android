package com.adsamcik.tracker.impexp.importer.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import android.content.Context
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
import io.jenetics.jpx.GPX
import io.jenetics.jpx.Speed
import io.jenetics.jpx.TrackSegment
import io.jenetics.jpx.WayPoint
import java.time.ZonedDateTime

/**
 * Imports GPX files.
 */
internal class GpxImport : FileImport {
	override val supportedExtensions: Collection<String> = listOf("gpx")

	override suspend fun import(
			context: Context,
			database: AppDatabase,
			stream: FileImportStream
	): ImportResult = withContext(Dispatchers.IO) {
		var successCount = 0
		val gpx = GPX.Reader.DEFAULT.read(stream)
		gpx.tracks().forEach { track ->
			val type: String? = if (track.type.isPresent) track.type.get() else null
			val activity = if (type != null) {
				prepareActivity(database, type)
			} else {
				null
			}

			track.segments().forEach { segment ->
				prepareSession(segment, activity)?.let { session ->
					successCount += handleSegment(database, segment, session)
				}
			}
		}
		ImportResult(successCount = successCount)
	}

	private fun prepareActivity(database: AppDatabase, type: String): SessionActivity {
		val activityDao = database.activityDao()

		return activityDao.find(type) ?: SessionActivity(name = type).also {
			val id = activityDao.insert(it)
			it.id = id
		}
	}

	private fun ZonedDateTime.toEpochMillisecond(): Long {
		return toEpochSecond() * Time.SECOND_IN_MILLISECONDS
	}

	private fun prepareSession(
			segment: TrackSegment,
			activity: SessionActivity?
	): MutableTrackerSession? {
		val start: Long
		val end: Long

		try {
			start = segment.points.first { it.time.isPresent }.time.get().toEpochMilli()
			end = segment.points.last { it.time.isPresent }.time.get().toEpochMilli()
		} catch (e: NoSuchElementException) {
			return null
		}

		val session = MutableTrackerSession(start = start, isUserInitiated = true)
		session.end = end

		if (activity != null) {
			session.sessionActivityId = activity.id
		}

		return session
	}

	private fun handleSegment(
			database: AppDatabase,
			segment: TrackSegment,
			session: MutableTrackerSession
	): Int {
		var lastLocation: Location? = null

		val sampleList = ArrayList<LocationSample>(segment.points.size)

		segment.points().forEach { waypoint ->
			val location = createLocation(waypoint) ?: return@forEach
			sampleList.add(location.toLocationSample())

			val lastLocationTmp = lastLocation
			if (lastLocationTmp != null) {
				val distance = location.distance(lastLocationTmp, LengthUnit.Meter)
				session.distanceInM += distance.toFloat()
			}

			session.collections++
			lastLocation = location
		}

		database.locationSampleDao().let { dao ->
			sampleList.chunked(100).forEach { dao.insert(it) }
		}

		saveSession(database, session)
		return sampleList.size
	}

	private fun saveSession(
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

	private fun createLocation(waypoint: WayPoint): Location? {
		if (!waypoint.time.isPresent) return null

		val time = waypoint.time.get().toEpochMilli()
		val latitude = waypoint.latitude.toDegrees()
		val longitude = waypoint.longitude.toDegrees()
		val altitude = waypoint.elevation.orElse(null)?.toDouble()
		val speed = waypoint.speed.orElse(null)?.to(Speed.Unit.METERS_PER_SECOND)?.toFloat()
		return Location(time, latitude, longitude, altitude, null, null, speed, null)
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
}

