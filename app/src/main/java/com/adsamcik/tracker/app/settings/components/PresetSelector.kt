package com.adsamcik.tracker.app.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Preset selector component for tracking presets.
 * 
 * @param selectedPreset Currently selected preset
 * @param onPresetSelected Callback when preset changes
 * @param showCustomBadge Whether to show custom badge
 * @param modifier Optional modifier
 */
@Composable
fun PresetSelector(
    selectedPreset: TrackingPreset,
    onPresetSelected: (TrackingPreset) -> Unit,
    showCustomBadge: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .selectableGroup()
        ) {
            Text(
                text = stringResource(com.adsamcik.tracker.tracker.R.string.tracking_preset_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            PresetOption(
                preset = TrackingPreset.BATTERY_SAVER,
                selected = selectedPreset == TrackingPreset.BATTERY_SAVER,
                onClick = { onPresetSelected(TrackingPreset.BATTERY_SAVER) }
            )
            
            PresetOption(
                preset = TrackingPreset.BALANCED,
                selected = selectedPreset == TrackingPreset.BALANCED,
                onClick = { onPresetSelected(TrackingPreset.BALANCED) }
            )
            
            PresetOption(
                preset = TrackingPreset.HIGH_PRECISION,
                selected = selectedPreset == TrackingPreset.HIGH_PRECISION,
                onClick = { onPresetSelected(TrackingPreset.HIGH_PRECISION) }
            )
            
            if (showCustomBadge && selectedPreset == TrackingPreset.CUSTOM) {
                Text(
                    text = stringResource(com.adsamcik.tracker.tracker.R.string.tracking_preset_custom_badge),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, start = 40.dp)
                )
            }
        }
    }
}

@Composable
private fun PresetOption(
    preset: TrackingPreset,
    selected: Boolean,
    onClick: () -> Unit
) {
    val (titleRes, descRes) = when (preset) {
        TrackingPreset.BATTERY_SAVER -> com.adsamcik.tracker.tracker.R.string.tracking_preset_battery_saver_title to 
                                         com.adsamcik.tracker.tracker.R.string.tracking_preset_battery_saver_desc
        TrackingPreset.BALANCED -> com.adsamcik.tracker.tracker.R.string.tracking_preset_balanced_title to 
                                   com.adsamcik.tracker.tracker.R.string.tracking_preset_balanced_desc
        TrackingPreset.HIGH_PRECISION -> com.adsamcik.tracker.tracker.R.string.tracking_preset_high_precision_title to 
                                         com.adsamcik.tracker.tracker.R.string.tracking_preset_high_precision_desc
        TrackingPreset.CUSTOM -> throw IllegalArgumentException("CUSTOM preset should not be directly selectable")
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = null // Handled by Row's selectable
        )
        Column {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
