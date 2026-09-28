package com.adsamcik.tracker.statistics.ui

import com.adsamcik.tracker.stats.api.repository.SessionOrganizationRead
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Group presence is distinct from recording intensity; half-open bounds avoid next-day ghosts. */
internal fun organizationCalendarDays(read: SessionOrganizationRead?, month: YearMonth,
	zone: ZoneId = ZoneId.systemDefault()): Set<LocalDate> {
	val sessions = (read as? SessionOrganizationRead.Content)?.sessions ?: return emptySet()
	return (1..month.lengthOfMonth()).map { month.atDay(it) }.filterTo(mutableSetOf()) { day ->
		val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
		val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
		sessions.any { it.fromMs?.let { start -> start < to && (it.toMs?.let { end -> end > from } ?: true) } == true }
	}
}
