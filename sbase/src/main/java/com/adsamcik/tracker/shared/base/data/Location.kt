package com.adsamcik.tracker.shared.base.data

import android.os.Build
import android.os.Parcelable
import androidx.room.ColumnInfo
import com.adsamcik.tracker.shared.base.constant.LengthConstants
import com.adsamcik.tracker.shared.base.extension.LocationExtensions.EARTH_CIRCUMFERENCE
import com.adsamcik.tracker.shared.base.extension.LocationExtensions.METER_DEGREE_LATITUDE
import com.adsamcik.tracker.shared.base.extension.round
import com.adsamcik.tracker.shared.base.extension.toRadians
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.parcelize.Parcelize
import kotlin.math.sqrt

/**
 * `Location` represents a set of geographic coordinates along with additional data such as altitude, accuracies, and speed.
 *
 * @property time The timestamp of the location data, represented as milliseconds since the Unix epoch.
 * @property latitude The latitude of the location in degrees. Values are in the range [-90.0, 90.0].
 * @property longitude The longitude of the location in degrees. Values are in the range [-180.0, 180.0].
 * @property altitude The altitude of the location in meters above the WGS 84 reference ellipsoid, or `null` if altitude is not available.
 * @property horizontalAccuracy The accuracy of the location's latitude and longitude coordinates in meters, or `null` if horizontal accuracy is not available.
 * @property verticalAccuracy The accuracy of the location's altitude in meters, or `null` if vertical accuracy is not available.
 * @property speed The speed of the device over ground in meters per second, or `null` if speed is not available.
 * @property speedAccuracy The accuracy of the speed measurement in meters per second, or `null` if speed accuracy is not available.
 *
 * The `@JsonClass` annotation indicates that this class can be serialized and deserialized by Moshi.
 * The `@Parcelize` annotation indicates that this class can be parcelled to be passed between Android components.
 * The `@ColumnInfo` annotations are used to specify the column names when storing the location data in a database using Room.
 */
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

	/**
	 * Calculates distance between locations based on latitude and longitude
	 *
	 * @param location Second location
	 * @param unit Length unity
	 */
	fun distanceFlat(location: Location, unit: LengthUnit): Double {
		return distance(latitude, longitude, location.latitude, location.longitude, unit)
	}

	/**
	 * Calculates distance between two locations using latitude, longitude and altitude.
	 *
	 * @param location Second location
	 * @param unit Length unity
	 */
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

		/**
		 * Calculates approximate 2D distance between 2 positions.
		 *
		 * @param firstLatitude Latitude of first position
		 * @param firstLongitude Longitude of first position
		 * @param secondLatitude Latitude of second position
		 * @param secondLongitude Longitude of second position
		 * @param unit Length unity of resulting distance
		 */
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
		 * Calculates approximate distance between two 3D coordinates.
		 * This function uses Pythagorean theorem for altitude.
		 *
		 * @param firstLatitude Latitude of first position
		 * @param firstLongitude Longitude of first position
		 * @param firstAltitude Altitude of first position
		 * @param secondLatitude Latitude of second position
		 * @param secondLongitude Longitude of second position
		 * @param secondAltitude Altitude of second position
		 * @param unit Length unity of resulting distance
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

		/***
		 * Calculates how much can longitude change to fit inside given precision.
		 *
		 * @param precisionInMeters Precision in meters
		 * @param latitude Latitude required for proper distance calculation
		 */
		fun longitudeAccuracy(precisionInMeters: Double, latitude: Double): Double =
				precisionInMeters * (360.0 / calculateLineOfLongitudeM(latitude)).round(
						ROUND_TO_DECIMALS
				)

		/**
		 * Calculates how much can latitude change to fit inside given precision.
		 *
		 * @param precisionInMeters Precision in meters
		 */
		fun latitudeAccuracy(precisionInMeters: Double): Double =
				(METER_DEGREE_LATITUDE * precisionInMeters).round(ROUND_TO_DECIMALS)
	}
}

/**
 * Base location data object
 */
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
		val altitude: Double? = null
) {

	val isValid: Boolean
		get() = latitude >= -90.0 && latitude <= 90.0 && longitude >= -180.0 && longitude <= 180.0

	constructor(location: Location) : this(location.latitude, location.longitude, location.altitude)
}

/**
 * Units of length
 */
enum class LengthUnit {
	Meter,
	Kilometer,
	Mile,
	NauticalMile
}

