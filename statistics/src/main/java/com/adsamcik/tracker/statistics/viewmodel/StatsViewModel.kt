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
    
    // Summary loading state
    private val _summaryLoading = MutableStateFlow(false)
    val summaryLoading: StateFlow<Boolean> = _summaryLoading.asStateFlow()
    
    // Weekly statistics state
    private val _weeklyStats = MutableStateFlow<List<Stat>>(emptyList())
    val weeklyStats: StateFlow<List<Stat>> = _weeklyStats.asStateFlow()
    
    // Weekly loading state
    private val _weeklyLoading = MutableStateFlow(false)
    val weeklyLoading: StateFlow<Boolean> = _weeklyLoading.asStateFlow()
    
    /**
     * Load summary statistics from repository.
     * Sets loading state and handles errors gracefully.
     */
    fun loadSummaryStats() {
        viewModelScope.launch {
            _summaryLoading.value = true
            try {
                _summaryStats.value = sessionRepository.getSummaryStats()
            } catch (e: Exception) {
                // Log error and leave stats as empty list
                // TODO: Consider exposing error state to UI
                _summaryStats.value = emptyList()
            } finally {
                _summaryLoading.value = false
            }
        }
    }
    
    /**
     * Load weekly statistics from repository.
     * Sets loading state and handles errors gracefully.
     */
    fun loadWeeklyStats() {
        viewModelScope.launch {
            _weeklyLoading.value = true
            try {
                _weeklyStats.value = sessionRepository.getWeeklyStats()
            } catch (e: Exception) {
                // Log error and leave stats as empty list
                // TODO: Consider exposing error state to UI
                _weeklyStats.value = emptyList()
            } finally {
                _weeklyLoading.value = false
            }
        }
    }
}
