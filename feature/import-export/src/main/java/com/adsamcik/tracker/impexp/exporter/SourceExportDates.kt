package com.adsamcik.tracker.impexp.exporter

import java.time.LocalDate
import java.time.ZoneId

/** Calendar days as shown to the person selecting data to export. */
internal data class SourceExportDates(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
) {
	init {
		require(toExclusiveMs > fromInclusiveMs)
	}

	fun contains(timeMs: Long): Boolean = timeMs >= fromInclusiveMs && timeMs < toExclusiveMs

	/** A saved event with uncertain time can touch the selected dates at either boundary. */
	fun touches(firstPossibleMs: Long, lastPossibleMs: Long): Boolean =
		firstPossibleMs < toExclusiveMs && lastPossibleMs >= fromInclusiveMs

	/** Source coverage or a recorded gap is an interval; zero-length events use [contains]. */
	fun overlaps(startMs: Long, endMs: Long): Boolean =
		if (startMs == endMs) contains(startMs)
		else startMs < toExclusiveMs && endMs > fromInclusiveMs

	/** Captured run bounds and gaps end at [endMs], with no coverage after that instant. */
	fun containsWholeInterval(startMs: Long, endMs: Long): Boolean =
		startMs >= fromInclusiveMs &&
			(if (startMs == endMs) contains(startMs) else endMs <= toExclusiveMs)

	/** A scan's latest possible instant is inclusive. */
	fun containsWholePossibleTime(firstMs: Long, lastMs: Long): Boolean =
		firstMs >= fromInclusiveMs && lastMs < toExclusiveMs

	companion object {
		fun of(firstDay: LocalDate, lastDay: LocalDate, displayedZone: ZoneId): SourceExportDates {
			require(!lastDay.isBefore(firstDay))
			return SourceExportDates(
				fromInclusiveMs = firstDay.atStartOfDay(displayedZone).toInstant().toEpochMilli(),
				toExclusiveMs = lastDay.plusDays(1).atStartOfDay(displayedZone).toInstant().toEpochMilli(),
			)
		}
	}
}
