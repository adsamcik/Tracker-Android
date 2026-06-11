package com.adsamcik.tracker.game.di

import android.content.Context
import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.shared.base.di.GoalProgress
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class DefaultGoalProgressProvider(
	@Suppress("UNUSED_PARAMETER") context: Context,
	private val gameRepository: GameRepository,
	scope: CoroutineScope,
) : GoalProgressProvider {
	override val goalProgressFlow: StateFlow<GoalProgress> = gameRepository.getStepsSummary()
		.map { stepsSummary ->
			GoalProgress(
				stepsToday = stepsSummary?.stepsToday ?: 0,
				goalSteps = stepsSummary?.goalDay ?: 0,
				gamificationEnabled = true,
			)
		}
		.stateIn(scope, SharingStarted.Lazily, GoalProgress(0, 0, true))
}
