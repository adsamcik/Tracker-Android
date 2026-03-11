package com.adsamcik.tracker.app.settings.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.common.ui.BatteryImpactIndicator
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import kotlin.math.abs

@Composable
fun TrackingPresetSelector(
    selectedPreset: TrackingPreset,
    currentBatteryImpact: BatteryImpact,
    onPresetSelected: (TrackingPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = TrackingPreset.entries
    val listState = rememberLazyListState()
    val activePresetIndex by remember(listState) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) {
                0
            } else {
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                visibleItems.minByOrNull { item ->
                    abs((item.offset + item.size / 2) - viewportCenter)
                }?.index ?: listState.firstVisibleItemIndex
            }
        }
    }

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

        LazyRow(
            state = listState,
            contentPadding = PaddingValues(end = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(presets) { preset ->
                val selected = preset == selectedPreset
                Card(
                    onClick = { onPresetSelected(preset) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                    ),
                    border = BorderStroke(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.fillParentMaxWidth(0.72f),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
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
                        BatteryImpactIndicator(
                            impact = preset.batteryImpact(currentBatteryImpact),
                            compact = true,
                        )
                    }
                }
            }
        }

        PresetScrollIndicator(
            count = presets.size,
            activeIndex = activePresetIndex,
        )
    }
}

@Composable
private fun PresetScrollIndicator(
    count: Int,
    activeIndex: Int,
    modifier: Modifier = Modifier,
) {
    if (count <= 1) return

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val selected = index == activeIndex
            Surface(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = if (selected) 18.dp else 8.dp, height = 8.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            ) {}
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
