package com.adsamcik.tracker.dashboard.data

import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.metric.MetricKey
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

data class DashboardHistory(
	val lastSession: Trip?,
	val recentTrips: List<Trip>,
	val exploration: DashboardHistorySection<DashboardExplorationHistory?>,
	val streak: DashboardHistorySection<DashboardStreakHistory>,
	val latestAchievement: DashboardHistorySection<DashboardAchievementHistory?>,
)

sealed interface DashboardHistorySection<out T> {
	data class Loaded<T>(val value: T) : DashboardHistorySection<T>
	data object Failed : DashboardHistorySection<Nothing>
}

data class DashboardExplorationHistory(
	val totalCells: Int,
	val newCellsToday: Int,
	val seasonsCovered: Int,
)

data class DashboardStreakHistory(
	val currentStreak: Int,
	val bestStreak: Int,
	val weeklyDistances: List<Float>,
	val weeklyTrend: DashboardWeeklyTrend,
)

enum class DashboardWeeklyTrend {
	UP,
	DOWN,
	STEADY,
}

data class DashboardAchievementHistory(
	val id: String,
	val nameRes: String,
	val tier: AchievementTier,
	val unlockedAt: Long,
)

/**
 * Feature-facing boundary for the dashboard's historical cards.
 */
interface DashboardHistoryRepository {
	suspend fun load(includeLastSession: Boolean): DashboardHistory
}

@Singleton
internal class RoomDashboardHistoryRepository @Inject constructor(
	private val tripDao: TripDao,
	private val explorationCellDao: ExplorationCellDao,
	private val explorationStreakDao: ExplorationStreakDao,
	private val dailySummaryDao: DailySummaryDao,
	private val achievementProgressDao: AchievementProgressDao,
	private val dispatchers: DispatchersProvider,
) : DashboardHistoryRepository {
	override suspend fun load(includeLastSession: Boolean): DashboardHistory =
		withContext(dispatchers.io) {
			val recentTrips = tripDao.getRecentTrips(RECENT_TRIP_LIMIT).map { it.toModel() }
			DashboardHistory(
				lastSession = if (includeLastSession) recentTrips.firstOrNull() else null,
				recentTrips = recentTrips,
				exploration = loadSection(::loadExploration),
				streak = loadSection(::loadStreak),
				latestAchievement = loadSection(::loadLatestAchievement),
			)
		}

	/**
	 * Optional dashboard cards must not prevent the recent-trip cards from rendering.
	 * Keep failure distinct from a successful empty result so the ViewModel can preserve
	 * previously rendered data while preserving structured cancellation.
	 */
	private suspend fun <T> loadSection(
		load: suspend () -> T,
	): DashboardHistorySection<T> =
		try {
			DashboardHistorySection.Loaded(load())
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			DashboardHistorySection.Failed
		}

	private suspend fun loadExploration(): DashboardExplorationHistory? {
		val totalCells = explorationCellDao.countAtLevel(EXPLORATION_LEVEL)
		if (totalCells <= 0) return null

		val newToday = explorationCellDao.countDiscoveredSince(
			sinceMs = startOfTodayMs(),
			level = EXPLORATION_LEVEL,
		)
		val combinedBitmask = explorationCellDao.getDistinctSeasonBitmasks(EXPLORATION_LEVEL)
			.fold(0) { accumulated, bitmask -> accumulated or bitmask }
		return DashboardExplorationHistory(
			totalCells = totalCells,
			newCellsToday = newToday,
			seasonsCovered = Integer.bitCount(combinedBitmask),
		)
	}

	private suspend fun loadStreak(): DashboardStreakHistory {
		val streak = explorationStreakDao.getByType(DOMAIN_DAILY_DISCOVERY)
		val todayEpochDay = startOfTodayMs() / MILLIS_PER_DAY
		val startEpochDay = todayEpochDay - (DASHBOARD_DAY_COUNT - 1)
		val summariesByDay = dailySummaryDao
			.getBetween(startEpochDay, todayEpochDay)
			.associateBy(DailySummaryEntity::dateEpochDay)
		val weeklyDistances = (startEpochDay..todayEpochDay).map { epochDay ->
			summariesByDay[epochDay]?.totalDistanceM ?: 0f
		}
		val recentAverage = weeklyDistances.takeLast(RECENT_TREND_DAY_COUNT).average().toFloat()
		val olderAverage = weeklyDistances
			.dropLast(RECENT_TREND_DAY_COUNT)
			.average()
			.toFloat()
		val trend = when {
			olderAverage <= 0f ->
				if (recentAverage > 0f) DashboardWeeklyTrend.UP else DashboardWeeklyTrend.STEADY
			recentAverage > olderAverage * TREND_THRESHOLD -> DashboardWeeklyTrend.UP
			recentAverage < olderAverage * DOWN_TREND_THRESHOLD -> DashboardWeeklyTrend.DOWN
			else -> DashboardWeeklyTrend.STEADY
		}
		val currentStreak = if (streak != null && streak.lastIncrementDay > 0) {
			if (todayEpochDay - streak.lastIncrementDay > 1) 0 else streak.currentCount
		} else {
			streak?.currentCount ?: 0
		}
		return DashboardStreakHistory(
			currentStreak = currentStreak,
			bestStreak = streak?.bestCount ?: 0,
			weeklyDistances = weeklyDistances,
			weeklyTrend = trend,
		)
	}

	private suspend fun loadLatestAchievement(): DashboardAchievementHistory? {
		val row = achievementProgressDao.getAll().firstOrNull { it.lastTierIndex >= 0 }
			?: return null
		val metric = MetricKey.fromStorageKey(row.metricKey) ?: return null
		val definition = AchievementCatalog.byMetric(metric).getOrNull(row.lastTierIndex)
			?: return null
		return DashboardAchievementHistory(
			id = definition.id,
			nameRes = definition.nameRes,
			tier = definition.tier,
			unlockedAt = row.updatedAt,
		)
	}

	private fun startOfTodayMs(): Long = Calendar.getInstance().apply {
		set(Calendar.HOUR_OF_DAY, 0)
		set(Calendar.MINUTE, 0)
		set(Calendar.SECOND, 0)
		set(Calendar.MILLISECOND, 0)
	}.timeInMillis

	private companion object {
		const val DOMAIN_DAILY_DISCOVERY = "DAILY_DISCOVERY"
		const val EXPLORATION_LEVEL = 14
		const val RECENT_TRIP_LIMIT = 5
		const val DASHBOARD_DAY_COUNT = 7
		const val RECENT_TREND_DAY_COUNT = 3
		const val MILLIS_PER_DAY = 86_400_000L
		const val TREND_THRESHOLD = 1.1f
		const val DOWN_TREND_THRESHOLD = 0.9f
	}
}
