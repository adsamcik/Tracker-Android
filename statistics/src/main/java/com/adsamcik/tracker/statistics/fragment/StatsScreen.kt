package com.adsamcik.tracker.statistics.fragment

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.paging.compose.LazyPagingItems
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.base.R as BaseR
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Sailing
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.AppDimensions
import com.adsamcik.tracker.shared.utils.style.compose.MainNavigationLayout
import com.adsamcik.tracker.shared.utils.style.compose.rememberMainNavigationLayout
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.ui.compose.CalendarHeatmap
import com.adsamcik.tracker.statistics.viewmodel.DayBar

/**
 * Refresh state for statistics route. Mirrors the test expectations.
 */
sealed interface RefreshUiState {
    data object Loading : RefreshUiState
    data object Empty : RefreshUiState
    data object Error : RefreshUiState
    data object Content : RefreshUiState
}

/**
 * Append pagination state for statistics route. Mirrors the test expectations.
 */
sealed interface AppendUiState {
    data object NotLoading : AppendUiState
    data object Loading : AppendUiState
    data object Error : AppendUiState
}

enum class StatsHeaderAction {
    Summary,
    Dates,
    Wifi,
}

/** Test host expects this signature. */
@Composable
fun StatsScreen(
    refreshState: RefreshUiState,
    appendState: AppendUiState,
    onRetry: () -> Unit,
    onShowSummary: () -> Unit,
    onShowWeek: () -> Unit,
    onOpenWifi: () -> Unit,
    selectedHeaderAction: StatsHeaderAction? = null,
    weeklyBars: List<DayBar> = emptyList(),
    heatmapData: Map<LocalDate, Float> = emptyMap(),
    onTripClick: (Long) -> Unit = {},
    onTripViewOnMap: (Long) -> Unit = {},
    onTripDelete: (Long) -> Unit = {},
    onExportGpx: (Trip) -> Unit = {},
    activeDateFilterLabel: String? = null,
    // Optional paging trips supplied by route; tests omit it and rely on placeholders.
    sessions: LazyPagingItems<Trip>? = null,
){
    val navigationLayout = rememberMainNavigationLayout()
    val bottomClearance = if (navigationLayout == MainNavigationLayout.SideRail) 24.dp else AppDimensions.FloatingNavBarClearance
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // Nav bar already labels this tab "Statistics" — skip a duplicate screen-level title
        // and let the header action chips sit directly at the top.
        Box(modifier = Modifier.weight(1f)) {
            when (refreshState) {
                RefreshUiState.Loading -> LoadingState()
                RefreshUiState.Empty -> EmptyState()
                RefreshUiState.Error -> ErrorState(onRetry)
                RefreshUiState.Content -> ContentState(
                    appendState = appendState,
                    bottomClearance = bottomClearance,
                    onRetry = onRetry,
                    onShowSummary = onShowSummary,
                    onShowWeek = onShowWeek,
                    onOpenWifi = onOpenWifi,
                    selectedHeaderAction = selectedHeaderAction,
                    weeklyBars = weeklyBars,
                    heatmapData = heatmapData,
                    onTripClick = onTripClick,
                    onTripViewOnMap = onTripViewOnMap,
                    onTripDelete = onTripDelete,
                    onExportGpx = onExportGpx,
                    activeDateFilterLabel = activeDateFilterLabel,
                    pagingItems = sessions,
                )
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.stats_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.Route,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.stats_no_tracker_sessions),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        // Using module-specific generic error string
        Text(
            text = stringResource(R.string.stats_error_generic), 
            modifier = Modifier.padding(horizontal = 24.dp),
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        ) { 
            Text(stringResource(R.string.action_retry)) 
        }
    }
}

