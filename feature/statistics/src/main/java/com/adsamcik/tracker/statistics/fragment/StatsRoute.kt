package com.adsamcik.tracker.statistics.fragment

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.LoadState
import androidx.compose.ui.res.stringResource
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.presenter.StatsPresenterViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Entry point composable for Statistics tab. Uses Hilt for dependency injection.
 */
@Composable
fun StatsRoute(
    onTripClick: (Long) -> Unit = {},
    onTripViewOnMap: (Long, Long, Long) -> Unit = { _, _, _ -> },
    onNavigateToHistory: () -> Unit = {},
    onNavigateToTracker: () -> Unit = {},
    onNavigateToSummary: () -> Unit = {},
    onNavigateToWifiStats: () -> Unit = {},
) {
    val vm: StatsPresenterViewModel = hiltViewModel()
    val context = LocalContext.current
    val pagingItems = vm.tripsFlow.collectAsLazyPagingItems()

    // Date range remains a dialog because it's a tool that filters this very
    // screen; Summary and Wi-Fi stats are destination content and are now
    // dedicated routes (see SummaryRoute / WifiStatsRoute).
    var showDateRangeDialog by remember { mutableStateOf(false) }

    val weeklyBars by vm.weeklyBars.collectAsState()
    val heatmapData by vm.heatmapData.collectAsState()
    val activeDateFilter by vm.activeDateFilter.collectAsState()
    val selectedDateRange = activeDateFilter?.let { formatDateRange(it.startMs, it.endMs) }
    val selectedHeaderAction: StatsHeaderAction? = when {
        showDateRangeDialog || activeDateFilter != null -> StatsHeaderAction.Dates
        else -> null
    }

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
        onShowSummary = onNavigateToSummary,
        onShowWeek = { showDateRangeDialog = true },
        onOpenWifi = onNavigateToWifiStats,
        onNavigateToHistory = onNavigateToHistory,
        selectedHeaderAction = selectedHeaderAction,
        weeklyBars = weeklyBars,
        heatmapData = heatmapData,
        onTripClick = onTripClick,
        onTripViewOnMap = onTripViewOnMap,
        onTripDelete = vm::deleteTrip,
        onExportGpx = { trip -> vm.exportTripGpx(context, trip) },
        activeDateFilterLabel = selectedDateRange?.let {
            stringResource(R.string.stats_filter_active_label, it)
        },
        onStartTracking = onNavigateToTracker,
    )

    if (showDateRangeDialog) {
        com.adsamcik.tracker.statistics.ui.compose.StatsDateRangeDialog(
            initialStartMs = activeDateFilter?.startMs,
            initialEndMs = activeDateFilter?.endMs,
            onConfirm = { startMs, endMs ->
                vm.setDateRange(startMs = startMs, endMs = endMs)
                showDateRangeDialog = false
            },
            onClear = {
                vm.clearDateRange()
                showDateRangeDialog = false
            },
            onDismiss = { showDateRangeDialog = false },
        )
    }
}

private val statsDateFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun formatDateRange(startMs: Long, endMs: Long): String {
    val zoneId = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(startMs).atZone(zoneId).toLocalDate()
    val end = Instant.ofEpochMilli(endMs).atZone(zoneId).toLocalDate()
    return if (start == end) {
        statsDateFormatter.format(start)
    } else {
        "${statsDateFormatter.format(start)} – ${statsDateFormatter.format(end)}"
    }
}
