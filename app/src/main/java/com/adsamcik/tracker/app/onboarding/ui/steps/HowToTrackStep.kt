package com.adsamcik.tracker.app.onboarding.ui.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.common.ui.BatteryImpactIndicator
import com.adsamcik.tracker.app.onboarding.data.AutoTrackingMode
import com.adsamcik.tracker.app.settings.data.TrackingPolicyPreset
import com.adsamcik.tracker.shared.utils.style.compose.RadioCard

/**
 * Step 2 – How to Track.
 *
 * Two sections:
 * 1. Auto-tracking mode (disabled / on foot / in motion)
 * 2. Tracking preset (battery saver / balanced / high precision)
 */
@Composable
fun HowToTrackStep(
    autoTrackingMode: AutoTrackingMode,
    trackingPreset: TrackingPolicyPreset,
    onAutoTrackingModeChange: (AutoTrackingMode) -> Unit,
    onPresetChange: (TrackingPolicyPreset) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.setup_how_to_track_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.setup_how_to_track_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // --- Auto-tracking mode ---
        Text(
            text = stringResource(R.string.setup_auto_tracking_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AutoTrackingModeCard(
                selected = autoTrackingMode == AutoTrackingMode.Disabled,
                title = stringResource(R.string.setup_auto_tracking_disabled),
                description = stringResource(R.string.setup_auto_tracking_disabled_desc),
                onClick = { onAutoTrackingModeChange(AutoTrackingMode.Disabled) },
                modifier = Modifier.testTag("auto_tracking_disabled_card"),
            )

            AutoTrackingModeCard(
                selected = autoTrackingMode == AutoTrackingMode.OnFoot,
                title = stringResource(R.string.setup_auto_tracking_on_foot),
                description = stringResource(R.string.setup_auto_tracking_on_foot_desc),
                onClick = { onAutoTrackingModeChange(AutoTrackingMode.OnFoot) },
                modifier = Modifier.testTag("auto_tracking_on_foot_card"),
            )

            AutoTrackingModeCard(
                selected = autoTrackingMode == AutoTrackingMode.InMotion,
                title = stringResource(R.string.setup_auto_tracking_in_motion),
                description = stringResource(R.string.setup_auto_tracking_in_motion_desc),
                onClick = { onAutoTrackingModeChange(AutoTrackingMode.InMotion) },
                modifier = Modifier.testTag("auto_tracking_in_motion_card"),
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // --- Tracking preset ---
        Text(
            text = stringResource(R.string.setup_preset_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TrackingPolicyPreset.entries.forEach { preset ->
                PresetCard(
                    selected = trackingPreset == preset,
                    preset = preset,
                    onClick = { onPresetChange(preset) },
                    modifier = Modifier.testTag(preset.testTag()),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun AutoTrackingModeCard(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RadioCard(
        selected = selected,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth(),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PresetCard(
    selected: Boolean,
    preset: TrackingPolicyPreset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RadioCard(
        selected = selected,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth(),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(preset.nameRes),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(preset.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(4.dp))

            BatteryImpactIndicator(
                impact = preset.batteryImpact,
                compact = true,
            )
        }
    }
}

private fun TrackingPolicyPreset.testTag(): String = when (this) {
    TrackingPolicyPreset.BATTERY_SAVER -> "tracking_detail_battery_saver_card"
    TrackingPolicyPreset.BALANCED -> "tracking_detail_balanced_card"
    TrackingPolicyPreset.HIGH_PRECISION -> "tracking_detail_high_precision_card"
}
