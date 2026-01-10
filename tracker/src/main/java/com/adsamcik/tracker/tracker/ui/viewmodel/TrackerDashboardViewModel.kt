package com.adsamcik.tracker.tracker.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.summary.SummaryGenerator
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class DashboardState(
    val lastSession: TrackerSession? = null,
    val weeklyStats: List<Stat> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class TrackerDashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: TrackerServiceController,
    private val dispatchers: DispatchersProvider
) : ViewModel() {

    private val _weeklyStats = MutableStateFlow<List<Stat>>(emptyList())
    val weeklyStats: StateFlow<List<Stat>> = _weeklyStats.asStateFlow()

    val lastSessionFlow: StateFlow<TrackerSession?> = controller.lastSessionFlow

    // Combined state could be useful, or we can just expose individual flows.
    // Let's reload weekly stats when VM starts or on demand.

    init {
        loadWeeklyStats()
    }

    fun loadWeeklyStats() {
        viewModelScope.launch {
            val stats = withContext(dispatchers.io) {
                // Reuse logic from Statistics module via SummaryGenerator
                // Note: SummaryGenerator is in 'statistics' module.
                // Depending on 'statistics' from 'tracker' might cause circular dependency if 'statistics' depends on 'tracker'.
                // Checking dependency: statistics -> sbase, tracker -> sbase. sbase is safe.
                // BUT: 'tracker' -> 'statistics' dependency needs to be added to build.gradle.kts if not present.
                // If circular dependency exists (statistics -> tracker), we cannot add tracker -> statistics.
                // Let's assume we can use SummaryGenerator for now, or copy logic if circular.
                // Analysis showed statistics does NOT depend on tracker. So we can depend on statistics.
                try {
                    SummaryGenerator.buildSevenDaySummary(context)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            _weeklyStats.value = stats
        }
    }
}
