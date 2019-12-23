package com.adsamcik.tracker.shared.utils.extension

import android.content.Context
import android.content.res.Resources
import com.adsamcik.tracker.shared.base.R
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.constant.LengthConstants
import com.adsamcik.tracker.shared.base.extension.formatAncientRome
import com.adsamcik.tracker.shared.base.extension.formatImperial
import com.adsamcik.tracker.shared.base.extension.formatMetric
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.preferences.type.SpeedFormat

fun Resources.formatSpeed(context: Context, metersPerSecond: Double, digits: Int): String {
	val lengthSystem = Preferences.getLengthSystem(context)
	val speedFormat = Preferences.getSpeedFormat(context)
	return formatSpeed(metersPerSecond, digits, lengthSystem, speedFormat)
}

fun Resources.formatSpeed(
		metersPerSecond: Float,
		digits: Int,
		lengthSystem: LengthSystem,
		speedFormat: SpeedFormat
): String {
	return formatSpeed(metersPerSecond.toDouble(), digits, lengthSystem, speedFormat)
}

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

fun Resources.formatDistance(
		value: Int,
		digits: Int,
		unit: LengthSystem
): String {
	return formatDistance(value.toDouble(), digits, unit)
}

fun Resources.formatDistance(
		meters: Float,
		digits: Int,
		unit: LengthSystem
): String {
	return formatDistance(meters.toDouble(), digits, unit)
}

fun Resources.formatDistance(
		meters: Double,
		digits: Int,
		unit: LengthSystem
): String {
	return when (unit) {
		LengthSystem.Metric -> formatMetric(
				meters,
				digits
		)
		LengthSystem.Imperial -> {
			val feet = meters * LengthConstants.FEET_IN_METERS
			formatImperial(feet, digits)
		}
		LengthSystem.AncientRoman -> {
			val passus = meters / LengthConstants.METERS_IN_PASSUS
			formatAncientRome(passus, digits)
		}
	}
}

