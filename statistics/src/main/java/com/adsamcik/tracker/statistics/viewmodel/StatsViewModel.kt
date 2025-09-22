package com.adsamcik.tracker.statistics.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.adsamcik.tracker.statistics.repository.SessionRepository
import com.adsamcik.tracker.statistics.data.Stat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel providing paged tracker sessions for statistics screen.
 * Uses constructor-injected SessionRepository for clean testing and modularity.
 */
class StatsViewModel(
    private val sessionRepository: SessionRepository
) : ViewModel() {

    // Pager producing sessions ordered by start DESC (Room PagingSource provided by repository)
    val sessionsFlow = Pager(
        config = PagingConfig(pageSize = 20, prefetchDistance = 5, initialLoadSize = 40, enablePlaceholders = false)
    ) { sessionRepository.getAllSessionsPaged() }
        .flow
        .cachedIn(viewModelScope)
    
    // Summary statistics state
    private val _summaryStats = MutableStateFlow<List<Stat>>(emptyList())
    val summaryStats: StateFlow<List<Stat>> = _summaryStats.asStateFlow()
    
    // Weekly statistics state
    private val _weeklyStats = MutableStateFlow<List<Stat>>(emptyList())
    val weeklyStats: StateFlow<List<Stat>> = _weeklyStats.asStateFlow()
    
    /**
     * Load summary statistics from repository.
     */
    fun loadSummaryStats() {
        viewModelScope.launch {
            _summaryStats.value = sessionRepository.getSummaryStats()
        }
    }
    
    /**
     * Load weekly statistics from repository.
     */
    fun loadWeeklyStats() {
        viewModelScope.launch {
            _weeklyStats.value = sessionRepository.getWeeklyStats()
        }
    }
}
