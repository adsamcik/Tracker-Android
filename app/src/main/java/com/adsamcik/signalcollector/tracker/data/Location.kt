package com.adsamcik.signalcollector.tracker.data

import android.os.Build
import androidx.room.ColumnInfo
import com.adsamcik.signalcollector.activity.ActivityInfo
import com.adsamcik.signalcollector.database.data.DatabaseLocation
import com.adsamcik.signalcollector.misc.extension.deg2rad
import com.adsamcik.signalcollector.misc.extension.round
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Location(
		val time: Long,
		@Json(name = "lat")
		@ColumnInfo(name = "lat")
		val latitude: Double,
		@Json(name = "lon")
		@ColumnInfo(name = "lon")
		val longitude: Double,
		@Json(name = "alt")
		@ColumnInfo(name = "alt")
		val altitude: Double?,
		@Json(name = "hor_acc")
		@ColumnInfo(name = "hor_acc")
		val horizontalAccuracy: Float?,
		@Json(name = "ver_acc")
		@ColumnInfo(name = "ver_acc")
		val verticalAccuracy: Float?) {

	constructor(location: android.location.Location) : this(location.time,
			location.latitude,
			location.longitude,
			if (location.hasAltitude()) location.altitude else null,
			if (location.hasAccuracy()) location.accuracy else null,
			if (Build.VERSION.SDK_INT >= 26) location.verticalAccuracyMeters else null)

	constructor(location: Location) : this(location.time, location.latitude, location.longitude, location.altitude, location.horizontalAccuracy, location.verticalAccuracy)


	fun toDatabase(activityInfo: ActivityInfo): DatabaseLocation = DatabaseLocation(this, activityInfo)

	private fun calculateLineOfLongitudeM(latitude: Double) = kotlin.math.cos(latitude.deg2rad()) * EARTH_CIRCUMFERENCE

	private fun longitudeAccuracy(meters: Double, latitude: Double) = meters * (360.0 / calculateLineOfLongitudeM(latitude)).round(6)

	private fun latitudeAccuracy(meters: Double) = (METER_DEGREE_LATITUDE * meters).round(6)

	/// <summary>
	/// Calculates distance based on only latitude and longitude
	/// </summary>
	/// <param name="latlon">second location</param>
	/// <param name="unit">unit type</param>
	/// <returns></returns>
	fun distanceFlat(location: Location, unit: LengthUnit): Double {
		val lat1Rad = latitude.deg2rad()
		val lat2Rad = location.latitude.deg2rad()
		val latDistance = (location.latitude - latitude).deg2rad()
		val lonDistance = (location.longitude - longitude).deg2rad()

		val sinLatDistance = kotlin.math.sin(latDistance / 2)
		val sinLonDistance = kotlin.math.sin(lonDistance / 2)

		val a = sinLatDistance * sinLatDistance +
				kotlin.math.cos(lat1Rad) * kotlin.math.cos(lat2Rad) *
				sinLonDistance * sinLonDistance
		val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))

		var distance = EARTH_CIRCUMFERENCE * c


		when (unit) {
			LengthUnit.Meter -> {
			}
			LengthUnit.Kilometer -> distance /= 1000
			LengthUnit.Mile -> distance /= 1.609
			LengthUnit.NauticalMile -> distance /= 1.852
		}

		return distance
	}

	/// <summary>
	/// Calculates distance between two locations
	/// </summary>
	/// <param name="latlon">second location</param>
	/// <param name="unit">unity type</param>
	/// <returns></returns>
	fun distance(location: Location, unit: LengthUnit): Double {
		val flatDistance = distanceFlat(location, unit)

		if (location.altitude == null || altitude == null)
			return flatDistance

		val altitudeDistance = location.altitude - altitude
		return kotlin.math.sqrt(flatDistance * flatDistance + altitudeDistance * altitudeDistance)
	}

	fun roundTo(meters: Double): Location = roundTo(meters, meters)

	fun roundTo(metersHorizontal: Double, metersVertical: Double): Location {
		val accLatitude = latitudeAccuracy(metersVertical)
		val accLongitude = longitudeAccuracy(metersHorizontal, latitude)
		return Location(time, (latitude - latitude % accLatitude).round(6), (longitude - longitude % accLongitude.round(6)), altitude, horizontalAccuracy, verticalAccuracy)
	}

	companion object {
		const val EARTH_CIRCUMFERENCE: Int = 40075000
		const val METER_DEGREE_LATITUDE: Double = 360.0 / EARTH_CIRCUMFERENCE
	}
}

enum class LengthUnit {
	Meter,
	Kilometer,
	Mile,
	NauticalMile
}