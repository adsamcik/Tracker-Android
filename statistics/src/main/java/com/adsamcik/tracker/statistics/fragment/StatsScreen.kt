package com.adsamcik.tracker.statistics.fragment

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.paging.compose.LazyPagingItems
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.adsamcik.tracker.shared.utils.style.compose.AppDimensions
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.AppColors
import com.adsamcik.tracker.shared.utils.style.compose.AppShapes
import com.adsamcik.tracker.shared.utils.style.compose.EmptyStateCard
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.viewmodel.DayBar
import com.adsamcik.tracker.statistics.viewmodel.activityIcon

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

/** Test host expects this signature. New parameters use defaults for backward compatibility. */
@Composable
fun StatsScreen(
    refreshState: RefreshUiState,
    appendState: AppendUiState,
    onRetry: () -> Unit,
    onShowSummary: () -> Unit,
    onShowWeek: () -> Unit,
    onOpenWifi: () -> Unit,
    // Optional paging trips supplied by route; tests omit it and rely on placeholders.
    trips: LazyPagingItems<Trip>? = null,
    onTripClick: (Long) -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToTracker: () -> Unit = {},
    weeklyBars: List<DayBar> = emptyList(),
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (refreshState) {
            RefreshUiState.Loading -> LoadingState()
            RefreshUiState.Empty -> EmptyState(onNavigateToTracker)
            RefreshUiState.Error -> ErrorState(onRetry)
            RefreshUiState.Content -> ContentState(
                appendState, onRetry, onShowSummary, onShowWeek, onOpenWifi,
                trips, onTripClick, onNavigateToHistory, weeklyBars
            )
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
private fun EmptyState(onNavigateToTracker: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        EmptyStateCard(
            icon = Icons.Filled.Route,
            title = stringResource(R.string.stats_no_tracker_sessions),
            subtitle = stringResource(R.string.stats_empty_subtitle),
            action = {
                FilledTonalButton(onClick = onNavigateToTracker) {
                    Text(stringResource(R.string.stats_start_tracking_cta))
                }
            }
        )
    }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
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
    onRetry: () -> Unit,
    onShowSummary: () -> Unit,
    onShowWeek: () -> Unit,
    onOpenWifi: () -> Unit,
    pagingItems: LazyPagingItems<Trip>? = null,
    onTripClick: (Long) -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    weeklyBars: List<DayBar> = emptyList(),
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 16.dp,
            bottom = AppDimensions.FloatingNavBarClearance,
            start = 16.dp,
            end = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (weeklyBars.isNotEmpty() && weeklyBars.any { it.distanceM > 0f }) {
            item(key = "weekly_chart") {
                WeeklySummaryChart(bars = weeklyBars)
            }
        }

        item(key = "header_actions") {
            HeaderActions(onShowSummary, onShowWeek, onOpenWifi, onNavigateToHistory)
        }

        if (pagingItems != null) {
            val count = pagingItems.itemCount
            var lastDateKey: String? = null

            items(count) { index ->
                val trip = pagingItems[index]
                if (trip != null) {
                    val tripDate = if (trip.startTimeMs > 0) {
                        Instant.ofEpochMilli(trip.startTimeMs)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                    } else null

                    val currentDateKey = tripDate?.toString()

                    if (currentDateKey != null && currentDateKey != lastDateKey) {
                        DateHeader(trip.startTimeMs)
                        lastDateKey = currentDateKey
                    }

                    TripRow(
                        trip = trip,
                        onClick = { onTripClick(trip.id) }
                    )
                }
            }
        } else {
            // Placeholder state for tests — renders empty cards
            items(5) { index ->
                if (index == 0) {
                    DateHeader(System.currentTimeMillis())
                }
                PlaceholderRow(index)
            }
        }

        // Footer append UI state inline
        item(key = "append_state_footer") {
            AppendStateSection(appendState, onRetry)
        }
    }
}

/**
 * Canvas-based bar chart showing distance per day for the last 7 days.
 */
