package com.adsamcik.tracker.app.onboarding.ui.steps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.common.ui.BatteryImpactIndicator
import com.adsamcik.tracker.app.settings.data.TrackingPolicyPreset
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard

/**
 * Step 2 – How to Track.
 *
 * Two sections:
 * 1. Auto-tracking mode (disabled / on foot / in motion)
 * 2. Tracking preset (battery saver / balanced / high precision)
 */
@Composable
fun HowToTrackStep(
    autoTrackingMode: Int,
    trackingPreset: TrackingPolicyPreset,
    onAutoTrackingModeChange: (Int) -> Unit,
    onPresetChange: (TrackingPolicyPreset) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SetupStepScaffold(
        actionText = stringResource(R.string.button_continue),
        actionTestTag = "setup_cta_how_to_track",
        onAction = onContinue,
        modifier = modifier,
        bottomPaddingTestTag = "setup_how_to_track_scroll_bottom_padding",
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

        AutoTrackingModeCard(
            selected = autoTrackingMode == 0,
            title = stringResource(R.string.setup_auto_tracking_disabled),
            description = stringResource(R.string.setup_auto_tracking_disabled_desc),
            onClick = { onAutoTrackingModeChange(0) },
        )

        Spacer(modifier = Modifier.height(8.dp))

        AutoTrackingModeCard(
            selected = autoTrackingMode == 1,
            title = stringResource(R.string.setup_auto_tracking_on_foot),
            description = stringResource(R.string.setup_auto_tracking_on_foot_desc),
            onClick = { onAutoTrackingModeChange(1) },
        )

        Spacer(modifier = Modifier.height(8.dp))

        AutoTrackingModeCard(
            selected = autoTrackingMode == 2,
            title = stringResource(R.string.setup_auto_tracking_in_motion),
            description = stringResource(R.string.setup_auto_tracking_in_motion_desc),
            onClick = { onAutoTrackingModeChange(2) },
        )

        Spacer(modifier = Modifier.height(28.dp))

        // --- Tracking preset ---
        Text(
            text = stringResource(R.string.setup_preset_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(12.dp))

        TrackingPolicyPreset.entries.filter { it != TrackingPolicyPreset.BALANCED || true }
            .forEach { preset ->
                PresetCard(
                    selected = trackingPreset == preset,
                    preset = preset,
                    onClick = { onPresetChange(preset) },
                    modifier = Modifier.testTag(preset.testTag),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
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
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.shapes.medium,
                ) else Modifier,
            )
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
}

@Composable
private fun PresetCard(
    selected: Boolean,
    preset: TrackingPolicyPreset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.shapes.medium,
                ) else Modifier,
            )
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
}

private val TrackingPolicyPreset.testTag: String
    get() = "setup_preset_${name.lowercase()}"
