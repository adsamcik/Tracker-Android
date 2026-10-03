package com.adsamcik.tracker.stats.data.repository

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.WeekFields

/** Longest consecutive local periods represented by discoveries already filtered to the window. */
internal fun longestDiscoveryPeriodStreak(
	timestamps: List<Long>,
	zone: ZoneId,
	weekFields: WeekFields?,
): Long {
	val periods = timestamps.map { timestamp ->
		val day = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
		if (weekFields == null) day.toEpochDay() else day.with(weekFields.dayOfWeek(), 1L).toEpochDay()
	}.distinct().sorted()
	val periodDays = if (weekFields == null) 1L else 7L
	var previous: Long? = null
	var current = 0L
	var best = 0L
	for (period in periods) {
		current = if (previous?.let { period - it == periodDays } == true) current + 1L else 1L
		best = maxOf(best, current)
		previous = period
	}
	return best
}