@Composable
private fun ContentState(
    appendState: AppendUiState,
    bottomClearance: androidx.compose.ui.unit.Dp,
    onRetry: () -> Unit,
    onShowSummary: () -> Unit,
    onShowWeek: () -> Unit,
    onOpenWifi: () -> Unit,
    selectedHeaderAction: StatsHeaderAction? = null,
    weeklyBars: List<DayBar> = emptyList(),
    heatmapData: Map<LocalDate, Float> = emptyMap(),
    onTripClick: (Long) -> Unit = {},
    onTripViewOnMap: (Long) -> Unit = {},
    onTripDelete: (Long) -> Unit = {},
    onExportGpx: (Trip) -> Unit = {},
    activeDateFilterLabel: String? = null,
    pagingItems: LazyPagingItems<Trip>? = null,
){
    val sessionCount = pagingItems?.itemCount ?: 5
    val showSparseSummary = pagingItems != null && sessionCount in 1..2

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 16.dp, // Increased top padding
            bottom = bottomClearance,
            start = 16.dp,
            end = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header_actions") { 
            HeaderActions(
                onShowSummary = onShowSummary,
                onShowWeek = onShowWeek,
                onOpenWifi = onOpenWifi,
                selectedAction = selectedHeaderAction,
            )
        }

        if (heatmapData.isNotEmpty()) {
            item(key = "calendar_heatmap") {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stats_calendar_heatmap"),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.stats_heatmap_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        CalendarHeatmap(
                            data = heatmapData,
                            weeks = 26,
                        )
                    }
                }
            }
        }

        if (!activeDateFilterLabel.isNullOrBlank()) {
            item(key = "active_date_filter") {
                ActiveFilterCard(activeDateFilterLabel)
            }
        }
        
        if (pagingItems != null) {
            val count = pagingItems.itemCount
            
            items(count) { index ->
                val trip = pagingItems[index]
                if (trip != null) {
                    // Derive header visibility from previous item's date, not mutable state
                    val tripDate = if (trip.startTimeMs > 0) {
                        Instant.ofEpochMilli(trip.startTimeMs)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                    } else null
                    
                    val prevDate = if (index > 0) {
                        val prevTrip = pagingItems.peek(index - 1)
                        if (prevTrip != null && prevTrip.startTimeMs > 0) {
                            Instant.ofEpochMilli(prevTrip.startTimeMs)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                        } else null
                    } else null
                    
                    // Show date header when date differs from previous item (or is first)
                    if (tripDate != null && tripDate != prevDate) {
                        DateHeader(trip.startTimeMs)
                    }
                    
                    TripRow(
                        trip = trip,
                        onClick = { onTripClick(trip.id) },
                        onViewOnMap = { onTripViewOnMap(trip.id) },
                        onDelete = { onTripDelete(trip.id) },
                        onExportGpx = { onExportGpx(trip) },
                    )
                }
            }
        } else {
            // Placeholder state for tests
            items(5) { index -> 
                if (index == 0) {
                    DateHeader(System.currentTimeMillis())
                }
                TripRow(Trip(id = index.toLong(), startTimeMs = 0, endTimeMs = 0, distanceM = 0f, steps = null, primaryActivity = null, activityConfidence = null, sampleCount = 0, source = com.adsamcik.tracker.shared.base.database.data.SegmentSource.USER_CREATED, createdAt = 0))
            }
        }

        if (showSparseSummary) {
            item(key = "sparse_summary_footer") {
                SparseStatsSummaryCard(
                    visibleSessionCount = sessionCount,
                    weeklyBars = weeklyBars,
                    modifier = Modifier.padding(bottom = bottomClearance),
                )
            }
        }
         
        // Footer append UI state inline
        item(key = "append_state_footer") {
            AppendStateSection(appendState, onRetry)
        }
    }
}

@Composable
private fun AppendStateSection(state: AppendUiState, onRetry: () -> Unit) {
    when (state) {
        AppendUiState.Loading -> Column(Modifier.fillMaxWidth()) { repeat(3) { PlaceholderRow(it) } }
        AppendUiState.Error -> AppendErrorRow(onRetry)
        AppendUiState.NotLoading -> Unit
    }
}

