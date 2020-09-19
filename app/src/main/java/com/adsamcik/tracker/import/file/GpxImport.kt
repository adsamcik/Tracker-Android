package com.adsamcik.tracker.import.file

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.LengthUnit
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.MutableTrackerSession
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationDataDao
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import io.jenetics.jpx.GPX
import io.jenetics.jpx.Speed
import io.jenetics.jpx.TrackSegment
import io.jenetics.jpx.WayPoint
import java.io.File
import java.time.ZonedDateTime

@RequiresApi(Build.VERSION_CODES.O)
class GpxImport : FileImport {
	override val supportedExtensions: Collection<String> = listOf("gpx")

	override fun import(
			context: Context,
			database: AppDatabase,
			file: File
	) {
		val gpx = GPX.read(file.path)
		gpx.tracks().forEach { track ->
			val type: String? = if (track.type.isPresent) track.type.get() else null
			val activity = if (type != null) {
				prepareActivity(database, type)
			} else {
				null
			}

			track.segments().forEach { segment ->
				val session = prepareSession(segment, activity)
				if (session != null) {
					handleSegment(database, segment, session)
				}
			}
		}
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
			start = segment.points.first { it.time.isPresent }.time.get().toEpochMillisecond()
			end = segment.points.last { it.time.isPresent }.time.get().toEpochMillisecond()
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
	) {
		var lastLocation: Location? = null

		val locationDao = database.locationDao()
		segment.points().forEach { waypoint ->
			val location = saveWaypointToDb(locationDao, waypoint)

			val lastLocationTmp = lastLocation
			if (lastLocationTmp != null && location != null) {
				val distance = location.distance(lastLocationTmp, LengthUnit.Meter)
				session.distanceInM += distance.toFloat()
			}

			if (location != null) {
				session.collections++
				lastLocation = location
			}
		}

		saveSession(database, session)
	}

	private fun saveSession(
			database: AppDatabase,
			session: TrackerSession
	) {
		val sessionDao = database.sessionDao()
		sessionDao.insert(session)
	}

	private fun saveWaypointToDb(locationDao: LocationDataDao, waypoint: WayPoint): Location? {
		if (!waypoint.time.isPresent) return null

		val time = waypoint.time.get().toEpochMillisecond()
		val latitude = waypoint.latitude.toDegrees()
		val longitude = waypoint.longitude.toDegrees()
		val altitude = waypoint.elevation.orElse(null)?.toDouble()
		val speed = waypoint.speed.orElse(null)?.to(Speed.Unit.METERS_PER_SECOND)?.toFloat()
		val location = Location(time, latitude, longitude, altitude, null, null, speed, null)
		locationDao.insert(DatabaseLocation(location, ActivityInfo.UNKNOWN))
		return location
	}
}

