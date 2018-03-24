package com.adsamcik.signalcollector.extensions

import com.adsamcik.signalcollector.utility.Constants
import java.util.*

fun Calendar.roundToDate() {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}

fun Calendar.date(): Calendar {
    val calendar = cloneCalendar()
    calendar.roundToDate()
    return calendar
}

/**
 * @return Today as a day in unix time
 */
fun Calendar.dateUTC(): Calendar {
    val calendar = cloneCalendar()
    calendar.timeZone = java.util.TimeZone.getTimeZone("UTC")
    calendar.roundToDate()
    return calendar
}

fun Calendar.cloneCalendar(): Calendar {
    return clone() as Calendar
}

fun Calendar.timeInMillis(): Long {
    val calendar = cloneCalendar()
    return calendar.get(Calendar.HOUR_OF_DAY) * Constants.HOUR_IN_MILLISECONDS +
            calendar.get(Calendar.MINUTE) * Constants.MINUTE_IN_MILLISECONDS +
            calendar.get(Calendar.SECOND) * Constants.SECOND_IN_MILLISECONDS
}