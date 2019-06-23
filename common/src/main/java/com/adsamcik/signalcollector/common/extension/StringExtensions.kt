package com.adsamcik.signalcollector.common.extension

import android.content.Context
import android.content.res.Resources
import com.adsamcik.signalcollector.common.R
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.misc.LengthSystem
import com.adsamcik.signalcollector.common.misc.SpeedFormat
import com.adsamcik.signalcollector.common.preference.Preferences
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

fun Double.format(digits: Int): String = java.lang.String.format("%.${digits}f", this)

fun Float.format(digits: Int): String = java.lang.String.format("%.${digits}f", this)

fun Long.formatAsDate(): String {
	val date = Date(this)
	return SimpleDateFormat.getDateInstance().format(date)
}

fun Long.formatAsTime(): String {
	val date = Date(this)
	return SimpleDateFormat.getTimeInstance().format(date)
}

fun Long.formatAsDateTime(): String {
	val date = Date(this)
	return SimpleDateFormat.getDateTimeInstance().format(date)
}

fun Long.formatReadable(): String {
	val df = DecimalFormat("#,###,###")
	return df.format(this)
}

fun Int.formatReadable(): String {
	val df = DecimalFormat("#,###,###")
	return df.format(this)
}

fun Long.formatAsDuration(context: Context): String {
	val resources = context.resources
	if (this == 0L) return resources.getString(R.string.second_short, 0)

	var seconds = this / Time.SECOND_IN_MILLISECONDS
	var minutes = 0L
	var hours = 0L
	var days = 0L

	if (seconds >= Time.SECONDS_IN_A_MINUTE) {
		minutes = seconds / Time.SECONDS_IN_A_MINUTE
		seconds -= minutes * Time.SECONDS_IN_A_MINUTE

		if (minutes >= Time.MINUTES_IN_AN_HOUR) {
			hours = minutes / Time.MINUTES_IN_AN_HOUR
			minutes -= hours * Time.MINUTES_IN_AN_HOUR

			if (hours >= Time.HOURS_IN_A_DAY) {
				days = hours / Time.HOURS_IN_A_DAY
				hours -= days * Time.HOURS_IN_A_DAY
			}
		}
	}

	val builder = StringBuilder(50)

	if (days > 0) {
		builder.append(resources.getString(R.string.day_short, days))
	}

	if (hours > 0) {
		if (builder.isNotBlank()) {
			builder.append(' ')
		}
		builder.append(resources.getString(R.string.hour_short, hours))
	}

	if (minutes > 0) {
		if (builder.isNotBlank()) {
			builder.append(' ')
		}
		builder.append(resources.getString(R.string.minute_short, minutes))
	}

	if (seconds > 0) {
		if (builder.isNotBlank()) {
			builder.append(' ')
		}
		builder.append(resources.getString(R.string.second_short, seconds))
	}

	return builder.toString()
}

fun Double.formatReadable(digits: Int): String {
	val df = DecimalFormat("#,###,###.${"#".repeat(digits)}")
	return df.format(this).removeSuffix(".")
}

fun Float.formatReadable(digits: Int): String {
	val df = DecimalFormat("#,###,###.${"#".repeat(digits)}")
	return df.format(this).removeSuffix(".")
}

fun Long.formatAsShortDateTime(): String {
	val date = Date(this)
	return SimpleDateFormat.getDateTimeInstance(SimpleDateFormat.SHORT, SimpleDateFormat.SHORT).format(date)
}

fun Resources.formatSpeed(metersPerSecond: Float, digits: Int, lengthSystem: LengthSystem, speedFormat: SpeedFormat): String {
	return formatSpeed(metersPerSecond.toDouble(), digits, lengthSystem, speedFormat)
}

fun Resources.formatSpeed(context: Context, metersPerSecond: Double, digits: Int): String {
	val lengthSystem = Preferences.getLengthSystem(context)
	val speedFormat = Preferences.getSpeedFormat(context)
	return formatSpeed(metersPerSecond, digits, lengthSystem, speedFormat)
}

fun Resources.formatSpeed(metersPerSecond: Double, digits: Int, lengthSystem: LengthSystem, speedFormat: SpeedFormat): String {
	return when (speedFormat) {
		SpeedFormat.Second -> getString(R.string.per_second_abbr, formatDistance(metersPerSecond, digits, lengthSystem))
		SpeedFormat.Minute -> getString(R.string.per_minute_abbr, formatDistance(metersPerSecond * Time.SECONDS_IN_A_MINUTE, digits, lengthSystem))
		SpeedFormat.Hour -> getString(R.string.per_hour_abbr, formatDistance(metersPerSecond * Time.SECONDS_IN_AN_HOUR, digits, lengthSystem))
	}
}

fun Resources.formatDistance(value: Int, digits: Int, unit: LengthSystem): String {
	return formatDistance(value.toDouble(), digits, unit)
}

fun Resources.formatDistance(meters: Float, digits: Int, unit: LengthSystem): String {
	return formatDistance(meters.toDouble(), digits, unit)
}

fun Resources.formatDistance(meters: Double, digits: Int, unit: LengthSystem): String {
	return when (unit) {
		LengthSystem.Metric -> formatMetric(meters, digits)
		LengthSystem.Imperial -> {
			val feet = 3.280839895 * meters
			formatImperial(feet, digits)
		}
		LengthSystem.AncientRoman -> {
			val passus = meters / 1.48
			formatAncientRome(passus, digits)
		}
	}
}

private fun Resources.formatMetric(meters: Double, digits: Int): String {
	return if (meters >= 1000.0) {
		val kilometers = meters / 1000.0
		getString(R.string.kilometer_abbr, kilometers.formatReadable(digits))
	} else {
		getString(R.string.meter_abbr, meters.formatReadable(digits))
	}
}

private fun Resources.formatImperial(feet: Double, digits: Int): String {
	return if (feet >= 5280.0) {
		val miles = feet / 5280.0
		getString(R.string.mile_abbr, miles.formatReadable(digits))
	} else {
		getString(R.string.feet_abbr, feet.formatReadable(digits))
	}
}

private fun Resources.formatAncientRome(passus: Double, digits: Int): String {
	return if (passus >= 1000.0) {
		val millepassus = passus / 1000.0
		getString(R.string.millepassus, millepassus.formatReadable(digits))
	} else {
		getString(R.string.passus, passus.formatReadable(digits))
	}
}