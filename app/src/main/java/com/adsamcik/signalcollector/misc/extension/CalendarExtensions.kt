package com.adsamcik.signalcollector.misc.extension

import android.os.Build
import com.adsamcik.signalcollector.app.Constants
import java.util.*

fun createCalendarWithDate(year: Int, monthOfYear: Int, dayOfMonth: Int): Calendar {
	return if (Build.VERSION.SDK_INT >= 26)
		Calendar.Builder().setDate(year, monthOfYear, dayOfMonth).build()
	else {
		val cal = Calendar.getInstance()
		cal.set(year, monthOfYear, dayOfMonth, 0, 0, 0)
		cal
	}
}

fun Date.toCalendar(): Calendar {
	val calendar = Calendar.getInstance()
	calendar.time = this
	return calendar
}

/**
 * Rounds calendar to date by settings [Calendar.HOUR_OF_DAY], [Calendar.MINUTE], [Calendar.SECOND], [Calendar.MILLISECOND] to 0
 */
fun Calendar.roundToDate() {
	set(Calendar.HOUR_OF_DAY, 0)
	set(Calendar.MINUTE, 0)
	set(Calendar.SECOND, 0)
	set(Calendar.MILLISECOND, 0)
}

/**
 * Creates a new instance of this calendar which is rounded to date using [roundToDate]
 */
fun Calendar.date(): Calendar {
	val calendar = cloneCalendar()
	calendar.roundToDate()
	return calendar
}

/**
 * Creates a new instance of this calendar rounded to UTC date using [roundToDate]
 *
 * @return Today as a day in unix time
 */
fun Calendar.dateUTC(): Calendar {
	val calendar = cloneCalendar()
	calendar.timeZone = java.util.TimeZone.getTimeZone("UTC")
	calendar.roundToDate()
	return calendar
}

/**
 * Returns a copy of this calendar
 */
fun Calendar.cloneCalendar(): Calendar {
	return clone() as Calendar
}

/**
 * Returns time since midnight in milliseconds
 */
fun Calendar.timeInMillis(): Long {
	val calendar = cloneCalendar()
	return calendar.get(Calendar.HOUR_OF_DAY) * Constants.HOUR_IN_MILLISECONDS +
			calendar.get(Calendar.MINUTE) * Constants.MINUTE_IN_MILLISECONDS +
			calendar.get(Calendar.SECOND) * Constants.SECOND_IN_MILLISECONDS
}