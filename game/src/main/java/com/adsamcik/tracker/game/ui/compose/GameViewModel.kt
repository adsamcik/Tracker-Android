package com.adsamcik.tracker.game.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.game.repository.GameRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Provides reactive game state (points today, step goals, active challenges). */
@HiltViewModel
class GameViewModel @Inject constructor(
    private val gameRepository: GameRepository
) : ViewModel() {

    val pointsToday = gameRepository.getPointsToday()
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val stepsSummary = gameRepository.getStepsSummary()
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
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val challenges = gameRepository.getActiveChallenges()
        .map { dataList ->
            dataList.map { data ->
                ChallengeUi(
                    id = data.id,
                    title = data.title,
                    description = data.description,
                    progress = data.progress
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}
