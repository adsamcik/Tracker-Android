package com.adsamcik.tracker.tracker.ui.compose

import com.adsamcik.tracker.shared.base.di.GoalProgress
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeGoalProgressProvider : GoalProgressProvider {
    private val _goalProgressFlow = MutableStateFlow(GoalProgress(0, 0, false))
    override val goalProgressFlow: StateFlow<GoalProgress> = _goalProgressFlow

    fun updateGoalProgress(progress: GoalProgress) {
        _goalProgressFlow.value = progress
    }
}
