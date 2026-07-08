package com.adsamcik.tracker.shared.utils.extension

import android.content.Context
import android.content.res.Resources
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.constant.LengthConstants
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.preferences.R
import com.adsamcik.tracker.shared.preferences.extension.formatAncientRome
import com.adsamcik.tracker.shared.preferences.extension.formatMetric
import com.adsamcik.tracker.shared.preferences.extension.formatUscs
import com.adsamcik.tracker.shared.preferences.extension.formatKnots
import com.adsamcik.tracker.shared.preferences.extension.formatSailing
import com.adsamcik.tracker.shared.preferences.extension.formatFlying
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
	val snapshot = TrackerSettingsQuick.snapshot(context)
	return formatSpeed(metersPerSecond, digits, snapshot.lengthSystem, snapshot.speedFormat)
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
	// Knots (nautical miles per hour) are the standard sailing speed unit — there's no
	// real-world "knots per second/minute", so Sailing always reports speed this way
	// regardless of the user's chosen speed format.
	if (lengthSystem == LengthSystem.Sailing) {
		val nauticalMilesPerHour = metersPerSecond * Time.HOUR_IN_SECONDS / LengthConstants.METERS_IN_NAUTICAL_MILE
		return formatKnots(nauticalMilesPerHour, digits)
	}

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
): String {	return when (unit) {
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
		LengthSystem.Sailing -> {
			val fathoms = distanceInMeters / LengthConstants.METERS_IN_FATHOM
			formatSailing(fathoms, digits)
		}
		LengthSystem.Flying -> {
			val feet = distanceInMeters / LengthConstants.METERS_IN_FOOT
			formatFlying(feet, digits)
		}
	}
}

