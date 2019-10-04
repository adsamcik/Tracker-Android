package com.adsamcik.tracker.common.data

import android.os.Build
import android.os.Parcelable
import androidx.room.ColumnInfo
import com.adsamcik.tracker.common.constant.LengthConstants
import com.adsamcik.tracker.common.extension.LocationExtensions.EARTH_CIRCUMFERENCE
import com.adsamcik.tracker.common.extension.LocationExtensions.METER_DEGREE_LATITUDE
import com.adsamcik.tracker.common.extension.toRadians
import com.adsamcik.tracker.common.extension.round
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.android.parcel.Parcelize
import kotlin.math.sqrt

@JsonClass(generateAdapter = true)
@Parcelize
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
		val speedAccuracy: Float?
) : Parcelable {

	@Suppress("MagicNumber")
	constructor(location: android.location.Location) : this(
			location.time,
			location.latitude,
			location.longitude,
			if (location.hasAltitude()) location.altitude else null,
			if (location.hasAccuracy()) location.accuracy else null,
			if (Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null,
			if (location.hasSpeed()) location.speed else null,
			if (Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null
	)


	constructor(location: Location)
			: this(
			location.time,
			location.latitude,
			location.longitude,
			location.altitude,
			location.horizontalAccuracy,
			location.verticalAccuracy,
			location.speed,
			location.speedAccuracy
	)


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
		return if (location.altitude == null || altitude == null) {
			distanceFlat(location, unit)
		} else {
			distance(
					latitude,
					longitude,
					altitude,
					location.latitude,
					location.longitude,
					location.altitude,
					unit
			)
		}
	}

	/**
	 * Creates new location with rounded latitude to [precisionLatitudeInMeters]
	 * and longitude to [precisionLongitudeInMeters]
	 *
	 * @param precisionLatitudeInMeters Round latitude coordinate to meters
	 * @param precisionLongitudeInMeters Round longitude coordinate to meters
	 * @return New location containing rounded latitude and longitude and the rest of the original location data
	 */
	fun roundTo(
			precisionLatitudeInMeters: Double,
			precisionLongitudeInMeters: Double = precisionLatitudeInMeters
	): Location {
		val accLatitude = latitudeAccuracy(precisionLatitudeInMeters)
		val roundedLatitude = (latitude - latitude % accLatitude).round(ROUND_TO_DECIMALS)

		val accLongitude = longitudeAccuracy(precisionLongitudeInMeters, roundedLatitude)
		val roundedLongitude = (longitude - longitude % accLongitude).round(ROUND_TO_DECIMALS)
		return Location(
				time,
				roundedLatitude,
				roundedLongitude,
				altitude,
				horizontalAccuracy,
				verticalAccuracy,
				speed,
				speedAccuracy
		)
	}

	companion object {
		private const val ROUND_TO_DECIMALS = 6

		fun distance(
				firstLatitude: Double,
				firstLongitude: Double,
				secondLatitude: Double,
				secondLongitude: Double,
				unit: LengthUnit
		): Double {
			val lat1Rad = firstLatitude.toRadians()
			val lat2Rad = secondLatitude.toRadians()
			val latDistance = (secondLatitude - firstLatitude).toRadians()
			val lonDistance = (secondLongitude - firstLongitude).toRadians()

			val sinLatDistance = kotlin.math.sin(latDistance / 2)
			val sinLonDistance = kotlin.math.sin(lonDistance / 2)

			val a = sinLatDistance * sinLatDistance +
					kotlin.math.cos(lat1Rad) * kotlin.math.cos(lat2Rad) *
					sinLonDistance * sinLonDistance
			val c = 2 * kotlin.math.atan2(sqrt(a), sqrt(1 - a))

			val distance = EARTH_CIRCUMFERENCE * c
			return when (unit) {
				LengthUnit.Meter -> distance
				LengthUnit.Kilometer -> distance / LengthConstants.METERS_IN_KILOMETER
				LengthUnit.Mile -> distance / LengthConstants.METERS_IN_MILE
				LengthUnit.NauticalMile -> distance / LengthConstants.METERS_IN_NAUTICAL_MILE
			}
		}

		/**
		 * Returns approximate distance between 2 3D coordinates.
		 * This function uses Pythagorean theorem for altitude
		 */
		@Suppress("LongParameterList")
		fun distance(
				firstLatitude: Double,
				firstLongitude: Double,
				firstAltitude: Double,
				secondLatitude: Double,
				secondLongitude: Double,
				secondAltitude: Double,
				unit: LengthUnit
		): Double {
			val latLonDistance = distance(
					firstLatitude,
					firstLongitude,
					secondLatitude,
					secondLongitude,
					unit
			)
			val altitudeDifference = (secondAltitude - firstAltitude)
			return sqrt(altitudeDifference * altitudeDifference + latLonDistance * latLonDistance)
		}

		private fun calculateLineOfLongitudeM(latitude: Double) =
				kotlin.math.cos(latitude.toRadians()) * EARTH_CIRCUMFERENCE

		fun longitudeAccuracy(precisionInMeters: Double, latitude: Double): Double =
				precisionInMeters * (360.0 / calculateLineOfLongitudeM(latitude)).round(
						ROUND_TO_DECIMALS
				)

		fun latitudeAccuracy(precisionInMeters: Double): Double =
				(METER_DEGREE_LATITUDE * precisionInMeters).round(ROUND_TO_DECIMALS)
	}
}

@JsonClass(generateAdapter = true)
data class BaseLocation(
		@Json(name = "lat")
		@ColumnInfo(name = "lat")
		val latitude: Double,
		@Json(name = "lon")
		@ColumnInfo(name = "lon")
		val longitude: Double,
		@Json(name = "alt")
		@ColumnInfo(name = "alt")
		val altitude: Double?
) {

	constructor(location: Location) : this(location.latitude, location.longitude, location.altitude)
}

enum class LengthUnit {
	Meter,
	Kilometer,
	Mile,
	NauticalMile
}

