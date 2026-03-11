package com.adsamcik.tracker.statistics.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.statistics.repository.SessionRepository
import com.adsamcik.tracker.statistics.data.Stat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Sealed class representing the loading state of statistics data.
 */
sealed class StatsLoadState {
    data object Idle : StatsLoadState()
    data object Loading : StatsLoadState()
    data class Success(val stats: List<Stat>) : StatsLoadState()
    data class Error(val message: String) : StatsLoadState()
}

/**
 * Per-day bar data for the weekly summary chart.
 * @param dayLabel Short day name (e.g. "Mon")
 * @param distanceM Total distance in meters for this day
 * @param steps Total step count for this day
 * @param epochDay The java.time epoch day value
 */
data class DayBar(
    val dayLabel: String,
    val distanceM: Float,
    val steps: Int,
    val epochDay: Long,
)

/**
 * ViewModel providing paged trips for the statistics screen.
 * Uses TripDao for the main list (session_segment table) and
 * SessionRepository for legacy summary/weekly stats dialogs.
 */
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val tripDao: TripDao,
    private val sessionRepository: SessionRepository,
    private val dailySummaryDao: DailySummaryDao,
) : ViewModel() {

    // Pager producing trips ordered by start_time_ms DESC
    val tripsFlow = Pager(
        config = PagingConfig(pageSize = 20, prefetchDistance = 5, initialLoadSize = 40, enablePlaceholders = false)
    ) { tripDao.getAllPaged() }
        .flow
        .cachedIn(viewModelScope)

    // Summary statistics state with error handling
    private val _summaryStatsState = MutableStateFlow<StatsLoadState>(StatsLoadState.Idle)
    val summaryStatsState: StateFlow<StatsLoadState> = _summaryStatsState.asStateFlow()

    // Weekly statistics state with error handling
    private val _weeklyStatsState = MutableStateFlow<StatsLoadState>(StatsLoadState.Idle)
    val weeklyStatsState: StateFlow<StatsLoadState> = _weeklyStatsState.asStateFlow()

    // Weekly bar chart data for the last 7 days
    private val _weeklyBars = MutableStateFlow<List<DayBar>>(emptyList())
    val weeklyBars: StateFlow<List<DayBar>> = _weeklyBars.asStateFlow()

    init {
        loadWeeklyBars()
    }

    private fun loadWeeklyBars() {
        viewModelScope.launch {
            try {
                val todayEpochDay = LocalDate.now().toEpochDay()
                val fromDay = todayEpochDay - 6 // last 7 days inclusive
                val summaries = dailySummaryDao.getBetween(fromDay, todayEpochDay)
                val summaryMap = summaries.associateBy { it.dateEpochDay }

                val dayFormatter = DateTimeFormatter.ofPattern("EEE")
                val bars = (0L..6L).map { offset ->
                    val epochDay = fromDay + offset
                    val date = LocalDate.ofEpochDay(epochDay)
                    val label = date.format(dayFormatter)
                    val summary = summaryMap[epochDay]
                    DayBar(
                        dayLabel = label,
                        distanceM = summary?.totalDistanceM ?: 0f,
                        steps = summary?.totalSteps ?: 0,
                        epochDay = epochDay
                    )
                }
                _weeklyBars.value = bars
            } catch (_: Exception) {
                _weeklyBars.value = emptyList()
            }
        }
    }

    /**
     * Load summary statistics from repository.
     * Uses SessionRepository summary adapter backed by Room aggregates.
     */
    fun loadSummaryStats() {
        viewModelScope.launch {
            _summaryStatsState.value = StatsLoadState.Loading
            try {
                val stats = sessionRepository.getSummaryStats()
                _summaryStatsState.value = StatsLoadState.Success(stats)
            } catch (e: Exception) {
                _summaryStatsState.value = StatsLoadState.Error(
                    e.message ?: "Failed to load summary statistics"
                )
            }
        }
    }

    /**
     * Load weekly statistics from repository.
     * Uses SessionRepository summary adapter backed by Room aggregates.
     */
    fun loadWeeklyStats() {
        viewModelScope.launch {
            _weeklyStatsState.value = StatsLoadState.Loading
            try {
                val stats = sessionRepository.getWeeklyStats()
                _weeklyStatsState.value = StatsLoadState.Success(stats)
            } catch (e: Exception) {
                _weeklyStatsState.value = StatsLoadState.Error(
                    e.message ?: "Failed to load weekly statistics"
                )
            }
        }
    }
}
