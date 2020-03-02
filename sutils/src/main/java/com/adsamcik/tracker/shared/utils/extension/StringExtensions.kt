package com.adsamcik.tracker.shared.utils.extension

import android.content.Context
import android.content.res.Resources
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.constant.LengthConstants
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.R
import com.adsamcik.tracker.shared.preferences.extension.formatAncientRome
import com.adsamcik.tracker.shared.preferences.extension.formatMetric
import com.adsamcik.tracker.shared.preferences.extension.formatUscs
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.preferences.type.SpeedFormat

/**
 * Format speed in meters per second in length system specified by user preferences.
 * @param context Context - required to access user preferences.
 * @param metersPerSecond Speed in meters per second.
 * @param digits Number of decimal places.
 *
 * @return Formatted speed.
 */
fun Resources.formatSpeed(context: Context, metersPerSecond: Double, digits: Int): String {
	val lengthSystem = Preferences.getLengthSystem(context)
	val speedFormat = Preferences.getSpeedFormat(context)
	return formatSpeed(metersPerSecond, digits, lengthSystem, speedFormat)
}

/**
 * Format speed in meters per second in length system specified by user preferences.
 * @param metersPerSecond Speed in meters per second.
 * @param digits Number of decimal places.
 * @param lengthSystem Length unit system.
 * @param speedFormat Speed format.
 *
 * @return Formatted speed.
 */
fun Resources.formatSpeed(
		metersPerSecond: Float,
		digits: Int,
		lengthSystem: LengthSystem,
		speedFormat: SpeedFormat
): String {
	return formatSpeed(metersPerSecond.toDouble(), digits, lengthSystem, speedFormat)
}

/**
 * Format speed in meters per second in length system specified by user preferences.
 * @param metersPerSecond Speed in meters per second.
 * @param digits Number of decimal places.
 * @param lengthSystem Length unit system.
 * @param speedFormat Speed format.
 *
 * @return Formatted speed.
 */
fun Resources.formatSpeed(
		metersPerSecond: Double,
		digits: Int,
		lengthSystem: LengthSystem,
		speedFormat: SpeedFormat
): String {
	return when (speedFormat) {
		SpeedFormat.Second -> getString(
				R.string.per_second_abbr,
				formatDistance(metersPerSecond, digits, lengthSystem)
		)
		SpeedFormat.Minute -> getString(
				R.string.per_minute_abbr,
				formatDistance(metersPerSecond * Time.MINUTE_IN_SECONDS, digits, lengthSystem)
		)
		SpeedFormat.Hour -> getString(
				R.string.per_hour_abbr,
				formatDistance(metersPerSecond * Time.HOUR_IN_SECONDS, digits, lengthSystem)
		)
	}
}

/**
 * Format distance in meters in length system specified by user preferences.
 * @param distanceInMeters Distance in meters.
 * @param digits Number of decimal places.
 * @param unit Length unit system.
 *
 * @return Formatted speed.
 */
fun Resources.formatDistance(
		distanceInMeters: Int,
		digits: Int,
		unit: LengthSystem
): String {
	return formatDistance(distanceInMeters.toDouble(), digits, unit)
}

/**
 * Format distance in meters in length system specified by user preferences.
 * @param distanceInMeters Distance in meters.
 * @param digits Number of decimal places.
 * @param unit Length unit system.
 *
 * @return Formatted speed.
 */
fun Resources.formatDistance(
		distanceInMeters: Float,
		digits: Int,
		unit: LengthSystem
): String {
	return formatDistance(distanceInMeters.toDouble(), digits, unit)
}

/**
 * Format distance in meters in length system specified by user preferences.
 * @param distanceInMeters Distance in meters.
 * @param digits Number of decimal places.
 * @param unit Length unit system.
 *
 * @return Formatted speed.
 */
fun Resources.formatDistance(
		distanceInMeters: Double,
		digits: Int,
		unit: LengthSystem
): String {
	return when (unit) {
		LengthSystem.Metric -> formatMetric(
				distanceInMeters,
				digits
		)
		LengthSystem.Imperial -> {
			val feet = distanceInMeters * LengthConstants.FEET_IN_METERS
			formatUscs(feet, digits)
		}
		LengthSystem.AncientRoman -> {
			val passus = distanceInMeters / LengthConstants.METERS_IN_PASSUS
			formatAncientRome(passus, digits)
		}
	}
}

