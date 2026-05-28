package com.adsamcik.tracker.shared.base.database.aggregator

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import java.time.LocalDate
import java.time.ZoneId

/**
 * Aggregates session segment data into the daily_summary table.
 *
 * Two primary use cases:
 * - **Post-session materialization**: Called when a tracking session ends to
 *   recalculate the day's totals from all session segments (ground truth).
 * - **Periodic catch-up**: Called by [DailySummaryMaterializationWorker] on a
 *   24-hour schedule for crash recovery.
 *
 * The aggregator reads all [SessionSegment] rows for a given calendar day,
 * computes totals, and upserts a single [DailySummaryEntity] row. This is
 * idempotent — calling it multiple times for the same day produces the same result.
 *
 * [onDailySummaryWritten] is invoked after every successful upsert. The unified rule
 * engine injects a callback that marks the `daily_summary` table dirty in the
 * `MetricDirtyTracker` so downstream signal processors can short-circuit idle flushes.
 * Defaults to a no-op for legacy/test call sites and to avoid a circular dependency
 * on `:stats-api`.
 *
 * The callback MUST NOT throw — exceptions inside it propagate up through
 * `materializeDayFromSegments` and the orchestrator will report the materialization
 * as failed (potentially scheduling a fallback worker run). Keep callback work to
 * setting a bit, not blocking I/O.
 */
class DailySummaryAggregator(
	private val dailySummaryDao: DailySummaryDao,
	private val sessionSegmentDao: SessionSegmentDao,
	private val onDailySummaryWritten: () -> Unit = {},
) {
	/**
	 * Materialize daily summary for a specific epoch day by aggregating
	 * all session segments that fall within that calendar day. Segments that cross
	 * midnight (e.g. a run from 23:50 to 00:10) are prorated by the fraction of their
	 * duration that falls inside this calendar day, so neither day double-counts and
	 * neither day silently drops the segment.
	 *
	 * Pro-rating notes:
	 * - `distanceM` is split by time fraction. Constant-speed approximation; fine for
	 *   trips where speed doesn't vary wildly across midnight (the common case —
	 *   most cross-midnight segments are continuations of a vehicle ride or run).
	 * - `steps` is split by time fraction and rounded down. Tiny rounding loss possible.
	 * - `duration` is the exact in-day clamped interval.
	 * - `tripCount` adds 1 per overlapping segment (no fractional trips). A run that
	 *   crosses midnight reads as one trip on the day where the run started.
	 *
	 * @param epochDay the [LocalDate.toEpochDay] identifier for the local calendar day
	 */
	suspend fun materializeDayFromSegments(epochDay: Long) {
		val startOfDayMs = startOfLocalDayMs(epochDay)
		val endOfDayMs = startOfLocalDayMs(epochDay + 1)

		val segments = sessionSegmentDao.getOverlapping(startOfDayMs, endOfDayMs)
		var totalDistanceM = 0f
		var totalSteps = 0
		var totalDurationMs = 0L
		var tripCount = 0
		for (segment in segments) {
			val segmentDuration = (segment.endTimeMs - segment.startTimeMs).coerceAtLeast(1L)
			val inDayStart = maxOf(segment.startTimeMs, startOfDayMs)
			val inDayEnd = minOf(segment.endTimeMs, endOfDayMs)
			val inDayDuration = (inDayEnd - inDayStart).coerceAtLeast(0L)
			if (inDayDuration == 0L) continue
			val fraction = inDayDuration.toDouble() / segmentDuration.toDouble()

			totalDistanceM += (segment.distanceM * fraction).toFloat()
			totalSteps += ((segment.steps ?: 0) * fraction).toInt()
			totalDurationMs += inDayDuration
			// Count the trip on the day it STARTED so we don't double-count.
			if (segment.startTimeMs in startOfDayMs until endOfDayMs) tripCount += 1
		}

		val now = Time.nowMillis
		val existing = dailySummaryDao.getByDay(epochDay)
		if (segments.isNotEmpty() || existing != null) {
			dailySummaryDao.upsert(
				dateEpochDay = epochDay,
				totalDistanceM = totalDistanceM,
				totalSteps = totalSteps,
				totalDurationMs = totalDurationMs,
				tripCount = tripCount,
				activeTrackingMs = existing?.activeTrackingMs ?: 0L,
				lastUpdatedMs = now,
			)
			onDailySummaryWritten()
		}
	}

	/**
	 * Materialize today's daily summary from session segments.
	 */
	suspend fun materializeToday() {
		val todayEpochDay = LocalDate.now().toEpochDay()
		materializeDayFromSegments(todayEpochDay)
	}

	private fun startOfLocalDayMs(epochDay: Long): Long {
		return LocalDate.ofEpochDay(epochDay)
			.atStartOfDay(ZoneId.systemDefault())
			.toInstant()
			.toEpochMilli()
	}
}
