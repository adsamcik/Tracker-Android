package com.adsamcik.signalcollector.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.adsamcik.signalcollector.extensions.deg2rad
import com.adsamcik.signalcollector.extensions.round
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlin.math.pow

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
		val altitude: Double,
		@Json(name = "acc")
		@ColumnInfo(name = "hor_acc")
		val horizontalAccuracy: Float) {

	constructor(location: android.location.Location) : this(location.time, location.latitude, location.longitude, location.altitude, location.accuracy)
	constructor(location: Location) : this(location.time, location.latitude, location.longitude, location.altitude, location.horizontalAccuracy)


	fun toDatabase() = DatabaseLocation(this)

	private fun calculateLineOfLongitudeM(latitude: Double) = kotlin.math.cos(latitude.deg2rad()) * EARTH_CIRCUMFERENCE;

	private fun longitudeAccuracy(meters: Int, latitude: Double) = meters * (360.0 / calculateLineOfLongitudeM(latitude)).round(6);

	private fun latitudeAccuracy(meters: Int) = (METER_DEGREE_LATITUDE * meters).round(6);

	fun roundTo(meters: Int): Location {
		val accLatitude = latitudeAccuracy(meters)
		val accLongitude = longitudeAccuracy(meters, latitude)
		return Location(time, (latitude - latitude % accLatitude).round(6), (longitude - longitude % accLongitude.round(6)), altitude, horizontalAccuracy)
	}

	companion object {
		const val EARTH_CIRCUMFERENCE = 40075000;
		const val METER_DEGREE_LATITUDE = 360.0 / EARTH_CIRCUMFERENCE;

		fun toGoogleLon(lon: Double, tileCount: Int): Double {
			return tileCount * ((lon + 180) / 360)
		}

		fun toGoogleLat(lat: Double, tileCount: Int): Double {
			val latRad = kotlin.math.PI / 180 * lat
			return tileCount * (1.0 - kotlin.math.log(kotlin.math.tan(latRad) + 1.0 / kotlin.math.cos(latRad), 10.0) / kotlin.math.PI) / 2
		}

		fun countPixelSize(latitude: Double, zoom: Int): Double {
			return EARTH_CIRCUMFERENCE * kotlin.math.cos(latitude.deg2rad()) / 2.0.pow(zoom + 8)
		}
	}
}

@Entity(tableName = "location_data")
data class DatabaseLocation(@Embedded val location: Location) {
	@PrimaryKey(autoGenerate = true)
	var id: Int = 0
}