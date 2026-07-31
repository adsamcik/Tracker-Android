package com.adsamcik.tracker.game.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.game.data.ExplorationProgressRepository
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for exploration and achievement UI state.
 *
 * Input: [ExplorationProgressRepository]
 * Outputs: StateFlow<ExplorationState>, StateFlow<AchievementSummaryState>
 * Failure modes: Empty state shown when no data exists
 */
@HiltViewModel
class ExplorationViewModel @Inject constructor(
	private val progressRepository: ExplorationProgressRepository,
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

	val explorationState: StateFlow<ExplorationState?> = progressRepository.exploration
		.map { progress ->
			ExplorationState(
				totalCells = progress.totalCells,
				dailyStreak = progress.dailyStreak,
				bestStreak = progress.bestStreak,
				seasonsBitmask = progress.seasonsBitmask,
				recentDiscoveries = progress.recentDiscoveries.map {
					RecentCell(
						token = it.token,
						quality = it.quality,
						discoveredAt = it.discoveredAt,
					)
				},
			)
		}
		.map { it as ExplorationState? }
		.catch { emit(null) }
		.flowOn(dispatchers.io)
		.stateIn(
			scope = viewModelScope,
			started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
			initialValue = null,
		)

	val achievementState: StateFlow<AchievementSummaryState?> =
		progressRepository.achievements
			.map { progressRows ->
				val progressByMetric = progressRows.associateBy { it.metric }
				val unlocked = AchievementCatalog.definitions.filter { definition -> (progressByMetric[definition.metric]?.lastTierIndex ?: -1) >= definition.tierIndex }
				val recentUnlocks = progressRows.asSequence()
					.filter { it.lastTierIndex >= 0 }
					.sortedByDescending { it.updatedAt }
					.mapNotNull { row ->
						val definition = AchievementCatalog.byMetric(row.metric).getOrNull(row.lastTierIndex)
							?: return@mapNotNull null
						AchievementListItem(definition.id, definition.nameRes, definition.tier, row.updatedAt)
					}
					.take(3)
					.toList()
				// Pick the user's NEXT tier per metric series (lowest tierIndex not yet
				// unlocked), then surface the top 5 closest-to-completion across DIFFERENT
				// series. Previously this filtered all unfinished definitions and took 5
				// by progress descending, which collapsed to "5 tiers of the same metric"
				// for new users with no progress (e.g. Total Distance 1k / 5k / 10k / 50k
				// / 100k) — looked like the system was repeating itself. One-per-series
				// reads like a real to-do list of variety: distance + steps + cells + ….
				val nextUp = AchievementCatalog.definitions.asSequence()
					.filter { definition -> (progressByMetric[definition.metric]?.lastTierIndex ?: -1) < definition.tierIndex }
					.groupBy { it.metric }
					.values
					.mapNotNull { tiersForMetric -> tiersForMetric.minByOrNull { it.tierIndex } }
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

	companion object {
		private const val STATE_STOP_TIMEOUT_MS = 5_000L
	}
}
