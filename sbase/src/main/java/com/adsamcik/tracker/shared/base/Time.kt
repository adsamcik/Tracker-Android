package com.adsamcik.tracker.shared.base

import android.os.SystemClock
import com.adsamcik.tracker.shared.base.extension.toEpochMillis
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Centralized access to time.
 * This ensures that time is taken from a single source and is therefore comparable.
 */
@Suppress("MemberVisibilityCanBePrivate")
object Time {
	/**
	 * Current date time in milliseconds since epoch
	 */
	val nowMillis: Long get() = System.currentTimeMillis()

	/**
	 * Current date time as [ZonedDateTime]
	 */
	val now: ZonedDateTime get() = ZonedDateTime.now()

	/**
	 * Elapsed time in milliseconds since boot.
	 */
	val elapsedRealtimeMillis: Long get() = SystemClock.elapsedRealtime()

	/**
	 * Elapsed time in nano seconds since boot.
	 */
	val elapsedRealtimeNanos: Long get() = SystemClock.elapsedRealtimeNanos()

	/**
	 * Today's date in milliseconds since epoch.
	 */
	val todayMillis: Long get() = roundToDate(nowMillis).toEpochMillis()

	/**
	 * Tomorrow date
	 */
	val tomorrow: ZonedDateTime
		get() = roundToDate(ZonedDateTime.now()).plusDays(1)

	/**
	 * Tomorrow date in milliseconds since epoch.
	 */
	val tomorrowMillis: Long
		get() = tomorrow.toEpochMillis()

	/**
	 * Today's date as calendar
	 */
	val today: ZonedDateTime
		get() {
			return roundToDate(now)
		}

	/**
	 * Round date time in milliseconds to date in milliseconds
	 */
	fun roundToDate(time: Long): ZonedDateTime {
		return roundToDate(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
	}

	/**
	 * Round date time in milliseconds to date in milliseconds
	 */
	fun roundToDate(time: ZonedDateTime): ZonedDateTime {
		return time
				.withNano(0)
				.withSecond(0)
				.withMinute(0)
				.withHour(0)
	}

	/**
	 * Returns zoned date time from milliseconds since epoch.
	 */
	fun ofEpochMilli(epochMilli: Long): ZonedDateTime {
		return Instant.ofEpochMilli(epochMilli).atZone(ZoneId.systemDefault())
	}

	const val DAY_IN_HOURS: Long = 24L
	const val HOUR_IN_MINUTES: Long = 60L
	const val MINUTE_IN_SECONDS: Long = 60L
	const val WEEK_IN_DAYS: Long = 7L

	const val HOUR_IN_SECONDS: Long = HOUR_IN_MINUTES * MINUTE_IN_SECONDS

	const val MILLISECONDS_IN_NANOSECONDS: Long = 1000000L
	const val SECOND_IN_MILLISECONDS: Long = 1000L
	const val SECOND_IN_NANOSECONDS: Long = SECOND_IN_MILLISECONDS * MILLISECONDS_IN_NANOSECONDS
	const val MINUTE_IN_MILLISECONDS: Long = MINUTE_IN_SECONDS * SECOND_IN_MILLISECONDS
	const val HOUR_IN_MILLISECONDS: Long = HOUR_IN_MINUTES * MINUTE_IN_MILLISECONDS
	const val DAY_IN_MILLISECONDS: Long = DAY_IN_HOURS * HOUR_IN_MILLISECONDS
	const val WEEK_IN_MILLISECONDS: Long = WEEK_IN_DAYS * DAY_IN_MILLISECONDS
	const val DAY_IN_MINUTES: Long = DAY_IN_MILLISECONDS / MINUTE_IN_MILLISECONDS

	const val QUARTER_DAY_IN_HOURS: Long = 6L
	const val HALF_DAY_IN_HOURS: Long = 12L
}

