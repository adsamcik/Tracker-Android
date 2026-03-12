package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.ExportLogDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.stats.api.repository.AchievementMetricsProvider
import javax.inject.Inject

/**
 * Collects achievement metrics from pre-aggregated tables.
 *
 * NEVER scans raw location_sample. All queries hit indexed, pre-aggregated tables
 * (daily_summary, exploration_cell, exploration_streak, session_segment, export_log).
 *
 * Metric keys match [com.adsamcik.tracker.stats.engine.achievement.AchievementCatalog] definitions.
 */
class DefaultAchievementMetricsProvider @Inject constructor(
	private val dailySummaryDao: DailySummaryDao,
	private val explorationCellDao: ExplorationCellDao,
	private val explorationStreakDao: ExplorationStreakDao,
	private val sessionSegmentDao: SessionSegmentDao,
	private val exportLogDao: ExportLogDao,
) : AchievementMetricsProvider {

	override suspend fun collect(): Map<String, Long> = buildMap {
		// ── EXPLORATION ─────────────────────────────────────────────
		put("cells_discovered", explorationCellDao.countAtLevelLong(EXPLORATION_CELL_LEVEL))

		// Unique areas = distinct cells at a coarser level (level 10 ≈ ~12km coverage)
		put("unique_areas", explorationCellDao.countAtLevelLong(AREA_CELL_LEVEL))

		// Seasons explored = count of distinct season bits across all cells
		val seasonBitmasks = explorationCellDao.getDistinctSeasonBitmasks(EXPLORATION_CELL_LEVEL)
		val combinedSeasons = seasonBitmasks.fold(0) { acc, mask -> acc or mask }
		put("seasons_explored", Integer.bitCount(combinedSeasons).toLong())

		// ── DISTANCE ────────────────────────────────────────────────
		// SUM(total_distance_m) returns meters; achievement thresholds are in km
		val totalDistanceM = dailySummaryDao.sumTotalDistance()
		put("total_distance_km", totalDistanceM / METERS_PER_KM)

		// Longest single segment distance in km
		val longestTripM = sessionSegmentDao.maxSegmentDistance()
		put("longest_trip_km", longestTripM / METERS_PER_KM)

		// ── STEPS ───────────────────────────────────────────────────
		put("total_steps", dailySummaryDao.sumTotalSteps())
		put("best_daily_steps", dailySummaryDao.maxDailySteps())

		// ── STREAKS ─────────────────────────────────────────────────
		val dailyStreak = explorationStreakDao.getByType(DAILY_STREAK_TYPE)
		put("daily_streak", dailyStreak?.bestCount?.toLong() ?: 0L)

		val weeklyStreak = explorationStreakDao.getByType(WEEKLY_STREAK_TYPE)
		put("weekly_streak", weeklyStreak?.bestCount?.toLong() ?: 0L)

		// ── MODES ───────────────────────────────────────────────────
		put("transport_mode_count", sessionSegmentDao.countDistinctActivities())

		// Walking trips: ON_FOOT(2) + WALKING(7) + RUNNING(8)
		val walkingTrips = sessionSegmentDao.countByActivity(ACTIVITY_ON_FOOT) +
			sessionSegmentDao.countByActivity(ACTIVITY_WALKING) +
			sessionSegmentDao.countByActivity(ACTIVITY_RUNNING)
		put("walking_trips", walkingTrips)

		// Cycling trips: ON_BICYCLE(1)
		put("cycling_trips", sessionSegmentDao.countByActivity(ACTIVITY_ON_BICYCLE))

		// ── MILESTONES ──────────────────────────────────────────────
		put("total_trips", dailySummaryDao.sumTotalTrips())
		put("total_exports", exportLogDao.countTotal())
	}

	companion object {
		private const val EXPLORATION_CELL_LEVEL = 14
		private const val AREA_CELL_LEVEL = 10
		private const val METERS_PER_KM = 1000L

		// Google Play Services DetectedActivity int values
		private const val ACTIVITY_ON_BICYCLE = 1
		private const val ACTIVITY_ON_FOOT = 2
		private const val ACTIVITY_WALKING = 7
		private const val ACTIVITY_RUNNING = 8

		private const val DAILY_STREAK_TYPE = "DAILY_DISCOVERY"
		private const val WEEKLY_STREAK_TYPE = "WEEKLY_EXPLORER"
	}
}
