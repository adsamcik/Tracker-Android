package com.adsamcik.tracker.statistics.fragment

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.LoadState
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.statistics.viewmodel.StatsLoadState
import com.adsamcik.tracker.statistics.viewmodel.StatsViewModel
import com.adsamcik.tracker.statistics.ui.compose.SummaryDialog
import com.adsamcik.tracker.statistics.ui.compose.WeekDialog

/**
 * Entry point composable for Statistics tab. Uses Hilt for dependency injection.
 */
@Composable
fun StatsRoute() {
    val vm: StatsViewModel = hiltViewModel()
    val pagingItems = vm.sessionsFlow.collectAsLazyPagingItems()

    // Dialog state management
    var showSummaryDialog by remember { mutableStateOf(false) }
    var showWeekDialog by remember { mutableStateOf(false) }
    
    // Collect statistics state from ViewModel
    val summaryStatsState by vm.summaryStatsState.collectAsState()
    val weeklyStatsState by vm.weeklyStatsState.collectAsState()

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
            state = summaryStatsState,
            onDismiss = { showSummaryDialog = false }
        )
    }
    
    if (showWeekDialog) {
        WeekDialog(
            visible = showWeekDialog,
            state = weeklyStatsState,
            onDismiss = { showWeekDialog = false }
        )
    }
}
