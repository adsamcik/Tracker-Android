package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.CalendarHistoryPresenceRead
import com.adsamcik.tracker.stats.api.repository.HistoryContinuation
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRead
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Uses the existing qualified page reader; the caller keeps all pages in one Room snapshot. */
internal suspend fun readCalendarPresenceSnapshot(range: HistoryRange, zone: ZoneId,
    readPage: suspend (HistoryRangeRequest) -> HistoryRangeRead): CalendarHistoryPresenceRead {
    val days = linkedSetOf<Long>()
    val problems = linkedSetOf<HistoryProblem>()
    var snapshot: TrackingHistoryReadSnapshot? = null
    var remaining = range
    var after: HistoryContinuation? = null
    repeat(MAX_CALENDAR_PAGES) {
        currentCoroutineContext().ensureActive()
        val request = HistoryRangeRequest(
            remaining, if (after == null) { 1 } else { CALENDAR_RECOVERY_PAGE_SIZE }, after)
        val read = readPage(request)
        val problem = calendarPageProblem(snapshot, request, read)
        if (problem != null) {
            return CalendarHistoryPresenceRead.Unavailable(problem)
        }
        read as HistoryRangeRead.Page
        snapshot = read.snapshot
        problems += read.problems
        val measured = read.rows.filter { it.measurement != null }
        measured.forEach { row ->
            problems += row.problems
            days += row.measuredCalendarDays(range, zone)
        }
        if (measured.isNotEmpty()) {
            val before = precedingCalendarDayBoundary(range, zone, measured)
            if (before <= range.fromInclusiveMs) {
                return CalendarHistoryPresenceRead.Snapshot(days, read.snapshot, problems)
            }
            remaining = range.copy(toExclusiveMs = before)
            after = null
        } else {
            after = read.next
            if (after == null) {
                return CalendarHistoryPresenceRead.Snapshot(days, read.snapshot, problems)
            }
        }
    }
    problems += HistoryProblem(null, HistoryProblemReason.DEPENDENCY_OVERFLOW)
    return CalendarHistoryPresenceRead.Snapshot(days, requireNotNull(snapshot), problems)
}

/** Validate a rejected-key continuation before advancing it inside the same snapshot. */
private fun calendarPageProblem(snapshot: TrackingHistoryReadSnapshot?, request: HistoryRangeRequest,
    read: HistoryRangeRead): HistoryProblemReason? = when (read) {
    is HistoryRangeRead.Unavailable -> read.reason
    is HistoryRangeRead.Page -> {
        val next = read.next
        when {
            snapshot != null && snapshot != read.snapshot -> HistoryProblemReason.INTEGRITY_UNAVAILABLE
            read.rows.none { it.measurement != null } && next != null &&
                (next.range != request.range || next == request.after) ->
                HistoryProblemReason.INTEGRITY_UNAVAILABLE
            else -> null
        }
    }
}

private fun UnifiedHistoryRow.measuredCalendarDays(range: HistoryRange, zone: ZoneId): LongRange {
    val start = maxOf(range.fromInclusiveMs, position.startTimeMs)
    val lastMeasuredMs = toExclusiveMs?.let { endExclusive ->
        if (endExclusive > position.startTimeMs) { endExclusive - 1L } else { position.startTimeMs }
    } ?: position.startTimeMs
    val end = minOf(range.toExclusiveMs - 1L, lastMeasuredMs)
    return if (start <= end) {
        val first = Instant.ofEpochMilli(start).atZone(zone).toLocalDate().toEpochDay()
        val last = Instant.ofEpochMilli(end).atZone(zone).toLocalDate().toEpochDay()
        first..last
    } else {
        LongRange.EMPTY
    }
}

private fun precedingCalendarDayBoundary(range: HistoryRange, zone: ZoneId,
    measured: List<UnifiedHistoryRow>): Long {
    val oldest = measured.minOf { it.position.startTimeMs }.coerceAtLeast(range.fromInclusiveMs)
    return Instant.ofEpochMilli(oldest).atZone(zone).toLocalDate().atStartOfDay(zone)
        .toInstant().toEpochMilli().coerceAtLeast(range.fromInclusiveMs)
}

private const val MAX_CALENDAR_PAGES = 64
private const val CALENDAR_RECOVERY_PAGE_SIZE = 100
private const val CALENDAR_REFRESH_INTERVAL_MILLIS = 1_000L

/** Complete finite reads and retain one refresh hint while live sources are writing. */
internal fun observeCalendarPresenceChanges(invalidations: Flow<Unit>,
    snapshot: suspend () -> TrackingHistoryReadSnapshot?,
    read: suspend () -> CalendarHistoryPresenceRead): Flow<CalendarHistoryPresenceRead> =
    invalidations.conflate().transform {
        emit(CalendarHistoryPresenceRead.Loading(snapshot()))
        emit(read())
        delay(CALENDAR_REFRESH_INTERVAL_MILLIS)
    }