@Composable
private fun WeeklySummaryChart(bars: List<DayBar>) {
    val context = LocalContext.current
    val resources = context.resources
    val settings = remember { TrackerSettingsQuick.snapshot(context) }

    val maxDistance = remember(bars) { bars.maxOf { it.distanceM }.coerceAtLeast(1f) }
    val totalDistance = remember(bars) { bars.sumOf { it.distanceM.toDouble() }.toFloat() }
    val totalSteps = remember(bars) { bars.sumOf { it.steps } }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.stats_weekly_chart_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = resources.formatDistance(totalDistance, digits = 1, unit = settings.lengthSystem),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(16.dp))

            // Bar chart
            val barColor = MaterialTheme.colorScheme.primary
            val emptyBarColor = MaterialTheme.colorScheme.surfaceContainerHighest
            val chartContentDescription = remember(bars, settings) {
                val dayDetails = bars.joinToString(", ") { bar ->
                    "${bar.dayLabel} ${resources.formatDistance(bar.distanceM, digits = 1, unit = settings.lengthSystem)}"
                }
                "${resources.getString(R.string.stats_weekly_chart_title)}: $dayDetails"
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .semantics { contentDescription = chartContentDescription }
            ) {
                val barCount = bars.size
                val spacing = 8.dp.toPx()
                val barWidth = (size.width - spacing * (barCount - 1)) / barCount
                val maxBarHeight = size.height

                bars.forEachIndexed { index, bar ->
                    val x = index * (barWidth + spacing)
                    val barHeight = if (maxDistance > 0f) {
                        (bar.distanceM / maxDistance * maxBarHeight).coerceAtLeast(4.dp.toPx())
                    } else {
                        4.dp.toPx()
                    }
                    val y = maxBarHeight - barHeight

                    drawRoundRect(
                        color = emptyBarColor,
                        topLeft = Offset(x, 0f),
                        size = Size(barWidth, maxBarHeight),
                        cornerRadius = CornerRadius(4.dp.toPx())
                    )

                    if (bar.distanceM > 0f) {
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(x, y),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(4.dp.toPx())
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Day labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                bars.forEach { bar ->
                    Text(
                        text = bar.dayLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Total steps if any
            if (totalSteps > 0) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = totalSteps.formatReadable() + " " + stringResource(R.string.stats_weekly_chart_steps_total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
    onNavigateToHistory: () -> Unit,
) {
    val summaryLabel = stringResource(R.string.stats_sum_title)
    val weekLabel = stringResource(R.string.stats_weekly_title)
    val wifiLabel = stringResource(R.string.stats_wifi_label)
    val historyLabel = stringResource(R.string.history_button_label)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionChip(
                onClick = onShowSummary,
                icon = Icons.Filled.Summarize,
                label = summaryLabel,
                modifier = Modifier.weight(1f)
            )
            ActionChip(
                onClick = onShowWeek,
                icon = Icons.Filled.CalendarMonth,
                label = weekLabel,
                modifier = Modifier.weight(1f)
            )
            ActionChip(
                onClick = onOpenWifi,
                icon = Icons.Filled.Wifi,
                label = wifiLabel,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionChip(
                onClick = onNavigateToHistory,
                icon = Icons.Filled.History,
                label = historyLabel,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ActionChip(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .height(56.dp)
            .clickable { onClick() }
            .semantics { contentDescription = label },
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private val tripTimeFormatter: DateTimeFormatter by lazy {
    DateTimeFormatter.ofPattern("HH:mm")
}

private val tripDateFormatter: DateTimeFormatter by lazy {
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
}

/**
 * Trip row displaying activity icon, time, duration, distance, and steps.
 */
@Composable
internal fun TripRow(
    trip: Trip,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val resources = context.resources
    val settings = remember { TrackerSettingsQuick.snapshot(context) }

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
            tripTimeFormatter.format(
                Instant.ofEpochMilli(trip.startTimeMs).atZone(ZoneId.systemDefault())
            )
        } else "--"
    }

    val tripIcon = remember(trip.primaryActivity) { activityIcon(trip.primaryActivity) }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .testTag("stats_trip_row"),
        shape = AppShapes.GlassCard
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Activity icon with colored background
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

            // Main content
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
                            text = "\u2022 $durationText",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

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
                    val steps = trip.steps ?: 0
                    if (steps > 0) {
                        MetricBadge(
                            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                            value = steps.formatReadable()
                        )
                    }
                    if (distanceText == null && steps == 0) {
                        Text(
                            text = stringResource(R.string.stats_session_subtitle_placeholder),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

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
 * Date header separator for grouping trips by day.
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
        else -> tripDateFormatter.format(sessionDate)
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
        Row(verticalAlignment = Alignment.CenterVertically) {
             Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)))
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
