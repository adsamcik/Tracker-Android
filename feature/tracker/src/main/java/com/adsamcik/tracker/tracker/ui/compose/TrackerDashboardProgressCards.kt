package com.adsamcik.tracker.tracker.ui.compose

import android.text.format.DateUtils
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Sailing
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.DailySummaryProvider
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsState
import com.adsamcik.tracker.shared.preferences.extension.formatDistance
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.tracker.R

/**
 * Today's Progress Card
 * 
 * Displays aggregated daily metrics (distance, steps, duration) when not tracking.
 * Redesigned to use a circular progress indicator for the daily goal and a more
 * prominent display of the primary metric (distance).
 */
@Composable
internal fun TodayProgressCard(
    isTracking: Boolean,
    settings: TrackerSettingsState,
    dailySummaryProvider: DailySummaryProvider,
    goalProgressProvider: GoalProgressProvider,
    modifier: Modifier = Modifier,
    onStartTrackingHint: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val resources = context.resources

    // StatusAndQuickStatsCard owns the active-tracking surface.
    if (isTracking) return

    val goalProgress by goalProgressProvider.goalProgressFlow.collectAsState()
    val readySteps = goalProgress.stepsToday as? QualifiedStepCount.Ready
    
    var todaySummary by remember { mutableStateOf<DailySummary?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    
    // Fetch on-demand when composition enters or when tracking state changes
    LaunchedEffect(isTracking) {
        isLoading = true
        try {
            todaySummary = dailySummaryProvider.fetchTodaySummary()
        } catch (_: Exception) {
            todaySummary = null
        }
        isLoading = false
    }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side: Stats
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.dashboard_today_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                if (isLoading) {
                    Box(modifier = Modifier.height(40.dp), contentAlignment = Alignment.CenterStart) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else if (todaySummary == null) {
                    Text(
                        text = stringResource(R.string.dashboard_today_empty_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                } else {
                    // todaySummary is guaranteed non-null in this branch
                    val summary = requireNotNull(todaySummary) { "todaySummary was null despite passing null check" }
                    
                    // Primary Metric: Distance
                    val distanceText = resources.formatDistance(
                        summary.totalDistanceM,
                        digits = if (summary.totalDistanceM >= 1000f) 1 else 0,
                        unit = settings.lengthSystem
                    )
                    
                    Text(
                        text = distanceText,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    // Secondary Metrics Grid
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Duration
                        Column {
                            Text(
                                text = stringResource(R.string.dashboard_today_duration),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                            )
                            Text(
                                text = summary.totalDurationMs.formatAsDuration(context),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        
                        // Steps
                        if (readySteps != null) {
                            Column {
                                Text(
                                    text = stringResource(R.string.dashboard_today_steps),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                                )
                                Text(
                                    text = readySteps.value.formatReadable(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
            
            // Right side: Goal Progress Ring
            if (!isLoading) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(start = 16.dp)
                ) {
                    GoalProgressRing(goalProgressProvider = goalProgressProvider)
                }
            }
        }
    }
}

/**
 * Goal Progress Ring
 * 
 * A circular progress indicator showing daily step goal progress.
 * Replaces the linear bar for a more modern look.
 */
@Composable
internal fun GoalProgressRing(
    goalProgressProvider: GoalProgressProvider,
    modifier: Modifier = Modifier
) {
    val goalProgress by goalProgressProvider.goalProgressFlow.collectAsState()
    
    if (!goalProgress.gamificationEnabled || goalProgress.goalSteps <= 0) return
    if (goalProgress.stepsToday !is QualifiedStepCount.Ready) return
    val progress = requireNotNull(goalProgress.progress)
    
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "goal_progress"
    )
    
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(72.dp)) {
        // Track
        CircularProgressIndicator(
            progress = { 1f },
            modifier = Modifier.fillMaxSize(),
            color = trackColor,
            strokeWidth = 6.dp,
            trackColor = Color.Transparent,
        )
        
        // Progress
        CircularProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxSize(),
            color = primaryColor,
            strokeWidth = 6.dp,
            trackColor = Color.Transparent,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        
        // Icon or Percentage inside
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.Star,
                contentDescription = stringResource(R.string.tracker_points_icon_desc),
                tint = primaryColor,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// region Phase 4d: RecentTripsCard

/**
 * Card showing up to 3 most recent trips from the session_segment table.
 * Only rendered when trips exist; hidden otherwise.
 */
@Composable
internal fun RecentTripsCard(
    trips: List<Trip>,
    settings: TrackerSettingsState,
    onTripClick: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (trips.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.dashboard_recent_trips),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            trips.forEach { trip ->
                key(trip.id) {
                    RecentTripRow(
                        trip = trip,
                        settings = settings,
                        onClick = if (onTripClick != null) {
                            { onTripClick(trip.id) }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
internal fun RecentTripRow(
    trip: Trip,
    settings: TrackerSettingsState,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resources = context.resources
    val icon = getTripIcon(trip.primaryActivity)
    val distanceText = resources.formatDistance(trip.distanceM, 1, settings.lengthSystem)
    val durationText = trip.durationMs.formatAsDuration(context)
    val timeText = DateUtils.getRelativeTimeSpanString(
        trip.startTimeMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()

    val rowModifier = if (onClick != null) {
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp)
    } else {
        modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 4.dp)
    }

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = distanceText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = durationText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

internal fun getTripIcon(primaryActivity: Int?): ImageVector {
    return when (primaryActivity) {
        -2 -> Icons.AutoMirrored.Filled.DirectionsWalk
        -3 -> Icons.AutoMirrored.Filled.DirectionsRun
        -4 -> Icons.AutoMirrored.Filled.DirectionsBike
        -5, -34 -> Icons.Filled.DirectionsCar
        -26 -> Icons.Filled.Sailing
        -31 -> Icons.Filled.Flight
        else -> Icons.Filled.Route
    }
}
