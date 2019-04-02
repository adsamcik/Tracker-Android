package com.adsamcik.signalcollector.misc.extension

import android.content.Context
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.app.Constants
import com.adsamcik.signalcollector.misc.LengthSystem
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
	if (this == 0L)
		return resources.getString(R.string.second_short, 0)

	var seconds = this / Constants.SECOND_IN_MILLISECONDS
	var minutes = 0L
	var hours = 0L
	var days = 0L

	if (seconds >= 60) {
		minutes = seconds / 60
		seconds -= minutes * 60

		if (minutes >= 60) {
			hours = minutes / 60
			minutes -= hours * 60

			if (hours >= 24) {
				days = hours / 24
				hours -= days * 24
			}
		}
	}

	val builder = StringBuilder(50)

	if (days > 0)
		builder.append(resources.getString(R.string.day_short, days))

	if (hours > 0) {
		if (builder.isNotBlank())
			builder.append(' ')
		builder.append(resources.getString(R.string.hour_short, hours))
	}

	if (minutes > 0) {
		if (builder.isNotBlank())
			builder.append(' ')
		builder.append(resources.getString(R.string.minute_short, minutes))
	}

	if (seconds > 0) {
		if (builder.isNotBlank())
			builder.append(' ')
		builder.append(resources.getString(R.string.second_short, seconds))
	}

	return builder.toString()
}

fun Double.formatReadable(digits: Int): String {
	val df = DecimalFormat("#,###,###.${"#".repeat(digits)}")
	return df.format(this)
}

fun Float.formatReadable(digits: Int): String {
	val df = DecimalFormat("#,###,###.${"#".repeat(digits)}")
	return df.format(this)
}

fun Long.formatAsShortDateTime(): String {
	val date = Date(this)
	return SimpleDateFormat.getDateTimeInstance(SimpleDateFormat.SHORT, SimpleDateFormat.SHORT).format(date)
}

fun Float.formatAsDistance(digits: Int, unit: LengthSystem): String {
	return this.toDouble().formatAsDistance(digits, unit)
}

fun Double.formatAsDistance(digits: Int, unit: LengthSystem): String {
	return when (unit) {
		LengthSystem.Metric -> {
			if (this >= 10000.0)
				"${(this / 1000.0).formatReadable(digits)} km"
			else
				"${this.formatReadable(digits)} m"
		}
		LengthSystem.Imperial -> {
			val feet = 3.280839895 * this

			if (feet >= 5280) {
				val miles = feet / 5280
				"${miles.formatReadable(digits)} mile"
			} else
				"${feet.formatReadable(digits)} feet"
		}
	}
}

fun Int.formatAsDistance(digits: Int, unit: LengthSystem): String {
	return when (unit) {
		LengthSystem.Metric -> {
			if (this >= 10000)
				"${(this / 1000.0).formatReadable(digits)} km"
			else
				"${this.formatReadable()} m"
		}
		LengthSystem.Imperial -> {
			val feet = 3.280839895 * this

			if (feet >= 5280) {
				val miles = feet / 5280
				"${miles.formatReadable(digits)} mile"
			} else
				"${feet.formatReadable(digits)} feet"
		}
	}
}