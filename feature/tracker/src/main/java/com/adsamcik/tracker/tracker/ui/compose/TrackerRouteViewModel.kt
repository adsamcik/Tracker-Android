package com.adsamcik.tracker.tracker.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.base.di.DailyPointsProvider
import com.adsamcik.tracker.shared.base.di.DailySummaryProvider
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplay
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRepository
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import com.adsamcik.tracker.tracker.controller.TodayStepsDisplay
import com.adsamcik.tracker.tracker.data.RecentTripsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class TrackerRouteViewModel @Inject constructor(
    val trackerController: TrackerStateReader,
    val lockManager: LockManager,
    val dailySummaryProvider: DailySummaryProvider,
    val dailyPointsProvider: DailyPointsProvider,
    val goalProgressProvider: GoalProgressProvider,
    recentTripsRepository: RecentTripsRepository,
    stepsNumericSummaryRepository: StepsNumericSummaryRepository,
    clock: Clock,
) : ViewModel() {
    private val todayStepsDisplay = TodayStepsDisplay(stepsNumericSummaryRepository, clock)
    val stepsToday = todayStepsDisplay.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0L, 0L), StepsDailyDisplay.Materializing)

    /** Refreshes the saved Steps display for the current local day independently of session capture. */
    fun refreshStepsToday() = todayStepsDisplay.refresh()

    val recentTrips = recentTripsRepository.observeRecentTrips(RECENT_TRIP_LIMIT)
        .catch { emit(emptyList()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    private companion object {
        const val RECENT_TRIP_LIMIT = 3
        const val STATE_STOP_TIMEOUT_MS = 5_000L
    }
}
