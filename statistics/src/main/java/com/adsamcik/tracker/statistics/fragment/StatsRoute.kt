package com.adsamcik.tracker.statistics.fragment

import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
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
import com.adsamcik.tracker.statistics.ui.compose.SummaryDialog
import com.adsamcik.tracker.statistics.ui.compose.WifiStatsDialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Entry point composable for Statistics tab. Uses Hilt for dependency injection.
 */
@Composable
fun StatsRoute(
    onTripClick: (Long) -> Unit = {},
    onTripViewOnMap: (Long) -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToTracker: () -> Unit = {},
) {
    val vm: StatsPresenterViewModel = hiltViewModel()
    val context = LocalContext.current
    val pagingItems = vm.tripsFlow.collectAsLazyPagingItems()

    // Dialog state management
    var showSummaryDialog by remember { mutableStateOf(false) }
    var showWifiDialog by remember { mutableStateOf(false) }
    var showDateRangeDialog by remember { mutableStateOf(false) }

    // Collect statistics state from ViewModel
    val summaryStatsState by vm.summaryStatsState.collectAsState()
    val wifiStatsState by vm.wifiStatsState.collectAsState()
    val weeklyBars by vm.weeklyBars.collectAsState()
    val heatmapData by vm.heatmapData.collectAsState()
    val activeDateFilter by vm.activeDateFilter.collectAsState()
    val selectedDateRange = activeDateFilter?.let { formatDateRange(it.startMs, it.endMs) }
    val selectedHeaderAction = when {
        showSummaryDialog -> StatsHeaderAction.Summary
        showWifiDialog -> StatsHeaderAction.Wifi
        showDateRangeDialog || activeDateFilter != null -> StatsHeaderAction.Dates
        else -> StatsHeaderAction.Summary
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
        onShowSummary = {
            vm.loadSummaryStats()
            showSummaryDialog = true
        },
        onShowWeek = { showDateRangeDialog = true },
        onOpenWifi = {
            vm.loadWifiStats()
            showWifiDialog = true
        },
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
    )

    // Show dialogs when state is true
    if (showSummaryDialog) {
        SummaryDialog(
            visible = showSummaryDialog,
            state = summaryStatsState,
            onDismiss = { showSummaryDialog = false }
        )
    }

    if (showWifiDialog) {
        WifiStatsDialog(
            visible = showWifiDialog,
            state = wifiStatsState,
            onDismiss = { showWifiDialog = false }
        )
    }

    if (showDateRangeDialog) {
        val pickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = activeDateFilter?.startMs,
            initialSelectedEndDateMillis = activeDateFilter?.endMs?.let(::pickerEndMillis),
        )
        DatePickerDialog(
            onDismissRequest = { showDateRangeDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val start = pickerState.selectedStartDateMillis
                        val end = pickerState.selectedEndDateMillis
                        if (start != null && end != null) {
                            vm.setDateRange(
                                startMs = start,
                                endMs = end + MILLIS_PER_DAY - 1L,
                            )
                            showDateRangeDialog = false
                        }
                    },
                    enabled = pickerState.selectedStartDateMillis != null &&
                        pickerState.selectedEndDateMillis != null,
                ) {
                    TextButtonText(android.R.string.ok)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (activeDateFilter != null) {
                            vm.clearDateRange()
                        }
                        showDateRangeDialog = false
                    },
                ) {
                    TextButtonText(
                        if (activeDateFilter != null) {
                            R.string.stats_filter_clear
                        } else {
                            android.R.string.cancel
                        },
                    )
                }
            },
        ) {
            DateRangePicker(
                state = pickerState,
                title = {
                    androidx.compose.material3.Text(
                        text = stringResource(R.string.stats_filter_dialog_title),
                    )
                },
            )
        }
    }
}

@Composable
private fun TextButtonText(textRes: Int) {
    androidx.compose.material3.Text(text = stringResource(textRes))
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

private fun pickerEndMillis(endMs: Long): Long = endMs - MILLIS_PER_DAY + 1L

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
