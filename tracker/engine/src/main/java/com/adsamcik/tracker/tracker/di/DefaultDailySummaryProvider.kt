package com.adsamcik.tracker.tracker.di

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.LiveStatsDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
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
 * 2. Falls back to [TripDao.getTodaySummary] if no materialized data exists
 * 3. [observeTodayLive] provides reactive Flow from [LiveStatsDao] for real-time dashboard
 *
 * Lifecycle: Application-scoped singleton (wired in AppGraph)
 */
class DefaultDailySummaryProvider(
	private val tripDao: TripDao,
	private val dailySummaryDao: DailySummaryDao,
	private val liveStatsDao: LiveStatsDao,
	private val ioDispatcher: CoroutineDispatcher
) : DailySummaryProvider {

	override suspend fun fetchTodaySummary(): DailySummary? = withContext(ioDispatcher) {
		val now = Time.nowMillis
		val startOfDay = Time.todayMillis
		val epochDay = startOfDay / Time.DAY_IN_MILLISECONDS

		// Try materialized data first (fast single-row reads)
		val liveStats = liveStatsDao.get()
		if (
			liveStats != null &&
			liveStats.dateEpochDay == epochDay &&
			hasNonStepSummaryEvidence(
				distanceM = liveStats.dayTotalDistanceM,
				durationMs = liveStats.dayTotalDurationMs,
				sessionCount = 0,
			)
		) {
			return@withContext DailySummary(
				totalDistanceM = liveStats.dayTotalDistanceM,
				totalSteps = liveStats.dayTotalSteps,
				totalDurationMs = liveStats.dayTotalDurationMs,
				sessionCount = 1 // At least one active session
			)
		}

		// Try daily summary (covers finished sessions)
		val dailySummary = dailySummaryDao.getByDay(epochDay)
		if (
			dailySummary != null &&
			hasNonStepSummaryEvidence(
				distanceM = dailySummary.totalDistanceM,
				durationMs = dailySummary.totalDurationMs,
				sessionCount = dailySummary.tripCount,
			)
		) {
			return@withContext DailySummary(
				totalDistanceM = dailySummary.totalDistanceM,
				totalSteps = dailySummary.totalSteps,
				totalDurationMs = dailySummary.totalDurationMs,
				sessionCount = dailySummary.tripCount
			)
		}

		// Fallback to trip-based query
		val summary = tripDao.getTodaySummary(startOfDay, now)

		if (summary == null || summary.tripCount == 0) {
			null
		} else {
			DailySummary(
				totalDistanceM = summary.totalDistanceM,
				totalSteps = summary.totalSteps,
				totalDurationMs = summary.totalDurationMs,
				sessionCount = summary.tripCount
			)
		}
	}

	override fun observeTodayLive(): Flow<DailySummary?> {
		return liveStatsDao.getFlow().map { liveStats ->
			val epochDay = Time.todayMillis / Time.DAY_IN_MILLISECONDS
			if (
				liveStats != null &&
				liveStats.dateEpochDay == epochDay &&
				hasNonStepSummaryEvidence(
					distanceM = liveStats.dayTotalDistanceM,
					durationMs = liveStats.dayTotalDurationMs,
					sessionCount = 0,
				)
			) {
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

/** Raw aggregate Steps are not evidence that a product-visible session or day exists. */
internal fun hasNonStepSummaryEvidence(
	distanceM: Float,
	durationMs: Long,
	sessionCount: Int,
): Boolean = distanceM > 0f || durationMs > 0L || sessionCount > 0
