package com.adsamcik.tracker.game.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
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
) : ViewModel() {

	data class ExplorationState(
		val totalCells: Int = 0,
		val dailyStreak: Int = 0,
		val bestStreak: Int = 0,
		val seasonsCovered: Int = 0,
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

	val explorationState: StateFlow<ExplorationState> = combine(
		explorationCellDao.countAtLevelFlow(EXPLORATION_LEVEL),
		streakFlow(),
	) { totalCells, streakData ->
		ExplorationState(
			totalCells = totalCells,
			dailyStreak = streakData.currentStreak,
			bestStreak = streakData.bestStreak,
			seasonsCovered = streakData.seasonsCovered,
			recentDiscoveries = streakData.recentCells,
		)
	}.stateIn(viewModelScope, SharingStarted.Lazily, ExplorationState())

	val achievementState: StateFlow<AchievementSummaryState> =
		achievementProgressDao.getAllFlow()
			.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
			.let { progressFlow ->
				flow {
					progressFlow.collect { allProgress ->
						val unlocked = allProgress.filter { it.unlockedAt != null }
						val bronzeCount = unlocked.count { it.tier == TIER_BRONZE }
						val silverCount = unlocked.count { it.tier == TIER_SILVER }
						val goldCount = unlocked.count { it.tier == TIER_GOLD }
						val diamondCount = unlocked.count { it.tier == TIER_DIAMOND }

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

						emit(
							AchievementSummaryState(
								bronzeCount = bronzeCount,
								silverCount = silverCount,
								goldCount = goldCount,
								diamondCount = diamondCount,
								totalUnlocked = unlocked.size,
								nextClosest = nextClosest,
							)
						)
					}
				}
			}
			.stateIn(viewModelScope, SharingStarted.Lazily, AchievementSummaryState())

	/** Intermediate data holder for streak + season + recent cell loading. */
	private data class StreakData(
		val currentStreak: Int = 0,
		val bestStreak: Int = 0,
		val seasonsCovered: Int = 0,
		val recentCells: List<RecentCell> = emptyList(),
	)

	/**
	 * Loads streak info, season bitmask, and recent discoveries as a single flow.
	 * These are suspend DAO calls, so they are wrapped in a flow.
	 */
	private fun streakFlow() = flow {
		val streak = explorationStreakDao.getByType(STREAK_TYPE_DAILY)
		val seasonBitmasks = explorationCellDao.getDistinctSeasonBitmasks(EXPLORATION_LEVEL)
		val combinedBitmask = seasonBitmasks.fold(0) { acc, mask -> acc or mask }
		val seasonCount = Integer.bitCount(combinedBitmask)
		val recentEntities = explorationCellDao.getRecentAtLevel(EXPLORATION_LEVEL, RECENT_LIMIT)

		emit(
			StreakData(
				currentStreak = streak?.currentCount ?: 0,
				bestStreak = streak?.bestCount ?: 0,
				seasonsCovered = seasonCount,
				recentCells = recentEntities.map { entity ->
					RecentCell(
						token = entity.cellToken,
						quality = entity.quality,
						discoveredAt = entity.firstDiscoveredAt,
					)
				},
			)
		)
	}

	companion object {
		/** S2 cell level used for exploration (approx 0.8 km^2 per cell). */
		private const val EXPLORATION_LEVEL = 14
		private const val RECENT_LIMIT = 5
		private const val STREAK_TYPE_DAILY = "DAILY_DISCOVERY"

		// Achievement tier constants matching AchievementProgressEntity.tier values
		private const val TIER_BRONZE = 0
		private const val TIER_SILVER = 1
		private const val TIER_GOLD = 2
		private const val TIER_DIAMOND = 3
	}
}
