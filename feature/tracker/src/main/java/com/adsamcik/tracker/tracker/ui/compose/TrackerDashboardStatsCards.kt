package com.adsamcik.tracker.tracker.ui.compose

import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatTrackedSteps
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.shared.utils.extension.formatSpeed
import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.tracker.R

@Composable
internal fun StatusAndQuickStatsCard(
    isTracking: Boolean,
    sessionData: TrackerSession?,
    collectionData: CollectionData?,
    wallClockNowMillis: Long,
    onMapClick: () -> Unit,
    pathPoints: List<Location>? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resources = context.resources
    val settings = TrackerSettingsQuick.snapshot(context)
    var selectedTab by rememberSaveable { mutableStateOf(TrackingStatsTab.LIVE) }

    val currentSpeed = collectionData?.location?.speed
    val currentActivity = collectionData?.activity?.getGroupedActivityName(context)
    val sessionEnd = when {
        isTracking -> wallClockNowMillis
        sessionData != null && sessionData.end > sessionData.start -> sessionData.end
        else -> wallClockNowMillis
    }
    val durationMillis = if (sessionData != null) (sessionEnd - sessionData.start).coerceAtLeast(0L) else 0L
    val durationText = durationMillis.formatAsDuration(context)
    val isMoving = (currentSpeed ?: 0f) > 0.5f
    val pathMetricsAccumulator = remember(sessionData?.id) { PathMetricsAccumulator() }
    val pathMetrics = remember(pathPoints, pathMetricsAccumulator) {
        pathMetricsAccumulator.update(pathPoints)
    }
    val derivedDistanceMeters = pathMetrics.distanceMeters.toFloat()
    val distanceMeters = maxOf(sessionData?.distanceInM ?: 0f, derivedDistanceMeters)
    val distanceText = resources.formatDistance(
        distanceMeters,
        digits = if (distanceMeters >= 1000f) 1 else 0,
        unit = settings.lengthSystem
    )
    val avgSpeed = pathMetrics.movingAverageSpeedMps
    val avgSpeedText = resources.formatSpeed(context, avgSpeed, 1)
    val speedText = currentSpeed?.let { resources.formatSpeed(context, it.toDouble(), 1) } ?: "—"
    val altitudeText = collectionData?.location?.altitude?.let {
        resources.formatDistance(it.toFloat(), 0, settings.lengthSystem)
    } ?: "—"
    val accuracyText = collectionData?.location?.horizontalAccuracy?.let {
        "±${resources.formatDistance(it, 0, settings.lengthSystem)}"
    } ?: "—"
    val stepCounterSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)
    val stepsText = sessionData?.steps?.formatTrackedSteps(stepCounterSupported) ?: "—"
    val wifiText = collectionData?.wifi?.inRange?.size?.takeIf { it > 0 }?.toString() ?: "—"
    val cellText = collectionData?.cell?.totalCount?.takeIf { it > 0 }?.toString() ?: "—"
    val activityText = currentActivity ?: "—"

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        onClick = onMapClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Status + Map Arrow
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Pulsing recording dot
                    val infiniteTransition = rememberInfiniteTransition(label = "recording_dot")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.2f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "alpha"
                    )
                    
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .alpha(alpha)
                            .clip(MaterialTheme.shapes.extraLarge)
                            .background(MaterialTheme.colorScheme.error)
                    )
                    Text(
                        text = stringResource(R.string.notification_tracking_active),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.tracker_go_to_map),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Primary Metric Area with animated values
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                if (isMoving && currentSpeed != null) {
                    // Moving: Speed is Primary
                    val speedText = resources.formatSpeed(context, currentSpeed.toDouble(), 1)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.speed_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                        PulseOnChange(key = speedText) {
                            AnimatedStatValue(
                                value = speedText,
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                } else {
                    // Stopped/Idle: Duration is Primary
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.duration_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                        AnimatedStatValue(
                            value = durationText,
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            TabRow(selectedTabIndex = TrackingStatsTab.entries.indexOf(selectedTab)) {
                TrackingStatsTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(text = stringResource(tab.titleRes)) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            when (selectedTab) {
                TrackingStatsTab.LIVE -> {
                    TrackingStatRow(
                        first = stringResource(R.string.speed_title) to speedText,
                        second = stringResource(R.string.tracker_average_label) to avgSpeedText,
                        third = stringResource(R.string.tracker_accuracy_label) to accuracyText
                    )
                }

                TrackingStatsTab.ROUTE -> {
                    TrackingStatRow(
                        first = stringResource(R.string.tracker_distance_title) to distanceText,
                        second = stringResource(R.string.duration_title) to durationText,
                        third = stringResource(R.string.altitude_title) to altitudeText
                    )
                }

                TrackingStatsTab.ACTIVITY -> {
                    TrackingStatRow(
                        first = stringResource(R.string.tracker_activity_title) to activityText,
                        second = stringResource(R.string.tracker_steps_title) to stepsText,
                        third = stringResource(R.string.tracker_accuracy_label) to accuracyText
                    )
                    Spacer(Modifier.height(12.dp))
                    TrackingTechnicalRow(
                        wifiText = wifiText,
                        cellText = cellText,
                        coordinatesText = collectionData?.location?.let { location ->
                            "${Assist.coordinateToString(location.latitude)}, ${Assist.coordinateToString(location.longitude)}"
                        }
                    )
                }
            }
        }
    }
}

internal enum class TrackingStatsTab(val titleRes: Int) {
    LIVE(R.string.tracker_live_tab),
    ROUTE(R.string.tracker_route_tab),
    ACTIVITY(R.string.tracker_activity_tab)
}

@Composable
private fun TrackingStatRow(
    first: Pair<String, String>,
    second: Pair<String, String>,
    third: Pair<String, String>
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        CompactStatItem(
            label = first.first,
            value = first.second,
            modifier = Modifier.weight(1f)
        )
        CompactStatItem(
            label = second.first,
            value = second.second,
            modifier = Modifier.weight(1f)
        )
        CompactStatItem(
            label = third.first,
            value = third.second,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TrackingTechnicalRow(
    wifiText: String,
    cellText: String,
    coordinatesText: String?
) {
    if (wifiText == "—" && cellText == "—" && coordinatesText == null) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (wifiText != "—") {
            TechnicalStatItem(
                icon = Icons.Filled.Wifi,
                text = wifiText
            )
        }
        if (cellText != "—") {
            TechnicalStatItem(
                icon = Icons.Filled.SignalCellularAlt,
                text = cellText
            )
        }
        if (coordinatesText != null) {
            Text(
                text = coordinatesText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ActiveStatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun CompactStatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    animated: Boolean = true
) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
        )
        if (animated) {
            AnimatedContent(
                targetState = value,
                label = "compact_stat",
                transitionSpec = {
                    (fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.95f))
                        .togetherWith(fadeOut(animationSpec = tween(100)))
                }
            ) { targetValue ->
                Text(
                    text = targetValue,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun TechnicalStatItem(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f),
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
        )
    }
}

/**
 * AnimatedStatValue - Displays a value with smooth counting animation when it changes.
 * Uses AnimatedContent with vertical slide for a slot-machine effect.
 */
@Composable
private fun AnimatedStatValue(
    value: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.displayMedium,
    fontWeight: FontWeight = FontWeight.Bold,
    color: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    AnimatedContent(
        targetState = value,
        label = "stat_value",
        transitionSpec = {
            (slideInVertically { height -> height / 4 } + fadeIn(animationSpec = tween(300)))
                .togetherWith(slideOutVertically { height -> -height / 4 } + fadeOut(animationSpec = tween(150)))
        },
        modifier = modifier
    ) { targetValue ->
        Text(
            text = targetValue,
            style = style,
            fontWeight = fontWeight,
            color = color
        )
    }
}

/**
 * PulseOnChange - Wraps content and adds a subtle scale pulse when value changes.
 */
@Composable
private fun PulseOnChange(
    key: Any,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scale = remember { Animatable(1f) }
    
    LaunchedEffect(key) {
        scale.animateTo(
            targetValue = 1.08f,
            animationSpec = tween(100, easing = FastOutSlowInEasing)
        )
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    }
    
    Box(
        modifier = modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
    ) {
        content()
    }
}
