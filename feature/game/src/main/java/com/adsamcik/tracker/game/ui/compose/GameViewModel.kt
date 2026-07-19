package com.adsamcik.tracker.game.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.feature.game.api.ranking.WeeklyRankingResult
import com.adsamcik.tracker.feature.game.api.ranking.WeeklyRankingService
import com.adsamcik.tracker.game.minigame.MiniGameRegistry
import com.adsamcik.tracker.game.repository.GameRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal data class MiniGameEntry(
	val id: String,
	val nameRes: Int,
	val descriptionRes: Int,
	val unlockLevel: Int,
	val isUnlocked: Boolean,
	val isAvailable: Boolean = false,
)

@HiltViewModel
internal class GameViewModel @Inject constructor(
	private val gameRepository: GameRepository,
	private val miniGameRegistry: MiniGameRegistry,
	weeklyRankingService: WeeklyRankingService,
) : ViewModel() {
	val pointsToday: StateFlow<Int?> = gameRepository.getPointsToday()
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS), null)

	val stepsSummary: StateFlow<StepsSummaryUi?> = gameRepository.getStepsSummary()
		.map { it?.let { data -> StepsSummaryUi(data.stepsToday, data.stepsWeek, data.goalDay, data.goalWeek) } }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS), null)

	val miniGameEntries: StateFlow<List<MiniGameEntry>?> = gameRepository.getPlayerProfile()
		.map { profile ->
			val playerLevel = profile?.level ?: 1
			miniGameRegistry.allSorted().map { game ->
				MiniGameEntry(
					id = game.id,
					nameRes = game.nameRes,
					descriptionRes = game.descriptionRes,
					unlockLevel = game.unlockLevel,
					isUnlocked = playerLevel >= game.unlockLevel,
					// Engines, score persistence and the session route are wired
					// for every registered game — the unlock gate is the only
					// thing standing between the user and play.
					isAvailable = true,
				)
			}
		}
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS), null)

	val weeklyRanking: StateFlow<WeeklyRankingResult?> = weeklyRankingService.observeCurrentWeek()
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS), null)

	private companion object { const val STATE_STOP_TIMEOUT_MS = 5_000L }
}
