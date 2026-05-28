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
	 * all session segments that fall within that calendar day.
	 *
	 * @param epochDay the [LocalDate.toEpochDay] identifier for the local calendar day
	 */
	suspend fun materializeDayFromSegments(epochDay: Long) {
		val startOfDayMs = startOfLocalDayMs(epochDay)
		val endOfDayMs = startOfLocalDayMs(epochDay + 1)

		val segments = sessionSegmentDao.getAllBetween(startOfDayMs, endOfDayMs)
		val totalDistanceM = segments.sumOf { it.distanceM.toDouble() }.toFloat()
		val totalSteps = segments.sumOf { it.steps ?: 0 }
		val totalDurationMs = segments.sumOf { it.endTimeMs - it.startTimeMs }
		val tripCount = segments.size

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
