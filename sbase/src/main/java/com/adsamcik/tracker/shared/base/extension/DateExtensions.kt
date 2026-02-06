package com.adsamcik.tracker.shared.base.extension

import java.time.Instant
import java.time.ZoneOffset
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
 * Checks if two instants represent the same day in UTC
 */
fun Instant.isTheSameDay(other: Instant): Boolean {
	val thisZdt = this.atZone(ZoneOffset.UTC)
	val otherZdt = other.atZone(ZoneOffset.UTC)
	return thisZdt.get(ChronoField.DAY_OF_YEAR) == otherZdt.get(ChronoField.DAY_OF_YEAR) &&
			thisZdt.get(ChronoField.YEAR) == otherZdt.get(ChronoField.YEAR)
}


/**
 * Converts ZonedDateTime to milliseconds since epoch
 */
fun ZonedDateTime.toEpochMillis(): Long = toInstant().toEpochMilli()
