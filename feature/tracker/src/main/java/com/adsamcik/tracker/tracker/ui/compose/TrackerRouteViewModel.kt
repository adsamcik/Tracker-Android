package com.adsamcik.tracker.tracker.ui.compose

import androidx.lifecycle.ViewModel
import com.adsamcik.tracker.shared.base.di.DailyPointsProvider
import com.adsamcik.tracker.shared.base.di.DailySummaryProvider
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TrackerRouteViewModel @Inject constructor(
    val trackerController: TrackerServiceController,
    val lockManager: LockManager,
    val dailySummaryProvider: DailySummaryProvider,
    val dailyPointsProvider: DailyPointsProvider,
    val goalProgressProvider: GoalProgressProvider,
) : ViewModel()
