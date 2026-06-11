package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.ExportLogDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.metric.TimeWindow
import com.adsamcik.tracker.stats.api.repository.WindowedMetricsProvider
import javax.inject.Inject

/**
 * Collects metric values for cumulative and bounded windows using pre-aggregated tables only.
 *
 * Metrics that do not have a meaningful bounded-window interpretation currently return their
 * cumulative value regardless of [window] (streaks, seasonal coverage, transport mode variety,
 * export totals, longest trip, and best daily steps).
 */
class DefaultWindowedMetricsProvider @Inject constructor(
	private val dailySummaryDao: DailySummaryDao,
	private val explorationCellDao: ExplorationCellDao,
	private val explorationStreakDao: ExplorationStreakDao,
	private val sessionSegmentDao: SessionSegmentDao,
	private val exportLogDao: ExportLogDao,
) : WindowedMetricsProvider {

	override suspend fun collect(metric: String, window: TimeWindow): Long = when (window) {
		TimeWindow.Cumulative -> collectCumulative(metric)
		is TimeWindow.Interval -> collectInterval(metric, window.startMs, window.endMs)
		is TimeWindow.Rolling -> {
			val nowMs = System.currentTimeMillis()
			val fromMs = nowMs - window.durationMs.coerceAtLeast(0L)
			collectInterval(metric, fromMs, nowMs)
		}
	}

	private suspend fun collectCumulative(metric: String): Long = when (metric) {
		MetricKeys.STEPS,
		MetricKeys.TOTAL_STEPS,
		-> dailySummaryDao.sumTotalSteps()

		MetricKeys.DISTANCE_M -> dailySummaryDao.sumTotalDistance()
		MetricKeys.TOTAL_DISTANCE_KM -> dailySummaryDao.sumTotalDistance() / METERS_PER_KM
		MetricKeys.ACTIVE_MINUTES -> dailySummaryDao.sumActiveMinutes()
		MetricKeys.CELLS_DISCOVERED -> explorationCellDao.countAtLevelLong(EXPLORATION_CELL_LEVEL)
		MetricKeys.UNIQUE_AREAS -> explorationCellDao.countAtLevelLong(AREA_CELL_LEVEL)
		MetricKeys.DISTANCE_ON_FOOT_M -> sessionSegmentDao.sumDistanceByActivities(ON_FOOT_ACTIVITY_IDS)
		MetricKeys.WALKING_TRIPS -> sessionSegmentDao.countByActivity(ACTIVITY_ON_FOOT) +
			sessionSegmentDao.countByActivity(ACTIVITY_WALKING) +
			sessionSegmentDao.countByActivity(ACTIVITY_RUNNING) +
			sessionSegmentDao.countByActivity(ACTIVITY_NATIVE_WALKING) +
			sessionSegmentDao.countByActivity(ACTIVITY_NATIVE_RUNNING)
		MetricKeys.CYCLING_TRIPS -> sessionSegmentDao.countByActivity(ACTIVITY_ON_BICYCLE) +
			sessionSegmentDao.countByActivity(ACTIVITY_NATIVE_BICYCLE)
		MetricKeys.BEST_DAILY_STEPS -> dailySummaryDao.maxDailySteps()
		MetricKeys.LONGEST_TRIP_KM -> sessionSegmentDao.maxSegmentDistance() / METERS_PER_KM
		MetricKeys.TOTAL_TRIPS -> dailySummaryDao.sumTotalTrips()
		MetricKeys.TRANSPORT_MODE_COUNT -> sessionSegmentDao.countDistinctActivities()
		MetricKeys.SEASONS_EXPLORED -> countSeasonsExplored()
		MetricKeys.DAILY_STREAK -> explorationStreakDao.getByType(DAILY_STREAK_TYPE)?.bestCount?.toLong() ?: 0L
		MetricKeys.WEEKLY_STREAK -> explorationStreakDao.getByType(WEEKLY_STREAK_TYPE)?.bestCount?.toLong() ?: 0L
		MetricKeys.TOTAL_EXPORTS -> exportLogDao.countTotal()
		MetricKeys.ACTIVE_DAYS -> dailySummaryDao.countActiveDays(MetricKeys.MIN_DAILY_TRIPS)
		else -> 0L
	}

	private suspend fun collectInterval(metric: String, fromMs: Long, toMs: Long): Long {
		if (fromMs > toMs) {
			return 0L
		}

		// Daily-summary queries use the epoch_day primary index, NOT the wall-clock ms.
		// Compute the day bounds once so each metric branch hits the index directly.
		val fromDay = com.adsamcik.tracker.shared.base.database.dao.fromMsToFromDay(fromMs)
		val toDay = com.adsamcik.tracker.shared.base.database.dao.toMsToToDay(toMs)

		return when (metric) {
			MetricKeys.STEPS,
			MetricKeys.TOTAL_STEPS,
			-> dailySummaryDao.sumStepsBetween(fromDay, toDay)

			MetricKeys.DISTANCE_M -> dailySummaryDao.sumTotalDistanceBetween(fromDay, toDay)
			MetricKeys.TOTAL_DISTANCE_KM -> dailySummaryDao.sumTotalDistanceBetween(fromDay, toDay) / METERS_PER_KM
			MetricKeys.ACTIVE_MINUTES -> dailySummaryDao.sumActiveMinutesBetween(fromDay, toDay)
			MetricKeys.CELLS_DISCOVERED -> explorationCellDao.countDiscoveredBetween(fromMs, toMs, EXPLORATION_CELL_LEVEL)
			MetricKeys.UNIQUE_AREAS -> explorationCellDao.countDiscoveredBetween(fromMs, toMs, AREA_CELL_LEVEL)
			MetricKeys.DISTANCE_ON_FOOT_M -> sessionSegmentDao.sumDistanceByActivitiesBetween(
				fromMs = fromMs,
				toMs = toMs,
				activityTypes = ON_FOOT_ACTIVITY_IDS,
			)
			MetricKeys.WALKING_TRIPS -> sessionSegmentDao.countByActivitiesBetween(
				fromMs = fromMs,
				toMs = toMs,
				activityTypes = WALKING_ACTIVITY_IDS,
			)
			MetricKeys.CYCLING_TRIPS -> sessionSegmentDao.countByActivitiesBetween(
				fromMs = fromMs,
				toMs = toMs,
				activityTypes = CYCLING_ACTIVITY_IDS,
			)
			MetricKeys.TOTAL_TRIPS -> dailySummaryDao.sumTripsBetween(fromDay, toDay)
			MetricKeys.ACTIVE_DAYS -> dailySummaryDao.countActiveDaysBetween(fromDay, toDay, MetricKeys.MIN_DAILY_TRIPS)

			MetricKeys.BEST_DAILY_STEPS,
			MetricKeys.LONGEST_TRIP_KM,
			MetricKeys.TRANSPORT_MODE_COUNT,
			MetricKeys.SEASONS_EXPLORED,
			MetricKeys.DAILY_STREAK,
			MetricKeys.WEEKLY_STREAK,
			MetricKeys.TOTAL_EXPORTS,
			-> collectCumulative(metric)

			else -> 0L
		}
	}

	private suspend fun countSeasonsExplored(): Long {
		val seasonBitmasks = explorationCellDao.getDistinctSeasonBitmasks(EXPLORATION_CELL_LEVEL)
		val combinedSeasons = seasonBitmasks.fold(0) { acc, mask -> acc or mask }
		return Integer.bitCount(combinedSeasons).toLong()
	}

	companion object {
		private const val METERS_PER_KM = 1000L
		private const val EXPLORATION_CELL_LEVEL = 14
		private const val AREA_CELL_LEVEL = 10
		private const val DAILY_STREAK_TYPE = "DAILY_DISCOVERY"
		private const val WEEKLY_STREAK_TYPE = "WEEKLY_EXPLORER"

		private const val ACTIVITY_ON_BICYCLE = 1
		private const val ACTIVITY_ON_FOOT = 2
		private const val ACTIVITY_WALKING = 7
		private const val ACTIVITY_RUNNING = 8
		private val ACTIVITY_NATIVE_WALKING = NativeSessionActivity.WALKING.id.toInt()
		private val ACTIVITY_NATIVE_RUNNING = NativeSessionActivity.RUNNING.id.toInt()
		private val ACTIVITY_NATIVE_BICYCLE = NativeSessionActivity.BICYCLE.id.toInt()

		private val ON_FOOT_ACTIVITY_IDS = listOf(
			ACTIVITY_ON_FOOT,
			ACTIVITY_WALKING,
			ACTIVITY_RUNNING,
			ACTIVITY_NATIVE_WALKING,
			ACTIVITY_NATIVE_RUNNING,
		)

		private val WALKING_ACTIVITY_IDS = ON_FOOT_ACTIVITY_IDS
		private val CYCLING_ACTIVITY_IDS = listOf(
			ACTIVITY_ON_BICYCLE,
			ACTIVITY_NATIVE_BICYCLE,
		)
	}
}
