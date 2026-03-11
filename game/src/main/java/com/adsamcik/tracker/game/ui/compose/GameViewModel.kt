package com.adsamcik.tracker.game.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.game.minigame.MiniGameRegistry
import com.adsamcik.tracker.game.repository.GameRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class MiniGameEntry(
    val id: String,
    val nameRes: Int,
    val descriptionRes: Int,
    val unlockLevel: Int,
    val isUnlocked: Boolean,
)

/** Provides reactive game state (points today, step goals, active challenges). */
@HiltViewModel
class GameViewModel @Inject constructor(
    private val gameRepository: GameRepository,
    private val miniGameRegistry: MiniGameRegistry,
) : ViewModel() {

    val pointsToday: StateFlow<Int?> = gameRepository.getPointsToday()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
            initialValue = null,
        )

    val stepsSummary: StateFlow<StepsSummaryUi?> = gameRepository.getStepsSummary()
        .map { data -> 
            data?.let {
                StepsSummaryUi(
                    stepsToday = it.stepsToday,
                    stepsWeek = it.stepsWeek,
                    goalDay = it.goalDay,
                    goalWeek = it.goalWeek
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
            initialValue = null,
        )

    val challenges: StateFlow<List<ChallengeUi>?> = gameRepository.getActiveChallenges()
        .map { dataList ->
            dataList.map { data ->
                ChallengeUi(
                    id = data.id,
                    title = data.title,
                    description = data.description,
                    progress = data.progress,
                    difficulty = data.difficulty,
                    timeRemainingMs = data.timeRemainingMs,
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
            initialValue = null,
        )

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
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
            initialValue = null,
        )

    private companion object {
        const val STATE_STOP_TIMEOUT_MS = 5_000L
    }
}
