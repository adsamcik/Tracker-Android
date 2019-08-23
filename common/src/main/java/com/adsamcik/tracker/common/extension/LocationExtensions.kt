package com.adsamcik.tracker.common.extension

import android.location.Location

@Suppress("Unused")
object LocationExtensions {
	const val EARTH_CIRCUMFERENCE: Int = 40075000
	const val METER_DEGREE_LATITUDE: Double = 360.0 / EARTH_CIRCUMFERENCE

	private const val ESTIMATE_LOCATION_PROVIDER: String = "Estimate"

	/// <summary>
/// Tries to approximate location in time between 2 known locations.
/// Can handle values outside of standard bounds to not fail on errors caused during collection.
/// </summary>
/// <param name="locationFrom">First location</param>
/// <param name="timeFrom">First location time</param>
/// <param name="locationTo">Second location</param>
/// <param name="timeTo">Second location time</param>
/// <param name="timeCurrent">Time of the location we want to interpolate</param>
/// <returns>Interpolated location</returns>
	fun approximateLocation(
			locationFrom: Location,
			locationTo: Location,
			estimateTime: Long
	): Location {
		val timeTo = locationTo.time
		val timeFrom = locationFrom.time

		if (timeFrom > timeTo) {
			throw IllegalArgumentException("Time from cannot be larger than time to")
		}

		if (estimateTime <= timeFrom) return locationFrom
		else if (estimateTime >= timeTo) return locationTo

		val diff = (estimateTime - timeFrom).toDouble() / (timeTo - timeFrom).toDouble()
		return interpolateLocation(locationFrom, locationTo, diff)
	}

	/// <summary>
/// Interpolates third location based on first two and time
/// </summary>
/// <param name="lOne">First location</param>
/// <param name="lTwo">Second location</param>
/// <param name="t">Time in percents (0 is first location, 1 is second location)</param>
/// <returns>Interpolated location</returns>
	fun interpolateLocation(from: Location, to: Location, delta: Double): Location {
		val estimate = Location(ESTIMATE_LOCATION_PROVIDER)
		estimate.latitude = from.latitude + (to.latitude - from.latitude) * delta
		estimate.longitude = from.longitude + (to.longitude - from.longitude) * delta

		if (from.hasAltitude()) {
			if (to.hasAltitude()) {
				estimate.altitude = from.altitude + (to.altitude - from.altitude) * delta
			} else {
				estimate.altitude = from.altitude
			}
		} else if (to.hasAltitude()) {
			estimate.altitude = to.altitude
		}

		estimate.time = from.time + (to.time - from.time * delta).toLong()

		return estimate
	}
}


