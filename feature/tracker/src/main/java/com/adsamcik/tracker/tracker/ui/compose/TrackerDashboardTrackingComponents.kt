package com.adsamcik.tracker.tracker.ui.compose

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsState
import com.adsamcik.tracker.shared.preferences.extension.formatDistance
import com.adsamcik.tracker.shared.preferences.extension.formatSpeed
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.data.collection.TrackerCollectionSnapshot
import kotlinx.coroutines.delay

internal sealed class TrackingComponent(val key: String) {
    data object Location : TrackingComponent("location")
    data object Activity : TrackingComponent("activity")
    data object Wifi : TrackingComponent("wifi")
    data object Cell : TrackingComponent("cell")
}

@Immutable
internal data class TrackingComponentModel(
    val component: TrackingComponent,
    val enabled: Boolean,
    val metrics: ComponentMetrics?
)

@Immutable
internal data class ComponentMetrics(
    val primary: ComponentMetric?,
    val secondary: List<ComponentMetric> = emptyList(),
    val status: String? = null
)

@Immutable
internal data class ComponentMetric(
    val label: String,
    val value: String,
    val copyableValue: String? = null, // Both formatted + raw for clipboard
    val onClick: (() -> Unit)? = null
)

@Composable
internal fun ComponentCard(
    component: TrackingComponent,
    enabled: Boolean,
    metrics: ComponentMetrics?,
    onClick: (() -> Unit)?
) {
    // Use surfaceContainerLow for enabled (better visibility) and surfaceVariant for disabled
    val containerColor = if (enabled) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentAlpha = if (enabled) 1f else 0.6f
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    val title = when (component) {
        TrackingComponent.Location -> stringResource(R.string.settings_location_enabled_title)
        TrackingComponent.Activity -> stringResource(R.string.tracker_activity_title)
        TrackingComponent.Wifi -> stringResource(R.string.settings_wifi_enabled_title)
        TrackingComponent.Cell -> stringResource(R.string.settings_cell_enabled_title)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("component_card_${component.key}"),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = onClick != null) { onClick?.invoke() }
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .alpha(0.9f)
                )

                if (!enabled) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.description_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("component_settings_icon").size(16.dp)
                    )
                }
            }

            metrics?.let { details ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .alpha(contentAlpha),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val scope = rememberCoroutineScope()
                    details.primary?.let {
                        ComponentMetricText(
                            metric = it,
                            emphasize = true,
                            onCopy = if (enabled && it.copyableValue != null) {
                                {
                                    copyToClipboard(context, haptics, it.label, it.copyableValue)
                                }
                            } else null
                        )
                    }
                    
                    if (details.secondary.isNotEmpty()) {
                        // Use a FlowRow or simple Column for secondary metrics
                        details.secondary.forEach {
                            ComponentMetricText(
                                metric = it,
                                emphasize = false,
                                onCopy = if (enabled && it.copyableValue != null) {
                                    {
                                        copyToClipboard(context, haptics, it.label, it.copyableValue)
                                    }
                                } else null
                            )
                        }
                    }
                    
                    details.status?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } ?: Row(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .alpha(contentAlpha),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (enabled) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.tracker_component_waiting_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.tracker_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ComponentMetricText(
    metric: ComponentMetric,
    emphasize: Boolean,
    onCopy: (() -> Unit)? = null
) {
    var justCopied by remember { mutableStateOf(false) }
    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(2000)
            justCopied = false
        }
    }

    val interactionModifier = if (onCopy != null) {
        Modifier.combinedClickable(
            onClick = { metric.onClick?.invoke() },
            onLongClick = {
                onCopy()
                justCopied = true
            }
        )
    } else if (metric.onClick != null) {
        Modifier.clickable { metric.onClick.invoke() }
    } else {
        Modifier
    }

    val semanticsModifier = Modifier.semantics {
        contentDescription = "${metric.label}: ${metric.value}"
        if (onCopy != null) {
            onClick(label = "Copy ${metric.label}") {
                onCopy()
                justCopied = true
                true
            }
        } else if (metric.onClick != null) {
             onClick(label = "Toggle format") {
                 metric.onClick.invoke()
                 true
             }
        }
    }

    Column(modifier = interactionModifier.then(semanticsModifier)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = metric.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
            )
            if (onCopy != null) {
                AnimatedContent(targetState = justCopied, label = "copy_icon") { copied ->
                    if (copied) {
                         Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.description_copied),
                            modifier = Modifier.size(10.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.description_copy),
                            modifier = Modifier
                                .size(10.dp)
                                .alpha(0.4f),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        
        AnimatedContent(
            targetState = metric.value,
            transitionSpec = {
                (slideInVertically { height -> height } + fadeIn()).togetherWith(slideOutVertically { height -> -height } + fadeOut())
            },
            label = "metric_value"
        ) { targetValue ->
            Text(
                text = targetValue,
                style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

internal fun buildLocationMetrics(
    context: Context,
    collectionData: TrackerCollectionSnapshot?,
    settings: TrackerSettingsState,
    useDecimalDegrees: Boolean,
    onToggleFormat: () -> Unit
): ComponentMetrics? {
    val location = collectionData?.location ?: return null
    val resources = context.resources
    val primary = location.horizontalAccuracy?.takeIf { it > 0f }?.let { accuracy ->
        val formattedAccuracy = resources.formatDistance(
            distanceInMeters = accuracy,
            digits = if (accuracy >= 100f) 0 else 1,
            unit = settings.lengthSystem
        )
        ComponentMetric(
            label = context.getString(R.string.horizontal_accuracy_title),
            value = formattedAccuracy,
            copyableValue = "$formattedAccuracy\n${accuracy}m"
        )
    }

    val secondary = buildList {
        // Add coordinates first
        val latFormatted = if (useDecimalDegrees) location.latitude.toString() else Assist.coordinateToString(location.latitude)
        val lonFormatted = if (useDecimalDegrees) location.longitude.toString() else Assist.coordinateToString(location.longitude)
        add(
            ComponentMetric(
                label = context.getString(R.string.coordinates_title),
                value = "$latFormatted, $lonFormatted",
                copyableValue = "Latitude: $latFormatted (${location.latitude})\nLongitude: $lonFormatted (${location.longitude})",
                onClick = onToggleFormat
            )
        )
        
        location.speed?.takeIf { it > 0f }?.let { speed ->
            val formattedSpeed = resources.formatSpeed(context, speed.toDouble(), 1)
            add(
                ComponentMetric(
                    label = context.getString(R.string.speed_title),
                    value = formattedSpeed,
                    copyableValue = "$formattedSpeed\n${speed}m/s"
                )
            )
        }
        collectionData.androidModelMslAltitudeM?.let { altitude ->
            val formattedAltitude = resources.formatDistance(altitude, 0, settings.lengthSystem)
            add(
                ComponentMetric(
                    label = context.getString(R.string.altitude_title),
                    value = formattedAltitude,
                    copyableValue = "$formattedAltitude\n${altitude}m"
                )
            )
        }
    }

    val status = formatRelativeUpdate(context, location.time)
    if (primary == null && secondary.isEmpty() && status == null) return null
    return ComponentMetrics(primary = primary, secondary = secondary, status = status)
}

internal fun buildActivityMetrics(
    context: Context,
    collectionData: TrackerCollectionSnapshot?,
): ComponentMetrics? {
    val activity = collectionData?.activity ?: return null
    val activityName = activity.getGroupedActivityName(context)
    val primary = ComponentMetric(
        label = context.getString(R.string.tracker_activity_title),
        value = activityName,
        copyableValue = "$activityName (${activity.confidence}% confidence)"
    )
    val secondary = listOf(
        ComponentMetric(
            label = context.getString(R.string.tracker_activity_confidence),
            value = "${activity.confidence}%",
            copyableValue = "Confidence: ${activity.confidence}%"
        )
    )
    val status = formatRelativeUpdate(context, collectionData.time)
    return ComponentMetrics(primary = primary, secondary = secondary, status = status)
}

internal fun buildWifiMetrics(
    context: Context,
    collectionData: TrackerCollectionSnapshot?,
): ComponentMetrics? {
    val wifi = collectionData?.wifi ?: return null
    val count = wifi.inRange.size
    val wifiCountText = context.resources.getQuantityString(R.plurals.tracker_wifi_networks_value, count, count)
    val primary = ComponentMetric(
        label = context.getString(R.string.settings_wifi_enabled_title),
        value = wifiCountText,
        copyableValue = "$count WiFi networks in range"
    )
    val strongest = wifi.inRange.maxByOrNull { it.level }
    val secondary = strongest?.let {
        val signalText = context.getString(R.string.tracker_signal_strength_value, it.level)
        listOf(
            ComponentMetric(
                label = context.getString(R.string.tracker_signal_strength_title),
                value = signalText,
                copyableValue = "Strongest signal: ${it.level} dBm\nSSID: ${it.ssid ?: "Hidden"}"
            )
        )
    } ?: emptyList()
    val status = formatRelativeUpdate(context, wifi.time)
    return ComponentMetrics(primary = primary, secondary = secondary, status = status)
}

internal fun buildCellMetrics(
    context: Context,
    collectionData: TrackerCollectionSnapshot?,
): ComponentMetrics? {
    val cell = collectionData?.cell ?: return null
    val cellCountText = context.getString(R.string.cell_count_value, cell.totalCount)
    val primary = ComponentMetric(
        label = context.getString(R.string.cell_count_title),
        value = cellCountText,
        copyableValue = "Total cells: ${cell.totalCount}"
    )
    val strongest = cell.registeredCells.maxByOrNull { it.dbm }
    val secondary = strongest?.dbm?.takeIf { it != 0 }?.let { dbm ->
        val operatorName = strongest.networkOperator.name?.takeIf { it.isNotBlank() }
            ?: "${strongest.networkOperator.mcc}-${strongest.networkOperator.mnc}"
        val cellType = context.getString(strongest.type.nameRes)
        val cellValueText = context.getString(R.string.cell_current_single_value, cellType, operatorName, dbm)
        listOf(
            ComponentMetric(
                label = context.getString(R.string.cell_current_title),
                value = cellValueText,
                copyableValue = "Type: $cellType\nOperator: $operatorName\nSignal: ${dbm}dBm"
            )
        )
    } ?: emptyList()
    val status = formatRelativeUpdate(context, collectionData.time)
    return ComponentMetrics(primary = primary, secondary = secondary, status = status)
}

internal fun formatRelativeUpdate(context: Context, timestamp: Long?): String? {
    if (timestamp == null || timestamp <= 0L) return null
    val relative = DateUtils.getRelativeTimeSpanString(
        timestamp,
        System.currentTimeMillis(),
        DateUtils.SECOND_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()
    return context.getString(R.string.last_update_value, relative)
}
