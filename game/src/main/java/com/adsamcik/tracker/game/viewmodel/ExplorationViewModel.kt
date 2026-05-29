package com.adsamcik.tracker.game.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.metric.MetricKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for exploration and achievement UI state.
 *
 * Inputs: ExplorationCellDao, ExplorationStreakDao, AchievementProgressDao
 * Outputs: StateFlow<ExplorationState>, StateFlow<AchievementSummaryState>
 * Failure modes: Empty state shown when no data exists
 */
@HiltViewModel
class ExplorationViewModel @Inject constructor(
	private val explorationCellDao: ExplorationCellDao,
	private val explorationStreakDao: ExplorationStreakDao,
	private val achievementProgressDao: AchievementProgressDao,
	private val dispatchers: DispatchersProvider,
) : ViewModel() {

	data class ExplorationState(
		val totalCells: Int = 0,
		val dailyStreak: Int = 0,
		val bestStreak: Int = 0,
		val seasonsBitmask: Int = 0,
		val recentDiscoveries: List<RecentCell> = emptyList(),
	)

	data class RecentCell(
		val token: String,
		val quality: Int,
		val discoveredAt: Long,
	)

	data class AchievementSummaryState(
		val bronzeCount: Int = 0,
		val silverCount: Int = 0,
		val goldCount: Int = 0,
		val diamondCount: Int = 0,
		val mythicCount: Int = 0,
		val totalUnlocked: Int = 0,
		val recentUnlocks: List<AchievementListItem> = emptyList(),
		val nextUp: List<NextAchievement> = emptyList(),
	) { val nextClosest: NextAchievement? get() = nextUp.firstOrNull() }

	data class AchievementListItem(
		val id: String,
		val nameRes: String,
		val tier: AchievementTier,
		val unlockedAt: Long,
	)

	data class NextAchievement(
		val id: String,
		val nameRes: String,
		val tier: AchievementTier,
		val progress: Float,
		val currentValue: Double,
		val threshold: Double,
	)

	val explorationState: StateFlow<ExplorationState?> = combine(
		explorationCellDao.countAtLevelFlow(EXPLORATION_LEVEL),
		streakFlow(),
	) { totalCells, streakData ->
		ExplorationState(
			totalCells = totalCells,
			dailyStreak = streakData.currentStreak,
			bestStreak = streakData.bestStreak,
			seasonsBitmask = streakData.seasonsBitmask,
			recentDiscoveries = streakData.recentCells,
		)
	}
		.map { it as ExplorationState? }
		.catch { emit(null) }
		.stateIn(
			scope = viewModelScope,
			started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
			initialValue = null,
		)

	val achievementState: StateFlow<AchievementSummaryState?> =
		achievementProgressDao.getAllFlow()
			.map { progressRows ->
				val progressByMetric = progressRows.mapNotNull { row -> MetricKey.fromStorageKey(row.metricKey)?.let { it to row } }.toMap()
				val unlocked = AchievementCatalog.definitions.filter { definition -> (progressByMetric[definition.metric]?.lastTierIndex ?: -1) >= definition.tierIndex }
				val recentUnlocks = progressRows.asSequence()
					.filter { it.lastTierIndex >= 0 }
					.sortedByDescending { it.updatedAt }
					.mapNotNull { row ->
						val metric = MetricKey.fromStorageKey(row.metricKey) ?: return@mapNotNull null
						val definition = AchievementCatalog.byMetric(metric).getOrNull(row.lastTierIndex) ?: return@mapNotNull null
						AchievementListItem(definition.id, definition.nameRes, definition.tier, row.updatedAt)
					}
					.take(3)
					.toList()
				val nextUp = AchievementCatalog.definitions.asSequence()
					.filter { definition -> (progressByMetric[definition.metric]?.lastTierIndex ?: -1) < definition.tierIndex }
					.map { definition ->
						val currentValue = progressByMetric[definition.metric]?.lastValue ?: 0.0
						NextAchievement(definition.id, definition.nameRes, definition.tier, if (definition.threshold <= 0.0) 0f else (currentValue / definition.threshold).toFloat().coerceIn(0f, 1f), currentValue, definition.threshold)
					}
					.sortedByDescending { it.progress }
					.take(5)
					.toList()
				AchievementSummaryState(
					bronzeCount = unlocked.count { it.tier == AchievementTier.BRONZE },
					silverCount = unlocked.count { it.tier == AchievementTier.SILVER },
					goldCount = unlocked.count { it.tier == AchievementTier.GOLD },
					diamondCount = unlocked.count { it.tier == AchievementTier.DIAMOND },
					mythicCount = unlocked.count { it.tier == AchievementTier.MYTHIC },
					totalUnlocked = unlocked.size,
					recentUnlocks = recentUnlocks,
					nextUp = nextUp,
				)
			}
			.map { it as AchievementSummaryState? }
			.catch { emit(null) }
			.flowOn(dispatchers.io)
			.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS), null)

	/** Intermediate data holder for streak + season + recent cell loading. */
	private data class StreakData(
		val currentStreak: Int = 0,
		val bestStreak: Int = 0,
		val seasonsBitmask: Int = 0,
		val recentCells: List<RecentCell> = emptyList(),
	)

	/**
	 * Reactive flow that re-emits when the streak row changes.
	 * Season + recent cell data is loaded alongside each streak update.
	 */
	private fun streakFlow() = combine(
		explorationStreakDao.getByTypeFlow(STREAK_TYPE_DAILY),
		explorationCellDao.getDistinctSeasonBitmasksFlow(EXPLORATION_LEVEL),
		explorationCellDao.getRecentAtLevelFlow(EXPLORATION_LEVEL, RECENT_LIMIT),
	) { streak, seasonBitmasks, recentEntities ->
		val combinedBitmask = seasonBitmasks.fold(0) { acc, mask -> acc or mask }
		StreakData(
			currentStreak = streak?.currentCount ?: 0,
			bestStreak = streak?.bestCount ?: 0,
			seasonsBitmask = combinedBitmask,
			recentCells = recentEntities.map { entity ->
				RecentCell(
					token = entity.cellToken,
					quality = entity.quality,
					discoveredAt = entity.firstDiscoveredAt,
				)
			},
		)
	}.flowOn(dispatchers.io)

	companion object {
		/** S2 cell level used for exploration (approx 0.8 km^2 per cell). */
		private const val EXPLORATION_LEVEL = 14
		private const val RECENT_LIMIT = 5
		private const val STATE_STOP_TIMEOUT_MS = 5_000L
		private const val STREAK_TYPE_DAILY = "DAILY_DISCOVERY"
	}
}
