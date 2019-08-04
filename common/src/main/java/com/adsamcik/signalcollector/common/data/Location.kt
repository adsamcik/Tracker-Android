package com.adsamcik.signalcollector.common.data

import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import androidx.room.ColumnInfo
import com.adsamcik.signalcollector.common.extension.LocationExtensions.EARTH_CIRCUMFERENCE
import com.adsamcik.signalcollector.common.extension.LocationExtensions.METER_DEGREE_LATITUDE
import com.adsamcik.signalcollector.common.extension.deg2rad
import com.adsamcik.signalcollector.common.extension.round
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlin.math.sqrt

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
		val verticalAccuracy: Float?,
		val speed: Float?,
		@ColumnInfo(name = "s_acc")
		val speedAccuracy: Float?) : Parcelable {

	constructor(parcel: Parcel) : this(
			parcel.readLong(),
			parcel.readDouble(),
			parcel.readDouble(),
			parcel.readValue(Double::class.java.classLoader) as? Double,
			parcel.readValue(Float::class.java.classLoader) as? Float,
			parcel.readValue(Float::class.java.classLoader) as? Float,
			parcel.readValue(Float::class.java.classLoader) as? Float,
			parcel.readValue(Float::class.java.classLoader) as? Float) {
	}

	constructor(location: android.location.Location) : this(location.time,
			location.latitude,
			location.longitude,
			if (location.hasAltitude()) location.altitude else null,
			if (location.hasAccuracy()) location.accuracy else null,
			if (Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null,
			if (location.hasSpeed()) location.speed else null,
			if (Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null)


	constructor(location: Location) : this(location.time, location.latitude, location.longitude, location.altitude, location.horizontalAccuracy, location.verticalAccuracy, location.speed, location.speedAccuracy)


	/// <summary>
	/// Calculates distance based on only latitude and longitude
	/// </summary>
	/// <param name="latlon">second location</param>
	/// <param name="unit">unit type</param>
	/// <returns></returns>
	fun distanceFlat(location: Location, unit: LengthUnit): Double {
		return distance(latitude, longitude, location.latitude, location.longitude, unit)
	}

	/// <summary>
	/// Calculates distance between two locations
	/// </summary>
	/// <param name="latlon">second location</param>
	/// <param name="unit">unity type</param>
	/// <returns></returns>
	fun distance(location: Location, unit: LengthUnit): Double {
		return if (location.altitude == null || altitude == null)
			distanceFlat(location, unit)
		else
			distance(latitude, longitude, altitude, location.latitude, location.longitude, location.altitude, unit)
	}

	/**
	 * Creates new location with rounded latitude to [precisionLatitudeInMeters] and longitude to [precisionLongitudeInMeters]
	 *
	 * @param precisionLatitudeInMeters Round latitude coordinate to meters
	 * @param precisionLongitudeInMeters Round longitude coordinate to meters
	 * @return New location containing rounded latitude and longitude and the rest of the original location data
	 */
	fun roundTo(precisionLatitudeInMeters: Double, precisionLongitudeInMeters: Double = precisionLatitudeInMeters): Location {
		val accLatitude = latitudeAccuracy(precisionLatitudeInMeters)
		val roundedLatitude = (latitude - latitude % accLatitude).round(6)

		val accLongitude = longitudeAccuracy(precisionLongitudeInMeters, roundedLatitude)
		val roundedLongitude = (longitude - longitude % accLongitude).round(6)
		return Location(time,
				roundedLatitude,
				roundedLongitude,
				altitude,
				horizontalAccuracy,
				verticalAccuracy,
				speed,
				speedAccuracy)
	}


	override fun writeToParcel(parcel: Parcel, flags: Int) {
		parcel.writeLong(time)
		parcel.writeDouble(latitude)
		parcel.writeDouble(longitude)
		parcel.writeValue(altitude)
		parcel.writeValue(horizontalAccuracy)
		parcel.writeValue(verticalAccuracy)
		parcel.writeValue(speed)
		parcel.writeValue(speedAccuracy)
	}

	override fun describeContents(): Int {
		return 0
	}

	companion object CREATOR : Parcelable.Creator<Location> {
		fun distance(firstLatitude: Double,
		             firstLongitude: Double,
		             secondLatitude: Double,
		             secondLongitude: Double,
		             unit: LengthUnit): Double {
			val lat1Rad = firstLatitude.deg2rad()
			val lat2Rad = secondLatitude.deg2rad()
			val latDistance = (secondLatitude - firstLatitude).deg2rad()
			val lonDistance = (secondLongitude - firstLongitude).deg2rad()

			val sinLatDistance = kotlin.math.sin(latDistance / 2)
			val sinLonDistance = kotlin.math.sin(lonDistance / 2)

			val a = sinLatDistance * sinLatDistance +
					kotlin.math.cos(lat1Rad) * kotlin.math.cos(lat2Rad) *
					sinLonDistance * sinLonDistance
			val c = 2 * kotlin.math.atan2(sqrt(a), sqrt(1 - a))

			val distance = EARTH_CIRCUMFERENCE * c
			return when (unit) {
				LengthUnit.Meter -> distance
				LengthUnit.Kilometer -> distance / 1000
				LengthUnit.Mile -> distance / 1.609
				LengthUnit.NauticalMile -> distance / 1.852
			}
		}

		/**
		 * Returns approximate distance between 2 3D coordinates.
		 * This function uses Pythagorean theorem for altitude
		 */
		fun distance(firstLatitude: Double,
		             firstLongitude: Double,
		             firstAltitude: Double,
		             secondLatitude: Double,
		             secondLongitude: Double,
		             secondAltitude: Double,
		             unit: LengthUnit): Double {
			val latLonDistance = distance(firstLatitude, firstLongitude, secondLatitude, secondLongitude, unit)
			val altitudeDifference = (secondAltitude - firstAltitude)
			return sqrt(altitudeDifference * altitudeDifference + latLonDistance * latLonDistance)
		}

		private fun calculateLineOfLongitudeM(latitude: Double) = kotlin.math.cos(latitude.deg2rad()) * EARTH_CIRCUMFERENCE

		fun longitudeAccuracy(precisionInMeters: Double, latitude: Double) = precisionInMeters * (360.0 / calculateLineOfLongitudeM(latitude)).round(6)

		fun latitudeAccuracy(precisionInMeters: Double) = (METER_DEGREE_LATITUDE * precisionInMeters).round(6)

		override fun createFromParcel(parcel: Parcel): Location {
			return Location(parcel)
		}

		override fun newArray(size: Int): Array<Location?> {
			return arrayOfNulls(size)
		}
	}
}

enum class LengthUnit {
	Meter,
	Kilometer,
	Mile,
	NauticalMile
}