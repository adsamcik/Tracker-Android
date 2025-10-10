package com.adsamcik.tracker.statistics.fragment

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.LoadState
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.di.LocalViewModelFactory
import com.adsamcik.tracker.statistics.viewmodel.StatsViewModel
import com.adsamcik.tracker.statistics.ui.compose.SummaryDialog
import com.adsamcik.tracker.statistics.ui.compose.WeekDialog

/**
 * Entry point composable for Statistics tab. Uses ViewModelFactory for dependency injection.
 */
@Composable
fun StatsRoute() {
    val factory = LocalViewModelFactory.current
    val vm: StatsViewModel = viewModel(factory = factory)
    val pagingItems = vm.sessionsFlow.collectAsLazyPagingItems()

    // Dialog state management
    var showSummaryDialog by remember { mutableStateOf(false) }
    var showWeekDialog by remember { mutableStateOf(false) }
    
    // Collect statistics state from ViewModel
    val summaryStats by vm.summaryStats.collectAsState()
    val summaryLoading by vm.summaryLoading.collectAsState()
    val weeklyStats by vm.weeklyStats.collectAsState()
    val weeklyLoading by vm.weeklyLoading.collectAsState()

    val refreshState = when (val s = pagingItems.loadState.refresh) {
        is LoadState.Loading -> RefreshUiState.Loading
        is LoadState.Error -> RefreshUiState.Error
        is LoadState.NotLoading -> if (pagingItems.itemCount == 0) RefreshUiState.Empty else RefreshUiState.Content
    }
    val appendState = when (pagingItems.loadState.append) {
        is LoadState.Loading -> AppendUiState.Loading
        is LoadState.Error -> AppendUiState.Error
        is LoadState.NotLoading -> AppendUiState.NotLoading
    }

    StatsScreen(
        refreshState = refreshState,
        appendState = appendState,
        sessions = pagingItems,
        onRetry = { pagingItems.retry() },
        onShowSummary = { 
            vm.loadSummaryStats()
            showSummaryDialog = true 
        },
        onShowWeek = { 
            vm.loadWeeklyStats()
            showWeekDialog = true 
        },
        onOpenWifi = {
            // WiFi browser feature not yet implemented
            // Future: Display discovered WiFi networks for sessions
        }
    )
    
    // Show dialogs when state is true
    if (showSummaryDialog) {
        SummaryDialog(
            visible = showSummaryDialog,
            stats = summaryStats,
            isLoading = summaryLoading,
            onDismiss = { showSummaryDialog = false }
        )
    }
    
    if (showWeekDialog) {
        WeekDialog(
            visible = showWeekDialog,
            stats = weeklyStats,
            isLoading = weeklyLoading,
            onDismiss = { showWeekDialog = false }
        )
    }
}