@Composable
private fun HeaderActions(
    onShowSummary: () -> Unit,
    onShowWeek: () -> Unit,
    onOpenWifi: () -> Unit,
    selectedAction: StatsHeaderAction?,
) {
    val summaryLabel = stringResource(R.string.stats_sum_title)
    val weekLabel = stringResource(R.string.stats_filter_dates)
    val wifiLabel = stringResource(R.string.stats_wifi_chip_label)
    val scrollState = rememberScrollState()

    // Summary and Wi-Fi are one-shot actions (they open a dialog and return to this screen);
    // Dates is a persistent filter that remains visibly selected while the filter is applied.
    // Using AssistChip for the actions + FilterChip for the filter gives TalkBack and sighted users
    // the correct affordance per Material 3 Expressive.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionAssistChip(
            onClick = onShowSummary,
            icon = Icons.Filled.Summarize,
            label = summaryLabel,
        )
        ActionFilterChip(
            onClick = onShowWeek,
            icon = Icons.Filled.CalendarMonth,
            label = weekLabel,
            isSelected = selectedAction == StatsHeaderAction.Dates,
        )
        ActionAssistChip(
            onClick = onOpenWifi,
            icon = Icons.Filled.Wifi,
            label = wifiLabel,
        )
    }
}

@Composable
private fun ActionAssistChip(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.AssistChip(
        modifier = modifier,
        onClick = onClick,
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@Composable
private fun ActionFilterChip(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val selectedContainer = MaterialTheme.colorScheme.secondaryContainer
    val selectedContent = MaterialTheme.colorScheme.onSecondaryContainer

    FilterChip(
        modifier = modifier.semantics { selected = isSelected },
        selected = isSelected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = selectedContainer,
            selectedLabelColor = selectedContent,
            selectedLeadingIconColor = selectedContent,
        ),
    )
}

@Composable
private fun ActiveFilterCard(label: String) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.CalendarMonth,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SparseStatsSummaryCard(
    visibleSessionCount: Int,
    weeklyBars: List<DayBar>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = context.resources
    val settings = remember { TrackerSettingsQuick.snapshot(context) }
    val totalDistanceM = remember(weeklyBars) { weeklyBars.sumOf { it.distanceM.toDouble() }.toFloat() }
    val totalSteps = remember(weeklyBars) { weeklyBars.sumOf { it.steps } }
    val stepCounterSupported = remember {
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_SENSOR_STEP_COUNTER)
    }
    val activeDays = remember(weeklyBars) { weeklyBars.count { it.distanceM > 0f || it.steps > 0 || it.sessionCount > 0 } }
    val distanceText = remember(totalDistanceM, settings) {
        resources.formatDistance(
            totalDistanceM,
            digits = if (totalDistanceM >= 1000f) 1 else 2,
            unit = settings.lengthSystem,
        )
    }

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("stats_sparse_summary"),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.stats_sparse_summary_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.stats_sparse_summary_subtitle,
                        visibleSessionCount,
                        visibleSessionCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SummaryMetricCard(
                    label = stringResource(R.string.stats_distance_total),
                    value = distanceText,
                    modifier = Modifier.weight(1f),
                )
                SummaryMetricCard(
                    label = stringResource(R.string.stats_steps),
                    value = if (totalSteps > 0 || stepCounterSupported) {
                        totalSteps.formatReadable()
                    } else {
                        stringResource(R.string.stats_metric_not_available_short)
                    },
                    modifier = Modifier.weight(1f),
                )
                SummaryMetricCard(
                    label = stringResource(R.string.stats_sparse_summary_active_days),
                    value = activeDays.formatReadable(),
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = stringResource(R.string.stats_sparse_summary_week_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                weeklyBars.forEach { dayBar ->
                    WeeklySummaryDayChip(
                        dayBar = dayBar,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun WeeklySummaryDayChip(
    dayBar: DayBar,
    modifier: Modifier = Modifier,
) {
    val hasActivity = dayBar.distanceM > 0f || dayBar.steps > 0 || dayBar.sessionCount > 0
    val containerColor = if (hasActivity) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (hasActivity) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = dayBar.dayLabel,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = if (dayBar.steps > 0) dayBar.steps.formatReadable() else "—",
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
            maxLines = 1,
        )
    }
}

private val sessionTimeFormatter: DateTimeFormatter by lazy {
    DateTimeFormatter.ofPattern("HH:mm")
}

private val sessionDateFormatter: DateTimeFormatter by lazy {
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
}

/**
 * Returns an appropriate icon for the trip based on primary activity type.
 */
private data class TripActivityPresentation(
    val icon: ImageVector,
    @StringRes val labelRes: Int,
)

private fun getTripActivityPresentation(trip: Trip): TripActivityPresentation {
    // Use DetectedActivity constants from Google Play Services
    return when (trip.primaryActivity) {
        0 -> TripActivityPresentation(Icons.Filled.DirectionsCar, BaseR.string.activity_in_vehicle)
        1 -> TripActivityPresentation(Icons.AutoMirrored.Filled.DirectionsBike, BaseR.string.activity_bicycle)
        2 -> TripActivityPresentation(Icons.AutoMirrored.Filled.DirectionsWalk, BaseR.string.activity_on_foot)
        7 -> TripActivityPresentation(Icons.AutoMirrored.Filled.DirectionsWalk, BaseR.string.activity_walking)
        8 -> TripActivityPresentation(Icons.AutoMirrored.Filled.DirectionsRun, BaseR.string.activity_running)
        else -> {
            val steps = trip.steps ?: 0
            when {
                steps > 0 && trip.distanceM > 1000 -> TripActivityPresentation(
                    Icons.AutoMirrored.Filled.DirectionsRun,
                    BaseR.string.activity_running
                )
                steps > 0 -> TripActivityPresentation(
                    Icons.AutoMirrored.Filled.DirectionsWalk,
                    BaseR.string.activity_walking
                )
                else -> TripActivityPresentation(Icons.Filled.Route, R.string.stats_format_unknown_activity)
            }
        }
    }
}

internal fun getTripActivityLabel(context: Context, trip: Trip): String =
    context.getString(getTripActivityPresentation(trip).labelRes)

internal fun buildTripRowContentDescription(
    timeText: String,
    activityTypeText: String,
    durationText: String?,
    distanceText: String?,
    stepsText: String?,
): String = listOfNotNull(
    timeText.takeIf { it.isNotBlank() },
    activityTypeText.takeIf { it.isNotBlank() },
    durationText?.takeIf { it.isNotBlank() },
    distanceText?.takeIf { it.isNotBlank() },
    stepsText?.takeIf { it.isNotBlank() },
).joinToString(separator = ", ")

/**
 * Trip row with activity icon, formatted duration, distance, and steps.
 */
@Composable
internal fun TripRow(
    trip: Trip,
    onClick: (() -> Unit)? = null,
    onViewOnMap: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onExportGpx: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val resources = context.resources
    val settings = remember { TrackerSettingsQuick.snapshot(context) }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    val durationMs = trip.durationMs
    
    val durationText = remember(durationMs) {
        if (durationMs > 0) durationMs.formatAsDuration(context) else null
    }
    
    val distanceText = remember(trip.distanceM, settings) {
        if (trip.distanceM > 0) {
            resources.formatDistance(
                trip.distanceM,
                digits = if (trip.distanceM >= 1000f) 1 else 2,
                unit = settings.lengthSystem
            )
        } else null
    }
    
    val timeText = remember(trip.startTimeMs) {
        if (trip.startTimeMs != 0L) {
            sessionTimeFormatter.format(
                Instant.ofEpochMilli(trip.startTimeMs).atZone(ZoneId.systemDefault())
            )
        } else "--"
    }

    val dateText = remember(trip.startTimeMs) {
        if (trip.startTimeMs > 0L) {
            sessionDateFormatter.format(
                Instant.ofEpochMilli(trip.startTimeMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            )
        } else "--"
    }
    
    val tripActivity = remember(trip.primaryActivity, trip.steps, trip.distanceM) {
        getTripActivityPresentation(trip)
    }
    val tripIcon = tripActivity.icon
    val activityTypeText = stringResource(tripActivity.labelRes)
    val steps = trip.steps ?: 0
    val stepsText = remember(steps) {
        if (steps > 0) {
            resources.getQuantityString(R.plurals.stats_trip_row_steps_content_description, steps, steps.formatReadable())
        } else {
            null
        }
    }
    
    val rowLabel = remember(timeText, activityTypeText, durationText, distanceText, stepsText) {
        buildTripRowContentDescription(
            timeText = timeText,
            activityTypeText = activityTypeText,
            durationText = durationText,
            distanceText = distanceText,
            stepsText = stepsText,
        )
    }

    if (showDeleteDialog && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.trip_detail_delete_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.trip_detail_delete_confirm_message))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DeleteSummaryLine(
                            label = stringResource(R.string.trip_detail_date),
                            value = dateText,
                        )
                        DeleteSummaryLine(
                            label = stringResource(R.string.trip_detail_distance),
                            value = distanceText ?: "--",
                        )
                        DeleteSummaryLine(
                            label = stringResource(R.string.trip_detail_duration),
                            value = durationText ?: "--",
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text(stringResource(R.string.trip_detail_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.trip_detail_cancel))
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(com.adsamcik.tracker.shared.utils.style.compose.TerrainCardShape)
            .then(
                if (onClick != null || onViewOnMap != null || onDelete != null) {
                    Modifier.tripRowClickable(
                        onClick = onClick,
                        onLongClick = { showMenu = true },
                    )
                } else {
                    Modifier
                }
            )
            .semantics {
                contentDescription = rowLabel
            }
            .testTag("stats_session_row")
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = com.adsamcik.tracker.shared.utils.style.compose.TerrainCardShape
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = tripIcon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = timeText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (durationText != null) {
                            Text(
                                text = "• $durationText",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (distanceText != null || steps > 0) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (distanceText != null) {
                                MetricBadge(
                                    icon = Icons.Filled.Route,
                                    value = distanceText
                                )
                            }
                            if (steps > 0) {
                                MetricBadge(
                                    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                                    value = steps.formatReadable()
                                )
                            }
                        }
                    }
                }
                if (onClick != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            if (onClick != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.trip_detail_title)) },
                    onClick = {
                        showMenu = false
                        onClick()
                    },
                )
            }
            if (onViewOnMap != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.trip_detail_route_map)) },
                    onClick = {
                        showMenu = false
                        onViewOnMap()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.trip_detail_export_gpx)) },
                onClick = {
                    showMenu = false
                    onExportGpx?.invoke()
                },
            )
            if (onDelete != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.trip_detail_delete)) },
                    onClick = {
                        showMenu = false
                        showDeleteDialog = true
                    },
                )
            }
        }
    }
}

