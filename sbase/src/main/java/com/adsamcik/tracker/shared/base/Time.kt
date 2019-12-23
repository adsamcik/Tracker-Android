package com.adsamcik.tracker.common

import android.os.SystemClock
import com.adsamcik.tracker.common.extension.roundToDate
import com.adsamcik.tracker.common.extension.toCalendar
import java.util.*

/**
 * Centralized access to time.
 * This ensures that time is taken from a single source and is therefore comparable.
 */
object Time {
	val nowMillis: Long get() = System.currentTimeMillis()

	val now: Calendar get() = Calendar.getInstance()

	val elapsedRealtimeMillis: Long get() = SystemClock.elapsedRealtime()

	val elapsedRealtimeNanos: Long get() = SystemClock.elapsedRealtimeNanos()

	val todayMillis: Long get() = roundToDate(nowMillis)

	val today: Calendar
		get() {
			val now = now
			now.roundToDate()
			return now
		}

	fun roundToDate(time: Long): Long {
		return Date(time).toCalendar().apply {
			roundToDate()
		}.timeInMillis
	}

	const val DAY_IN_HOURS: Long = 24L
	const val HOUR_IN_MINUTES: Long = 60L
	const val MINUTE_IN_SECONDS: Long = 60L
	const val WEEK_IN_DAYS: Long = 7L

	const val HOUR_IN_SECONDS: Long = HOUR_IN_MINUTES * MINUTE_IN_SECONDS

	const val MILISECONDS_IN_NANOSECONDS: Long = 1000000L
	const val SECOND_IN_MILLISECONDS: Long = 1000L
	const val SECOND_IN_NANOSECONDS: Long = SECOND_IN_MILLISECONDS * MILISECONDS_IN_NANOSECONDS
	const val MINUTE_IN_MILLISECONDS: Long = MINUTE_IN_SECONDS * SECOND_IN_MILLISECONDS
	const val HOUR_IN_MILLISECONDS: Long = HOUR_IN_MINUTES * MINUTE_IN_MILLISECONDS
	const val DAY_IN_MILLISECONDS: Long = DAY_IN_HOURS * HOUR_IN_MILLISECONDS
	const val WEEK_IN_MILLISECONDS: Long = WEEK_IN_DAYS * DAY_IN_MILLISECONDS
	const val DAY_IN_MINUTES: Long = DAY_IN_MILLISECONDS / MINUTE_IN_MILLISECONDS
}

