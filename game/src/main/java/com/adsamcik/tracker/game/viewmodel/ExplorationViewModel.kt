package com.adsamcik.tracker.game.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
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
		val totalUnlocked: Int = 0,
		val nextClosest: NextAchievement? = null,
	)

	data class NextAchievement(
		val id: String,
		val progress: Float,
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
			.map { allProgress ->
				val unlocked = allProgress.filter { it.unlockedAt != null }
				val nextClosest = allProgress
					.filter { it.unlockedAt == null && it.targetValue > 0 }
					.maxByOrNull { it.currentValue.toFloat() / it.targetValue }
					?.let { entity ->
						NextAchievement(
							id = entity.achievementId,
							progress = (entity.currentValue.toFloat() / entity.targetValue)
								.coerceIn(0f, 1f),
						)
					}
				AchievementSummaryState(
					bronzeCount = unlocked.count { it.tier == TIER_BRONZE },
					silverCount = unlocked.count { it.tier == TIER_SILVER },
					goldCount = unlocked.count { it.tier == TIER_GOLD },
					diamondCount = unlocked.count { it.tier == TIER_DIAMOND },
					totalUnlocked = unlocked.size,
					nextClosest = nextClosest,
				)
			}
			.map { it as AchievementSummaryState? }
			.catch { emit(null) }
			.flowOn(dispatchers.io)
			.stateIn(
				scope = viewModelScope,
				started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
				initialValue = null,
			)

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

		// Achievement tier constants matching AchievementProgressEntity.tier values
		private const val TIER_BRONZE = 0
		private const val TIER_SILVER = 1
		private const val TIER_GOLD = 2
		private const val TIER_DIAMOND = 3
	}
}