@Composable
private fun DeleteSummaryLine(
    label: String,
    value: String,
) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.tripRowClickable(
    onClick: (() -> Unit)?,
    onLongClick: () -> Unit,
): Modifier = combinedClickable(
    onClick = { onClick?.invoke() },
    onLongClick = onLongClick,
    role = Role.Button,
)

@Composable
private fun MetricBadge(
    icon: ImageVector,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Date header separator for grouping sessions by day.
 */
@Composable
internal fun DateHeader(dateMillis: Long) {
    val context = LocalContext.current
    val dateText = remember(dateMillis) {
        formatRelativeDate(context, dateMillis)
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        )
        Text(
            text = dateText,
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        )
    }
}

private fun formatRelativeDate(context: Context, dateMillis: Long): String {
    val sessionDate = Instant.ofEpochMilli(dateMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    val today = LocalDate.now()
    val daysDiff = ChronoUnit.DAYS.between(sessionDate, today)
    
    return when {
        daysDiff == 0L -> context.getString(R.string.stats_date_today)
        daysDiff == 1L -> context.getString(R.string.stats_date_yesterday)
        daysDiff < 7L -> sessionDateFormatter.format(sessionDate)
        else -> sessionDateFormatter.format(sessionDate)
    }
}

@Composable
private fun PlaceholderRow(index: Int) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .testTag("stats_placeholder_$index")
    ) {
        // Placeholder content
        Row(verticalAlignment = Alignment.CenterVertically) {
             Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)))
        }
    }
}

@Composable
private fun AppendErrorRow(onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("stats_append_error"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(R.string.stats_append_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}
