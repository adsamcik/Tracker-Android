package com.adsamcik.signalcollector.import

import androidx.annotation.RequiresApi
import com.adsamcik.signalcollector.common.data.ActivityInfo
import com.adsamcik.signalcollector.common.data.Location
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.database.dao.LocationDataDao
import com.adsamcik.signalcollector.common.database.data.DatabaseLocation
import io.jenetics.jpx.GPX
import io.jenetics.jpx.Speed
import io.jenetics.jpx.WayPoint
import java.io.File

@RequiresApi(26)
class GpxImport : IImport {
	override val supportedExtensions: Collection<String> = listOf("gpx")

	override fun import(database: AppDatabase, file: File) {
		val locationDao = database.locationDao()
		val gpx = GPX.read(file.path)
		gpx.tracks().forEach { track ->
			track.segments().forEach { segment ->
				segment.points().forEach { waypoint ->
					saveWaypointToDb(locationDao, waypoint)
				}
			}
		}

	}

	private fun saveWaypointToDb(locationDao: LocationDataDao, waypoint: WayPoint) {
		val time = waypoint.time.get().toEpochSecond()
		val latitude = waypoint.latitude.toDegrees()
		val longitude = waypoint.longitude.toDegrees()
		val altitude = waypoint.elevation.orElse(null)?.toDouble()
		val speed = waypoint.speed.orElse(null)?.to(Speed.Unit.METERS_PER_SECOND)?.toFloat()
		val location = Location(time, latitude, longitude, altitude, null, null, speed, null)
		locationDao.insert(DatabaseLocation(location, ActivityInfo.UNKNOWN))
	}

}