package com.adsamcik.tracker.shared.base.extension

import android.content.Context
import android.content.res.Resources
import com.adsamcik.tracker.shared.base.R
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.constant.LengthConstants
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

/**
 * Formats double to more readable string format.
 */
fun Double.format(digits: Int): String = java.lang.String.format("%.${digits}f", this)

/**
 * Formats float to more readable string format.
 */
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

@Suppress("ComplexMethod")
fun Long.formatAsDuration(context: Context): String {
	val resources = context.resources
	if (this == 0L) return resources.getString(R.string.second_short, 0)

	var seconds = this / Time.SECOND_IN_MILLISECONDS
	var minutes = 0L
	var hours = 0L
	var days = 0L

	if (seconds >= Time.MINUTE_IN_SECONDS) {
		minutes = seconds / Time.MINUTE_IN_SECONDS
		seconds -= minutes * Time.MINUTE_IN_SECONDS

		if (minutes >= Time.HOUR_IN_MINUTES) {
			hours = minutes / Time.HOUR_IN_MINUTES
			minutes -= hours * Time.HOUR_IN_MINUTES

			if (hours >= Time.DAY_IN_HOURS) {
				days = hours / Time.DAY_IN_HOURS
				hours -= days * Time.DAY_IN_HOURS
			}
		}
	}

	@Suppress("MagicNumber")
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
	val separator = df.decimalFormatSymbols.decimalSeparator
	return df.format(this).removeSuffix(separator.toString())
}

fun Float.formatReadable(digits: Int): String {
	val df = DecimalFormat("#,###,###.${"#".repeat(digits)}")
	val separator = df.decimalFormatSymbols.decimalSeparator
	return df.format(this).removeSuffix(separator.toString())
}

fun Long.formatAsShortDateTime(): String {
	val date = Date(this)
	return SimpleDateFormat.getDateTimeInstance(SimpleDateFormat.SHORT, SimpleDateFormat.SHORT)
			.format(date)
}
