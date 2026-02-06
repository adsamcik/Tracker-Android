package com.adsamcik.tracker.tracker.di

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.LiveStatsDao
import com.adsamcik.tracker.shared.base.database.dao.SessionDataDao
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.DailySummaryProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Default implementation of DailySummaryProvider.
 *
 * Evolution strategy:
 * 1. [fetchTodaySummary] reads from materialized [DailySummaryDao] + [LiveStatsDao] first
 * 2. Falls back to legacy [SessionDataDao.getTodaySummary] if no materialized data exists
 * 3. [observeTodayLive] provides reactive Flow from [LiveStatsDao] for real-time dashboard
 *
 * Lifecycle: Application-scoped singleton (wired in AppGraph)
 */
class DefaultDailySummaryProvider(
	private val sessionDao: SessionDataDao,
	private val dailySummaryDao: DailySummaryDao,
	private val liveStatsDao: LiveStatsDao,
	private val ioDispatcher: CoroutineDispatcher
) : DailySummaryProvider {

	override suspend fun fetchTodaySummary(): DailySummary? = withContext(ioDispatcher) {
		val now = Time.nowMillis
		val epochDay = now / Time.DAY_IN_MILLISECONDS

		// Try materialized data first (fast single-row reads)
		val liveStats = liveStatsDao.get()
		if (liveStats != null && liveStats.dateEpochDay == epochDay) {
			return@withContext DailySummary(
				totalDistanceM = liveStats.dayTotalDistanceM,
				totalSteps = liveStats.dayTotalSteps,
				totalDurationMs = liveStats.dayTotalDurationMs,
				sessionCount = 1 // At least one active session
			)
		}

		// Try daily summary (covers finished sessions)
		val dailySummary = dailySummaryDao.getByDay(epochDay)
		if (dailySummary != null && (dailySummary.totalDistanceM > 0f || dailySummary.totalSteps > 0)) {
			return@withContext DailySummary(
				totalDistanceM = dailySummary.totalDistanceM,
				totalSteps = dailySummary.totalSteps,
				totalDurationMs = dailySummary.totalDurationMs,
				sessionCount = dailySummary.tripCount.coerceAtLeast(1)
			)
		}

		// Fallback to legacy session-based query
		val startOfDay = epochDay * Time.DAY_IN_MILLISECONDS
		val summary = sessionDao.getTodaySummary(startOfDay, now)

		if (summary == null || summary.sessionCount == 0) {
			null
		} else {
			DailySummary(
				totalDistanceM = summary.distanceInM,
				totalSteps = summary.steps,
				totalDurationMs = summary.duration,
				sessionCount = summary.sessionCount
			)
		}
	}

	override fun observeTodayLive(): Flow<DailySummary?> {
		return liveStatsDao.getFlow().map { liveStats ->
			val epochDay = Time.nowMillis / Time.DAY_IN_MILLISECONDS
			if (liveStats != null && liveStats.dateEpochDay == epochDay) {
				DailySummary(
					totalDistanceM = liveStats.dayTotalDistanceM,
					totalSteps = liveStats.dayTotalSteps,
					totalDurationMs = liveStats.dayTotalDurationMs,
					sessionCount = 1
				)
			} else {
				null
			}
		}
	}
}
