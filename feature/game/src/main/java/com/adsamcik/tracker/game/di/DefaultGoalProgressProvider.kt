package com.adsamcik.tracker.game.di

import android.content.Context
import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.game.repository.toGoalProgress
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
		.map { stepsSummary -> stepsSummary.toGoalProgress() }
		.stateIn(
			scope = scope,
			started = SharingStarted.WhileSubscribed(
				stopTimeoutMillis = 0L,
				replayExpirationMillis = 0L,
			),
			initialValue = null.toGoalProgress(),
		)
}
