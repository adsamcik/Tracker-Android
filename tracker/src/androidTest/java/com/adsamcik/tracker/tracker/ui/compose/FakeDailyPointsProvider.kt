package com.adsamcik.tracker.tracker.ui.compose

import com.adsamcik.tracker.shared.base.di.DailyPointsProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeDailyPointsProvider : DailyPointsProvider {
    private val _pointsTodayFlow = MutableStateFlow(0)
    override val pointsTodayFlow: StateFlow<Int> = _pointsTodayFlow

    fun updatePoints(points: Int) {
        _pointsTodayFlow.value = points
    }
}
