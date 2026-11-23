package com.adsamcik.tracker.dashboard.data

import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.data.repository.AchievementMetricQualification
import com.adsamcik.tracker.stats.api.repository.ImportedStepsHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.PressureOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageProblem
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageQuery
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailSelection
import java.time.Instant
import java.time.ZoneId
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.base.time.SystemClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext

data class DashboardHistory(
	val exploration: DashboardHistorySection<DashboardExplorationHistory?>,
	val streak: DashboardHistorySection<DashboardStreakHistory>,
	val latestAchievement: DashboardHistorySection<DashboardAchievementHistory?>,
)

/** One row in the Dashboard's bounded recent-history product. */
sealed interface DashboardRecentHistoryEntry {
	/** Existing physical session row with its established detail and map behavior. */
	data class Physical(val trip: Trip) : DashboardRecentHistoryEntry

	/** Steps row retaining its exact source-issued detail selection when available. */
	data class StepsOnly(
		val history: StepsOnlyHistoryEntry,
		val detailSelection: SourceHistoryDetailSelection? = null,
	) : DashboardRecentHistoryEntry {
		init {
			require(detailSelection == null ||
				(detailSelection.entry as? SourceAwareHistoryPageEntry.StepsOnly)?.history == history)
		}
	}

	/** Retained imported Steps, with exact selectable physical members and no inferred route. */
	data class ImportedSteps(val history: ImportedStepsHistoryEntry) : DashboardRecentHistoryEntry

	/** Pressure row retaining its exact native or imported detail selection when available. */
	data class PressureOnly(
		val history: PressureOnlyHistoryEntry,
		val detailSelection: SourceHistoryDetailSelection? = null,
	) : DashboardRecentHistoryEntry {
		init {
			require(detailSelection == null ||
				(detailSelection.entry as? SourceAwareHistoryPageEntry.PressureOnly)?.history == history)
		}
	}

	/** Activity row with an exact source-issued detail selection when one remains actionable. */
	data class ActivityOnly(
		val history: ActivityHistoryEntry,
		val detailSelection: SourceHistoryDetailSelection?,
	) : DashboardRecentHistoryEntry {
		init {
			require(
				detailSelection == null ||
					(detailSelection.entry as? SourceAwareHistoryPageEntry.ActivityOnly)?.history ==
						history,
			)
		}
	}

	/** Source-issued Wi-Fi detail selection with identity-free retained radio facts. */
	data class WifiOnly(
		val history: WifiHistoryEntry,
		val detailSelection: SourceHistoryDetailSelection,
	) : DashboardRecentHistoryEntry {
		init {
			require(
				(detailSelection.entry as? SourceAwareHistoryPageEntry.WifiOnly)?.history ==
					history,
			)
		}
	}

	/** Source-issued Cell detail selection with identity-free retained radio facts. */
	data class CellOnly(
		val history: CellHistoryEntry,
		val detailSelection: SourceHistoryDetailSelection,
	) : DashboardRecentHistoryEntry {
		init {
			require(
				(detailSelection.entry as? SourceAwareHistoryPageEntry.CellOnly)?.history ==
					history,
			)
		}
	}
}

/** Explicit recent-history state; failures never fall back to raw physical rows. */
sealed interface DashboardRecentHistoryState {
	/** A recent page has not yet resolved or is intentionally gated while tracking. */
	data object Loading : DashboardRecentHistoryState

	/** One successfully coordinated recent page, including an explicitly empty page. */
	data class Content(
		val entries: List<DashboardRecentHistoryEntry>,
		val problems: Set<SourceAwareHistoryPageProblem> = emptySet(),
		/** Saved sessionless observations exist; no session or numeric summary is implied. */
		val hasSavedBackgroundData: Boolean = false,
	) : DashboardRecentHistoryState

	/** The coordinated page failed; raw physical candidates must not replace it. */
	data class Unavailable(
		val reason: SourceAwareHistoryPageUnavailableReason? = null,
		val source: HistorySource? = null,
	) : DashboardRecentHistoryState
}

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
	/** Observe one coordinated, bounded physical/Steps/Activity/Pressure-aware recent page. */
	fun observeRecentHistory(): Flow<List<DashboardRecentHistoryEntry>>

	/** State stays observable while one source is unavailable. */
	fun observeRecentHistoryState(): Flow<DashboardRecentHistoryState> =
		observeRecentHistory().map { DashboardRecentHistoryState.Content(it) }

	/** Load optional historical cards independently from the recent-history product. */
	suspend fun load(): DashboardHistory
}

