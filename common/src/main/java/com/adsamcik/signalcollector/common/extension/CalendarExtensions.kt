package com.adsamcik.signalcollector.common.extension

import android.os.Build
import com.adsamcik.signalcollector.common.Time
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

fun createCalendarWithTime(time: Long): Calendar {
	return if (Build.VERSION.SDK_INT >= 26)
		Calendar.Builder().setInstant(time).build()
	else {
		Date(time).toCalendar()
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
fun Calendar.toDate(): Calendar {
	val calendar = cloneCalendar()
	calendar.roundToDate()
	return calendar
}

/**
 * Creates a new instance of this calendar rounded to UTC date using [roundToDate]
 *
 * @return Today as a day in unix time
 */
fun Calendar.toDateUTC(): Calendar {
	val calendar = cloneCalendar()
	calendar.timeZone = TimeZone.getTimeZone("UTC")
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
fun Calendar.toTimeSinceMidnight(): Long = get(Calendar.HOUR_OF_DAY) * Time.HOUR_IN_MILLISECONDS +
		get(Calendar.MINUTE) * Time.MINUTE_IN_MILLISECONDS +
		get(Calendar.SECOND) * Time.SECOND_IN_MILLISECONDS

val Calendar.month: Int get() = get(Calendar.MONTH)
val Calendar.day: Int get() = get(Calendar.DAY_OF_MONTH)
val Calendar.dayOfYear: Int get() = get(Calendar.DAY_OF_YEAR)
val Calendar.year: Int get() = get(Calendar.YEAR)


