package com.adsamcik.tracker.shared.base.database.aggregator

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao

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
 */
class DailySummaryAggregator(
	private val dailySummaryDao: DailySummaryDao,
	private val sessionSegmentDao: SessionSegmentDao,
) {
	/**
	 * Materialize daily summary for a specific epoch day by aggregating
	 * all session segments that fall within that calendar day.
	 *
	 * @param epochDay the day identifier computed as `localMidnightMs / DAY_IN_MILLISECONDS`
	 */
	suspend fun materializeDayFromSegments(epochDay: Long) {
		val startOfDayMs = epochDay * Time.DAY_IN_MILLISECONDS
		val endOfDayMs = startOfDayMs + Time.DAY_IN_MILLISECONDS

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
		}
	}

	/**
	 * Materialize today's daily summary from session segments.
	 */
	suspend fun materializeToday() {
		val todayEpochDay = Time.todayMillis / Time.DAY_IN_MILLISECONDS
		materializeDayFromSegments(todayEpochDay)
	}
}
