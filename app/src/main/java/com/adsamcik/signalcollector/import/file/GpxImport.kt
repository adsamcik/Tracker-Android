package com.adsamcik.signalcollector.import

import androidx.annotation.RequiresApi
import com.adsamcik.signalcollector.common.data.ActivityInfo
import com.adsamcik.signalcollector.common.data.LengthUnit
import com.adsamcik.signalcollector.common.data.Location
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.database.dao.LocationDataDao
import com.adsamcik.signalcollector.common.database.data.DatabaseLocation
import com.adsamcik.signalcollector.tracker.data.session.MutableTrackerSession
import io.jenetics.jpx.GPX
import io.jenetics.jpx.Speed
import io.jenetics.jpx.TrackSegment
import io.jenetics.jpx.WayPoint
import java.io.File

@RequiresApi(26)
class GpxImport : FileImport {
	override val supportedExtensions: Collection<String> = listOf("gpx")

	override fun import(database: AppDatabase, file: File) {
		val gpx = GPX.read(file.path)
		gpx.tracks().forEach { track ->
			track.segments().forEach { segment ->
				val session = prepareSession(segment)
				if (session != null) {
					handleSegment(database, segment, session)
				}
			}
		}
	}

	private fun prepareSession(segment: TrackSegment): MutableTrackerSession? {
		val start: Long
		val end: Long

		try {
			start = segment.points.first { it.time.isPresent }.time.get().toEpochSecond()
			end = segment.points.last { it.time.isPresent }.time.get().toEpochSecond()
		} catch (e: NoSuchElementException) {
			return null
		}

		val session = MutableTrackerSession(start = start, isUserInitiated = true)
		session.end = end
		return session
	}

	private fun handleSegment(database: AppDatabase,
	                          segment: TrackSegment,
	                          session: MutableTrackerSession) {
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

	private fun saveSession(database: AppDatabase,
	                        session: TrackerSession) {
		val sessionDao = database.sessionDao()
		sessionDao.insert(session)
	}

	private fun saveWaypointToDb(locationDao: LocationDataDao, waypoint: WayPoint): Location? {
		if (!waypoint.time.isPresent) return null

		val time = waypoint.time.get().toEpochSecond()
		val latitude = waypoint.latitude.toDegrees()
		val longitude = waypoint.longitude.toDegrees()
		val altitude = waypoint.elevation.orElse(null)?.toDouble()
		val speed = waypoint.speed.orElse(null)?.to(Speed.Unit.METERS_PER_SECOND)?.toFloat()
		val location = Location(time, latitude, longitude, altitude, null, null, speed, null)
		locationDao.insert(DatabaseLocation(location, ActivityInfo.UNKNOWN))
		return location
	}
}