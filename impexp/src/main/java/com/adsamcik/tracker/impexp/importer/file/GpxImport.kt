package com.adsamcik.tracker.impexp.importer.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportResult
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

		val locationList = ArrayList<DatabaseLocation>(segment.points.size)

		segment.points().forEach { waypoint ->
			val databaseLocation = createDatabaseLocation(waypoint) ?: return@forEach
			locationList.add(databaseLocation)

			val location = databaseLocation.location

			val lastLocationTmp = lastLocation
			if (lastLocationTmp != null) {
				val distance = location.distance(lastLocationTmp, LengthUnit.Meter)
				session.distanceInM += distance.toFloat()
			}

			session.collections++
			lastLocation = location
		}

		val locationDao = database.locationDao()
		locationList.chunked(100).forEach {
			locationDao.insert(it)
		}

		saveSession(database, session)
		return locationList.size
	}

	private fun saveSession(
			database: AppDatabase,
			session: TrackerSession
	) {
		val sessionDao = database.sessionDao()
		sessionDao.insert(session)
	}

	private fun createDatabaseLocation(waypoint: WayPoint): DatabaseLocation? {
		if (!waypoint.time.isPresent) return null

		val time = waypoint.time.get().toEpochMilli()
		val latitude = waypoint.latitude.toDegrees()
		val longitude = waypoint.longitude.toDegrees()
		val altitude = waypoint.elevation.orElse(null)?.toDouble()
		val speed = waypoint.speed.orElse(null)?.to(Speed.Unit.METERS_PER_SECOND)?.toFloat()
		val location = Location(time, latitude, longitude, altitude, null, null, speed, null)
		return DatabaseLocation(location, ActivityInfo.UNKNOWN)
	}
}

