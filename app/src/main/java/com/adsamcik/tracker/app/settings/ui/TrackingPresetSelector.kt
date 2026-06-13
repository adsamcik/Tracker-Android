package com.adsamcik.tracker.app.settings.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.common.ui.BatteryImpactIndicator
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset

/**
 * Lets the user pick a tracking profile to build from. Selecting a preset applies its
 * configuration as a starting point; fine-tuning any control in the advanced section then
 * turns the active profile into [TrackingPreset.CUSTOM] while keeping the preset's values.
 */
@Composable
fun TrackingPresetSelector(
    selectedPreset: TrackingPreset,
    currentBatteryImpact: BatteryImpact,
    onPresetSelected: (TrackingPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = listOf(
        TrackingPreset.HIGH_ACCURACY,
        TrackingPreset.BALANCED,
        TrackingPreset.POWER_SAVE,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.tracking_preset_selector_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.tracking_preset_selector_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { preset ->
                PresetOptionCard(
                    preset = preset,
                    selected = preset == selectedPreset,
                    batteryImpact = preset.batteryImpact(currentBatteryImpact),
                    onClick = { onPresetSelected(preset) },
                )
            }
        }

        if (selectedPreset == TrackingPreset.CUSTOM) {
            CustomProfileIndicator(currentBatteryImpact = currentBatteryImpact)
        }
    }
}

@Composable
private fun PresetOptionCard(
    preset: TrackingPreset,
    selected: Boolean,
    batteryImpact: BatteryImpact,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(preset.titleRes()),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(preset.descriptionRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BatteryImpactIndicator(
                impact = batteryImpact,
                compact = true,
            )
        }
    }
}

@Composable
private fun CustomProfileIndicator(
    currentBatteryImpact: BatteryImpact,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.tracking_preset_custom_name),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = stringResource(R.string.tracking_preset_customized_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            BatteryImpactIndicator(
                impact = currentBatteryImpact,
                compact = true,
            )
        }
    }
}

private fun TrackingPreset.titleRes(): Int = when (this) {
    TrackingPreset.HIGH_ACCURACY -> R.string.tracking_preset_high_accuracy_name
    TrackingPreset.BALANCED -> R.string.tracking_preset_balanced_name
    TrackingPreset.POWER_SAVE -> R.string.tracking_preset_power_save_name
    TrackingPreset.CUSTOM -> R.string.tracking_preset_custom_name
}

private fun TrackingPreset.descriptionRes(): Int = when (this) {
    TrackingPreset.HIGH_ACCURACY -> R.string.tracking_preset_high_accuracy_description
    TrackingPreset.BALANCED -> R.string.tracking_preset_balanced_description
    TrackingPreset.POWER_SAVE -> R.string.tracking_preset_power_save_description
    TrackingPreset.CUSTOM -> R.string.tracking_preset_custom_description
}

private fun TrackingPreset.batteryImpact(current: BatteryImpact): BatteryImpact = when (this) {
    TrackingPreset.HIGH_ACCURACY -> BatteryImpact.HIGH
    TrackingPreset.BALANCED -> BatteryImpact.MODERATE
    TrackingPreset.POWER_SAVE -> BatteryImpact.LOW
    TrackingPreset.CUSTOM -> current
}
