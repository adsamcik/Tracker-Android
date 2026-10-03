package com.adsamcik.tracker.stats.data.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.ExportLogDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.metric.TimeWindow
import com.adsamcik.tracker.stats.api.repository.WindowedMetricsProvider
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Collects metric values for cumulative and bounded windows.
 *
 * Bounded discovery streaks use dated, retained discoveries and the current local calendar.
 * Longest trip uses a bounded, read-only saved Location reader. Seasonal visits lack dated evidence.
 * Steps require the separate source-qualified numeric repository.
 */
class DefaultWindowedMetricsProvider @Inject constructor(
	private val dailySummaryDao: DailySummaryDao,
	private val explorationCellDao: ExplorationCellDao,
	private val explorationStreakDao: ExplorationStreakDao,
	private val sessionSegmentDao: SessionSegmentDao,
	private val exportLogDao: ExportLogDao,
	private val dispatchers: DispatchersProvider,
	private val savedTripDistanceReader: SavedTripDistanceReader,
) : WindowedMetricsProvider {

	override suspend fun collect(metric: String, window: TimeWindow): Either<StatsError, Long> {
		if (metric == MetricKeys.STEPS || metric == MetricKeys.STEPS_TOTAL || metric == MetricKeys.BEST_DAILY_STEPS) {
			return StatsError.ValidationError("Steps require source-qualified numeric evidence").left()
		}
		if (metric == MetricKeys.LONGEST_TRIP_KM) {
			val bounds = when (window) {
				TimeWindow.Cumulative -> 0L to Long.MAX_VALUE
				is TimeWindow.Interval -> window.startMs to window.endMs
				is TimeWindow.Rolling -> {
					if (window.durationMs < 0L) return StatsError.ValidationError("Rolling duration cannot be negative").left()
					val now = System.currentTimeMillis()
					(now - window.durationMs).coerceAtLeast(0L) to now
				}
			}
			return savedTripDistanceReader.longestDistanceMeters(bounds.first, bounds.second).map { it / METERS_PER_KM }
		}
		if (metric == MetricKeys.SEASONS_EXPLORED && window != TimeWindow.Cumulative) {
			return StatsError.ValidationError("Seasonal visits are unavailable for this period").left()
		}
		val value = when (window) {
			TimeWindow.Cumulative -> collectCumulative(metric)
			is TimeWindow.Interval -> {
				if (window.startMs > window.endMs) {
					return StatsError.ValidationError("Metric interval cannot end before it starts").left()
				}
				if (metric.isDiscoveryStreak()) {
					return collectDiscoveryStreak(metric, window.startMs, window.endMs)
				}
				collectInterval(metric, window.startMs, window.endMs)
			}
			is TimeWindow.Rolling -> {
				if (window.durationMs < 0L) {
					return StatsError.ValidationError("Rolling duration cannot be negative").left()
				}
				val nowMs = System.currentTimeMillis()
				if (metric.isDiscoveryStreak()) {
					return collectDiscoveryStreak(metric, nowMs - window.durationMs, nowMs)
				}
				collectInterval(metric, nowMs - window.durationMs, nowMs)
			}
		}
		return value?.right()
			?: StatsError.ValidationError("Metric is not supported by this provider").left()
	}

	private suspend fun collectCumulative(metric: String): Long? = when (metric) {
		MetricKeys.DISTANCE_M -> dailySummaryDao.sumTotalDistance()
		MetricKeys.DISTANCE_TOTAL_M -> dailySummaryDao.sumTotalDistance()
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
		MetricKeys.SESSIONS_TOTAL -> dailySummaryDao.sumTotalTrips()
		MetricKeys.ACTIVITY_TYPES_USED -> sessionSegmentDao.countDistinctActivities()
		MetricKeys.SEASONS_EXPLORED -> countSeasonsExplored()
		MetricKeys.DAILY_STREAK -> explorationStreakDao.getByType(DAILY_STREAK_TYPE)?.bestCount?.toLong() ?: 0L
		MetricKeys.WEEKLY_STREAK -> explorationStreakDao.getByType(WEEKLY_STREAK_TYPE)?.bestCount?.toLong() ?: 0L
		MetricKeys.TOTAL_EXPORTS -> exportLogDao.countTotal()
		MetricKeys.ACTIVE_DAYS_TOTAL -> dailySummaryDao.countActiveDays(MetricKeys.MIN_DAILY_TRIPS)
		else -> null
	}

	private suspend fun collectInterval(metric: String, fromMs: Long, toMs: Long): Long? {

		// Daily-summary queries use the epoch_day primary index, NOT the wall-clock ms.
		// Compute the day bounds once so each metric branch hits the index directly.
		val fromDay = com.adsamcik.tracker.shared.base.database.dao.fromMsToFromDay(fromMs)
		val toDay = com.adsamcik.tracker.shared.base.database.dao.toMsToToDay(toMs)

		return when (metric) {
			MetricKeys.DISTANCE_M -> dailySummaryDao.sumTotalDistanceBetween(fromDay, toDay)
			MetricKeys.DISTANCE_TOTAL_M -> dailySummaryDao.sumTotalDistanceBetween(fromDay, toDay)
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
			MetricKeys.SESSIONS_TOTAL -> dailySummaryDao.sumTripsBetween(fromDay, toDay)
			MetricKeys.ACTIVE_DAYS_TOTAL -> dailySummaryDao.countActiveDaysBetween(fromDay, toDay, MetricKeys.MIN_DAILY_TRIPS)

			MetricKeys.ACTIVITY_TYPES_USED -> sessionSegmentDao.countDistinctActivitiesInClosedInterval(fromMs, toMs)
			MetricKeys.TOTAL_EXPORTS -> exportLogDao.countCompletedBetween(fromMs, toMs)

			else -> null
		}
	}

	private suspend fun collectDiscoveryStreak(
		metric: String,
		fromMs: Long,
		toMs: Long,
	): Either<StatsError, Long> {
		// Capture the displayed calendar before suspension; a settings change cannot split one read.
		val zone = ZoneId.systemDefault()
		val weekFields = if (metric == MetricKeys.WEEKLY_STREAK) WeekFields.of(Locale.getDefault()) else null
		return withContext(dispatchers.default) {
			try {
				val timestamps = explorationCellDao.discoveryTimestampsBetween(
					fromMs, toMs, EXPLORATION_CELL_LEVEL, MAX_STREAK_DISCOVERIES + 1,
				)
				currentCoroutineContext().ensureActive()
				if (timestamps.size > MAX_STREAK_DISCOVERIES) {
					return@withContext StatsError.ValidationError("Discovery streak read budget exceeded").left()
				}
				val value = longestDiscoveryPeriodStreak(timestamps, zone, weekFields)
				currentCoroutineContext().ensureActive()
				value.right()
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				StatsError.DatabaseError("Failed to load discovery streak", error).left()
			}
		}
	}

	private fun String.isDiscoveryStreak(): Boolean =
		this == MetricKeys.DAILY_STREAK || this == MetricKeys.WEEKLY_STREAK

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
		internal const val MAX_STREAK_DISCOVERIES = 65_536

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