@Singleton
internal class RoomDashboardHistoryRepository @Inject constructor(
	private val trackingHistoryRepository: TrackingHistoryRepository,
	private val explorationCellDao: ExplorationCellDao,
	private val explorationStreakDao: ExplorationStreakDao,
	private val dailySummaryDao: DailySummaryDao,
	private val achievementProgressDao: AchievementProgressDao,
	private val dispatchers: DispatchersProvider,
	private val clock: Clock = SystemClock,
) : DashboardHistoryRepository {
	override fun observeRecentHistory(): Flow<List<DashboardRecentHistoryEntry>> =
		observeRecentHistoryState().mapNotNull { state ->
			when (state) {
				is DashboardRecentHistoryState.Content -> state.entries
				is DashboardRecentHistoryState.Unavailable -> throw DashboardHistoryPageUnavailable(
					requireNotNull(state.reason), state.source)
				DashboardRecentHistoryState.Loading -> null
			}
		}

	override fun observeRecentHistoryState(): Flow<DashboardRecentHistoryState> =
		trackingHistoryRepository.observeRecentHistoryPage(RECENT_HISTORY_LIMIT).map { query ->
			when (query) {
				is SourceAwareHistoryPageQuery.Unavailable -> DashboardRecentHistoryState.Unavailable(query.reason, query.source)
				is SourceAwareHistoryPageQuery.Content -> DashboardRecentHistoryState.Content(
					query.toDashboardEntries(), query.problems)
			}
		}.flowOn(dispatchers.io)

	override suspend fun load(): DashboardHistory =
		withContext(dispatchers.io) {
			DashboardHistory(
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
		val todayEpochDay = Instant.ofEpochMilli(clock.currentTimeMillis())
			.atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
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
		return achievementProgressDao.getAll().mapNotNull { row ->
			val metric = MetricKey.fromStorageKey(row.metricKey) ?: return@mapNotNull null
			if (!AchievementMetricQualification.hasTrustedUnlockTimestamp(metric, row) ||
				row.lastTierIndex < 0
			) {
				return@mapNotNull null
			}
			val definition = AchievementCatalog.byMetric(metric).getOrNull(row.lastTierIndex)
				?: return@mapNotNull null
			DashboardAchievementHistory(
				id = definition.id,
				nameRes = definition.nameRes,
				tier = definition.tier,
				unlockedAt = requireNotNull(row.lastUnlockedAt),
			)
		}.maxByOrNull(DashboardAchievementHistory::unlockedAt)
	}

	private fun startOfTodayMs(): Long = Instant.ofEpochMilli(clock.currentTimeMillis())
		.atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault())
		.toInstant().toEpochMilli()

	private companion object {
		const val DOMAIN_DAILY_DISCOVERY = "DAILY_DISCOVERY"
		const val EXPLORATION_LEVEL = 14
		const val RECENT_HISTORY_LIMIT = 5
		const val DASHBOARD_DAY_COUNT = 7
		const val RECENT_TREND_DAY_COUNT = 3
		const val TREND_THRESHOLD = 1.1f
		const val DOWN_TREND_THRESHOLD = 0.9f
	}
}

/** Preserve the ordering and measurements already resolved by the shared Stats snapshot. */
private fun SourceAwareHistoryPageQuery.Content.toDashboardEntries(): List<DashboardRecentHistoryEntry> =
	entries.map { entry -> mapDashboardEntry(entry, this) }

private fun mapDashboardEntry(
		entry: SourceAwareHistoryPageEntry,
		content: SourceAwareHistoryPageQuery.Content,
	): DashboardRecentHistoryEntry =
		when (entry) {
			is SourceAwareHistoryPageEntry.Physical -> DashboardRecentHistoryEntry.Physical(
				checkNotNull(content.physicalTrips[entry.segmentId]) {
					"Stats returned a physical row without its saved measurements"
				},
			)
			is SourceAwareHistoryPageEntry.StepsOnly -> DashboardRecentHistoryEntry.StepsOnly(
				history = entry.history,
				detailSelection = SourceHistoryDetailSelection.fromSourceEntry(entry, content.readSnapshot),
			)
			is SourceAwareHistoryPageEntry.ImportedSteps ->
				DashboardRecentHistoryEntry.ImportedSteps(entry.history)
			is SourceAwareHistoryPageEntry.PressureOnly -> DashboardRecentHistoryEntry.PressureOnly(
				history = entry.history,
				detailSelection = SourceHistoryDetailSelection.fromSourceEntry(entry, content.readSnapshot),
			)
			is SourceAwareHistoryPageEntry.ActivityOnly -> DashboardRecentHistoryEntry.ActivityOnly(
				history = entry.history,
				detailSelection = SourceHistoryDetailSelection.fromSourceEntry(
					entry,
					content.readSnapshot,
				),
			)
			is SourceAwareHistoryPageEntry.WifiOnly -> DashboardRecentHistoryEntry.WifiOnly(
				history = entry.history,
				detailSelection = requireNotNull(
					SourceHistoryDetailSelection.fromSourceEntry(
						entry,
						content.readSnapshot,
					),
				),
			)
			is SourceAwareHistoryPageEntry.CellOnly -> DashboardRecentHistoryEntry.CellOnly(
				history = entry.history,
				detailSelection = requireNotNull(
					SourceHistoryDetailSelection.fromSourceEntry(
						entry,
						content.readSnapshot,
					),
				),
			)
		}

internal class DashboardHistoryPageUnavailable(
	val reason: SourceAwareHistoryPageUnavailableReason,
	val source: HistorySource?,
) : IllegalStateException("Stats source-aware page unavailable: $reason")
