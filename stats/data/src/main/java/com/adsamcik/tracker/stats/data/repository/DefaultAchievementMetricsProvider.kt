package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.ExportLogDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.data.SessionActivityIds
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.api.metric.MetricSnapshot
import com.adsamcik.tracker.stats.api.repository.AchievementMetricsProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

class DefaultAchievementMetricsProvider @Inject constructor(
	private val dailySummaryDao: DailySummaryDao,
	private val explorationCellDao: ExplorationCellDao,
	private val explorationStreakDao: ExplorationStreakDao,
	private val sessionSegmentDao: SessionSegmentDao,
	private val exportLogDao: ExportLogDao,
) : AchievementMetricsProvider {
	override suspend fun collect(): MetricSnapshot {
		val zoneId = ZoneId.systemDefault()
		val today = LocalDate.now(zoneId)
		val weekStartEpochDay = today.minusDays(6).toEpochDay()
		val weekStartMs = today.minusDays(6).atStartOfDay(zoneId).toInstant().toEpochMilli()
		val weekEndMs = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
		val activeDays = dailySummaryDao.getActiveEpochDays()
		val firstActivityMs = listOfNotNull(dailySummaryDao.minCreatedAt(), sessionSegmentDao.minStartTime()).minOrNull()
		val seasonBitmasks = explorationCellDao.getDistinctSeasonBitmasks(EXPLORATION_CELL_LEVEL)
		val combinedSeasons = seasonBitmasks.fold(0) { acc, mask -> acc or mask }
		val hasWalk = sessionSegmentDao.countByActivities(SessionActivityIds.ON_FOOT.toList()) > 0
		val hasCycle = sessionSegmentDao.countByActivities(SessionActivityIds.CYCLING.toList()) > 0
		val hasDrive = sessionSegmentDao.countByActivities(SessionActivityIds.DRIVING.toList()) > 0
		val weekActiveDays = dailySummaryDao.countActiveDaysSinceEpochDay(weekStartEpochDay)

		return MetricSnapshot.from(
			mapOf(
				MetricKey.DISTANCE_TOTAL_M to dailySummaryDao.sumTotalDistance().toDouble(),
				MetricKey.STEPS_TOTAL to dailySummaryDao.sumTotalSteps().toDouble(),
				MetricKey.ACTIVE_DAYS_TOTAL to activeDays.size.toDouble(),
				MetricKey.SESSIONS_TOTAL to dailySummaryDao.sumTotalTrips().toDouble(),
				MetricKey.CELLS_DISTINCT_LIFETIME to explorationCellDao.countAtLevelLong(EXPLORATION_CELL_LEVEL).toDouble(),
				MetricKey.MAX_SESSION_DISTANCE_M to sessionSegmentDao.maxSegmentDistance().toDouble(),
				MetricKey.MAX_SESSION_DURATION_MS to sessionSegmentDao.maxSegmentDurationMs().toDouble(),
				MetricKey.MAX_SPEED_MPS to sessionSegmentDao.maxAverageSpeedMps(),
				MetricKey.STREAK_DAYS_CURRENT to (explorationStreakDao.getByType(DAILY_STREAK_TYPE)?.currentCount ?: 0).toDouble(),
				MetricKey.STREAK_DAYS_MAX to (explorationStreakDao.getByType(DAILY_STREAK_TYPE)?.bestCount ?: 0).toDouble(),
				MetricKey.COUNTRIES_VISITED to 0.0,
				MetricKey.ACTIVITY_TYPES_USED to sessionSegmentDao.countDistinctActivities().toDouble(),
				MetricKey.MONTHS_ACTIVE to dailySummaryDao.countDistinctMonths().toDouble(),
				MetricKey.HOURS_OF_DAY_TRACKED to sessionSegmentDao.countDistinctStartHours().toDouble(),
				MetricKey.DAYS_OF_WEEK_TRACKED to dailySummaryDao.countDistinctWeekdays().toDouble(),
				MetricKey.APP_AGE_DAYS to appAgeDays(firstActivityMs, zoneId).toDouble(),
				MetricKey.COMEBACK_GAP_DAYS to maxActiveDayGap(activeDays).toDouble(),
				MetricKey.CALENDAR_NEW_YEAR to activeDays.hasMonthDay(1, 1).toFlag(),
				MetricKey.CALENDAR_LEAP_DAY to activeDays.hasMonthDay(2, 29).toFlag(),
				MetricKey.CALENDAR_SUMMER_SOLSTICE to activeDays.hasMonthDay(6, 21).toFlag(),
				MetricKey.CALENDAR_WINTER_SOLSTICE to activeDays.hasMonthDay(12, 21).toFlag(),
				MetricKey.WEEK_DISTANCE_M to dailySummaryDao.sumDistanceSinceEpochDay(weekStartEpochDay).toDouble(),
				MetricKey.WEEK_ACTIVE_DAYS to weekActiveDays.toDouble(),
				MetricKey.WEEK_ALL_DAYS_TRACKED to (weekActiveDays >= 7).toFlag(),
				MetricKey.WEEK_ACTIVITY_TYPES to sessionSegmentDao.countDistinctActivitiesBetween(weekStartMs, weekEndMs).toDouble(),
				MetricKey.LIFETIME_WALK_CYCLE_DRIVE to (hasWalk && hasCycle && hasDrive).toFlag(),
				MetricKey.ACTIVE_MINUTES_TOTAL to (dailySummaryDao.sumTotalDurationMs() / MILLIS_PER_MINUTE).toDouble(),
				MetricKey.DISTANCE_ON_FOOT_M to sessionSegmentDao.sumDistanceByActivities(SessionActivityIds.ON_FOOT.toList()).toDouble(),
				MetricKey.CYCLING_DISTANCE_M to sessionSegmentDao.sumDistanceByActivities(SessionActivityIds.CYCLING.toList()).toDouble(),
				MetricKey.VEHICLE_DISTANCE_M to sessionSegmentDao.sumDistanceByActivities(SessionActivityIds.IN_VEHICLE.toList()).toDouble(),
				MetricKey.BEST_DAILY_STEPS to dailySummaryDao.maxDailySteps().toDouble(),
				MetricKey.BEST_DAY_DISTANCE_M to dailySummaryDao.maxDailyDistance().toDouble(),
				MetricKey.EXPORTS_TOTAL to exportLogDao.countTotal().toDouble(),
				MetricKey.SEASONS_EXPLORED to Integer.bitCount(combinedSeasons).toDouble(),
			)
		)
	}

	private fun appAgeDays(firstActivityMs: Long?, zoneId: ZoneId): Long {
		if (firstActivityMs == null || firstActivityMs <= 0L) return 0L
		val first = Instant.ofEpochMilli(firstActivityMs).atZone(zoneId).toLocalDate()
		return ChronoUnit.DAYS.between(first, LocalDate.now(zoneId)).coerceAtLeast(0L)
	}

	private fun maxActiveDayGap(activeDays: List<Long>): Long {
		if (activeDays.size < 2) return 0L
		var previous = activeDays.first()
		var maxGap = 0L
		for (index in 1 until activeDays.size) {
			val current = activeDays[index]
			maxGap = maxOf(maxGap, current - previous - 1)
			previous = current
		}
		return maxGap
	}

	private fun List<Long>.hasMonthDay(month: Int, day: Int): Boolean = any { epochDay ->
		LocalDate.ofEpochDay(epochDay).let { it.monthValue == month && it.dayOfMonth == day }
	}

	private fun Boolean.toFlag(): Double = if (this) 1.0 else 0.0

	companion object {
		private const val EXPLORATION_CELL_LEVEL = 14
		private const val DAILY_STREAK_TYPE = "DAILY_DISCOVERY"
		private const val MILLIS_PER_MINUTE = 60_000L
	}
}
