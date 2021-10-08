package com.adsamcik.tracker.shared.base.extension

import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoField

/**
 * Is before or equal to other date.
 */
fun ZonedDateTime.isBeforeOrEqual(other: ZonedDateTime): Boolean = isBefore(other) || isEqual(other)

/**
 * Is after or equal to other date.
 */
fun ZonedDateTime.isAfterOrEqual(other: ZonedDateTime): Boolean = isAfter(other) || isEqual(other)

/**
 * Checks if two instants represent the same day
 */
fun Instant.isTheSameDay(other: Instant): Boolean =
		get(ChronoField.DAY_OF_YEAR) == other.get(ChronoField.DAY_OF_YEAR) &&
				get(ChronoField.YEAR) == other.get(ChronoField.YEAR)


/**
 * Converts ZonedDateTime to milliseconds since epoch
 */
fun ZonedDateTime.toEpochMillis(): Long = toInstant().toEpochMilli()
