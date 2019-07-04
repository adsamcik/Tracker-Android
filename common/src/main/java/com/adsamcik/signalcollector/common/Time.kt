package com.adsamcik.signalcollector.common

import com.adsamcik.signalcollector.common.extension.roundToDate
import com.adsamcik.signalcollector.common.extension.toCalendar
import java.util.*

/**
 * Centralized access to time.
 * This ensures that time is taken from a single source and is therefore comparable.
 */
object Time {
	val nowMillis: Long get() = System.currentTimeMillis()

	val todayMillis: Long get() = roundToDate(nowMillis)

	fun roundToDate(time: Long): Long {
		return Date(time).toCalendar().apply {
			roundToDate()
		}.timeInMillis
	}

	const val HOURS_IN_A_DAY: Long = 24L
	const val MINUTES_IN_AN_HOUR: Long = 60L
	const val SECONDS_IN_A_MINUTE: Long = 60L
	const val DAYS_IN_A_WEEK = 7

	const val SECONDS_IN_AN_HOUR: Long = MINUTES_IN_AN_HOUR * SECONDS_IN_A_MINUTE

	const val NANOSECONDS_IN_A_MILISECOND = 1000000L
	const val SECOND_IN_MILLISECONDS: Long = 1000L
	const val SECOND_IN_NANOSECONDS: Long = SECOND_IN_MILLISECONDS * NANOSECONDS_IN_A_MILISECOND
	const val MINUTE_IN_MILLISECONDS: Long = SECONDS_IN_A_MINUTE * SECOND_IN_MILLISECONDS
	const val HOUR_IN_MILLISECONDS: Long = MINUTES_IN_AN_HOUR * MINUTE_IN_MILLISECONDS
	const val DAY_IN_MILLISECONDS: Long = HOURS_IN_A_DAY * HOUR_IN_MILLISECONDS
	const val WEEK_IN_MILLISECONDS: Long = DAYS_IN_A_WEEK * DAY_IN_MILLISECONDS
	const val DAY_IN_MINUTES: Long = DAY_IN_MILLISECONDS / MINUTE_IN_MILLISECONDS
}