package com.adsamcik.tracker.app.settings.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.common.ui.BatteryImpactIndicator
import com.adsamcik.tracker.app.settings.data.TrackingPolicyPreset

/**
 * Tracking policy preset selector with progressive disclosure.
 * Displays three presets (Battery Saver / Balanced / High Precision) with battery indicators.
 * Optional "Learn more" expansion reveals detailed settings for each preset.
 *
 * Follows Apple-style philosophy:
 * - Opinionated defaults prominently displayed
 * - Battery impact always visible
 * - Advanced details hidden by default (progressive disclosure)
 * - One-tap to apply preset
 *
 * @param selectedPreset Currently selected preset (or null for custom)
 * @param onPresetSelected Callback when user selects a preset
 * @param showDetails Whether to show "Learn more" expansion (default false)
 * @param modifier Optional modifier for the container
 */
@Composable
fun TrackingPolicySelector(
    selectedPreset: TrackingPolicyPreset?,
    onPresetSelected: (TrackingPolicyPreset) -> Unit,
    modifier: Modifier = Modifier,
    showDetails: Boolean = false
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.tracking_policy_selector_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = stringResource(R.string.tracking_policy_selector_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Custom preset indicator (if settings don't match any preset)
        if (selectedPreset == null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text = stringResource(R.string.tracking_policy_custom_badge),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Preset cards
        TrackingPolicyPreset.values().forEach { preset ->
            PresetCard(
                preset = preset,
                isSelected = selectedPreset == preset,
                onClick = { onPresetSelected(preset) },
                showDetails = showDetails
            )
        }

        // Estimated tracking duration disclaimer
        if (selectedPreset != null) {
            Text(
                text = pluralStringResource(
                    R.plurals.tracking_policy_estimated_duration,
                    selectedPreset.settings.estimatedTrackingHours(),
                    selectedPreset.settings.estimatedTrackingHours(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

/**
 * Card representing a single tracking policy preset
 */
@Composable
private fun PresetCard(
    preset: TrackingPolicyPreset,
    isSelected: Boolean,
    onClick: () -> Unit,
    showDetails: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceContainer
        ),
        border = if (isSelected)
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Main preset info
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = onClick
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(preset.nameRes),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = stringResource(preset.descriptionRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    BatteryImpactIndicator(
                        impact = preset.batteryImpact,
                        compact = true
                    )
                }

                // Expand/collapse details button (if showDetails enabled)
                if (showDetails) {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) 
                                stringResource(R.string.collapse_details) 
                            else 
                                stringResource(R.string.expand_details),
                            tint = if (isSelected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Detailed settings (expandable)
            if (showDetails) {
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier.padding(start = 48.dp, top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.tracking_policy_details_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurface
                        )

                        PresetDetailRow(
                            stringResource(R.string.preset_detail_location_precision),
                            if (preset.settings.requirePreciseLocation) 
                                stringResource(R.string.precise)
                            else 
                                stringResource(R.string.approximate),
                            isSelected
                        )

                        PresetDetailRow(
                            stringResource(R.string.preset_detail_min_distance),
                            "${preset.settings.minDistanceMeters}m",
                            isSelected
                        )

                        PresetDetailRow(
                            stringResource(R.string.preset_detail_min_time),
                            "${preset.settings.minTimeSeconds}s",
                            isSelected
                        )

                        PresetDetailRow(
                            stringResource(R.string.preset_detail_accuracy),
                            "${preset.settings.requiredAccuracyMeters}m",
                            isSelected
                        )

                        PresetDetailRow(
                            stringResource(R.string.preset_detail_sensors),
                            buildSensorsList(preset.settings),
                            isSelected
                        )
                    }
                }
            }
        }
    }
}

/**
 * Single detail row in expanded preset details
 */
@Composable
private fun PresetDetailRow(
    label: String,
    value: String,
    isSelected: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (isSelected)
                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            else
                MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (isSelected)
                MaterialTheme.colorScheme.onPrimaryContainer
            else
                MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Build human-readable sensor list from settings
 */
private fun buildSensorsList(settings: com.adsamcik.tracker.app.settings.data.TrackingPresetSettings): String {
    val sensors = mutableListOf<String>()
    if (settings.activityEnabled) sensors.add("Activity")
    if (settings.stepsEnabled) sensors.add("Steps")
    if (settings.wifiEnabled) sensors.add("WiFi")
    if (settings.cellEnabled) sensors.add("Cell")
    return if (sensors.isEmpty()) "None" else sensors.joinToString(", ")
}
