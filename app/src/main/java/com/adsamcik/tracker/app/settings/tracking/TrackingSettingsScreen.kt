package com.adsamcik.tracker.app.settings.tracking

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.common.ui.BatteryImpactIndicator
import com.adsamcik.tracker.app.settings.TrackingSettingsUiState
import com.adsamcik.tracker.app.settings.TrackingSettingsViewModel
import com.adsamcik.tracker.app.settings.components.ExpandableSection
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.app.settings.components.SliderSettingsItemWithHelp
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItemWithHelp
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.shared.utils.style.compose.RadioCard
import kotlin.math.abs

@Composable
fun TrackingSettingsScreen() {
    val context = LocalContext.current
    val trackingVm: TrackingSettingsViewModel = hiltViewModel()

    // Single consolidated state
    val uiState by trackingVm.uiState.collectAsState()

    TrackingSettingsContent(
        uiState = uiState,
        onPresetSelected = { trackingVm.applyPreset(it) },
        onTransitionDetectionChanged = { trackingVm.setTransitionDetectionEnabled(it) },
        onNotificationStyledChanged = { trackingVm.setNotificationStyled(it) },
        onSkiDetectionChanged = { trackingVm.setSkiDetectionEnabled(it) },
        onMinDistanceChanged = { trackingVm.setMinDistance(it) },
        onMinTimeChanged = { trackingVm.setMinTime(it) },
        onRequiredAccuracyChanged = { trackingVm.setRequiredAccuracy(it) },
        onLocationEnabledChanged = { trackingVm.setLocationEnabled(it) },
        onActivityEnabledChanged = { trackingVm.setActivityEnabled(it) },
        onStepsEnabledChanged = { trackingVm.setStepsEnabled(it) },
        onWifiEnabledChanged = { trackingVm.setWifiEnabled(it) },
        onWifiNetworkEnabledChanged = { trackingVm.setWifiNetworkEnabled(it) },
        onWifiLocationCountEnabledChanged = { trackingVm.setWifiLocationCountEnabled(it) },
        onCellEnabledChanged = { trackingVm.setCellEnabled(it) },
        onNotificationCustomize = {
            context.startActivity(
                android.content.Intent(context, com.adsamcik.tracker.tracker.notification.NotificationManagementActivity::class.java)
            )
        },
    )
}

@Composable
internal fun TrackingSettingsContent(
    uiState: TrackingSettingsUiState,
    onPresetSelected: (TrackingPreset) -> Unit = {},
    onTransitionDetectionChanged: (Boolean) -> Unit = {},
    onNotificationStyledChanged: (Boolean) -> Unit = {},
    onSkiDetectionChanged: (Boolean) -> Unit = {},
    onMinDistanceChanged: (Int) -> Unit = {},
    onMinTimeChanged: (Int) -> Unit = {},
    onRequiredAccuracyChanged: (Int) -> Unit = {},
    onLocationEnabledChanged: (Boolean) -> Unit = {},
    onActivityEnabledChanged: (Boolean) -> Unit = {},
    onStepsEnabledChanged: (Boolean) -> Unit = {},
    onWifiEnabledChanged: (Boolean) -> Unit = {},
    onWifiNetworkEnabledChanged: (Boolean) -> Unit = {},
    onWifiLocationCountEnabledChanged: (Boolean) -> Unit = {},
    onCellEnabledChanged: (Boolean) -> Unit = {},
    onNotificationCustomize: () -> Unit = {},
) {
    if (!uiState.isLoaded) return
    val customControlsEnabled = uiState.currentPreset == TrackingPreset.CUSTOM

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("trackingSettingsList"),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
    ) {
        // Tracking notice
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_notice_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // Validation warning
        if (!uiState.hasValidSources) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            stringResource(com.adsamcik.tracker.tracker.R.string.error_nothing_to_track),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        // Preset selector with progressive disclosure
        item {
            TrackingPresetRadioCarousel(
                selectedPreset = uiState.currentPreset,
                currentBatteryImpact = uiState.currentBatteryImpact,
                onPresetSelected = onPresetSelected,
            )
        }

        // Battery warning for high impact
        if (uiState.currentBatteryImpact == BatteryImpact.HIGH) {
            item {
                com.adsamcik.tracker.app.common.ui.BatteryImpactWarning()
            }
        }

        // Auto-tracking toggle (essential setting with help)
        item {
            TransitionDetectionSwitchItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_auto_tracking_transition_title),
                subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_auto_tracking_transition_summary),
                checked = uiState.transitionDetectionEnabled,
                onCheckedChange = onTransitionDetectionChanged,
                helpTextRes = com.adsamcik.tracker.tracker.R.string.help_transition_detection,
                modifier = Modifier.testTag("use_activity_transitions_switch"),
            )
        }

        // Notification toggle (essential setting)
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_styled_title),
                subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_styled_summary),
                checked = uiState.notificationStyled,
                onCheckedChange = onNotificationStyledChanged,
            )
        }

        // Ski detection toggle
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_ski_detection_title),
                subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_ski_detection_summary),
                checked = uiState.skiDetectionEnabled,
                onCheckedChange = onSkiDetectionChanged,
            )
        }

        // Notification customization
        item {
            SettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_customize_title),
                subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_customize_summary),
                icon = Icons.Default.Notifications,
                onClick = onNotificationCustomize,
            )
        }

        // Advanced settings section (collapsed by default)
        item {
            ExpandableSection(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_advanced_section_title),
                initiallyExpanded = false
            ) {
                if (!customControlsEnabled) {
                    Text(
                        text = stringResource(com.adsamcik.tracker.R.string.tracking_preset_custom_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                // Tracking parameters with contextual help
                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_min_distance_title),
                    value = uiState.minDistance.toFloat(),
                    valueRange = 0f..200f,
                    steps = 19,
                    valueLabel = { "${it.toInt()} m" },
                    onValueChange = { onMinDistanceChanged(it.toInt()) },
                    helpTextRes = com.adsamcik.tracker.tracker.R.string.help_min_distance,
                    enabled = customControlsEnabled,
                )

                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_min_time_title),
                    value = uiState.minTime.toFloat(),
                    valueRange = 0f..60f,
                    steps = 11,
                    valueLabel = { "${it.toInt()} s" },
                    onValueChange = { onMinTimeChanged(it.toInt()) },
                    helpTextRes = com.adsamcik.tracker.tracker.R.string.help_min_time,
                    enabled = customControlsEnabled,
                )

                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_required_accuracy_title),
                    value = uiState.requiredAccuracy.toFloat(),
                    valueRange = 10f..200f,
                    steps = 18,
                    valueLabel = { "${it.toInt()} m" },
                    onValueChange = { onRequiredAccuracyChanged(it.toInt()) },
                    helpTextRes = com.adsamcik.tracker.tracker.R.string.help_required_accuracy,
                    enabled = customControlsEnabled,
                )

                // Enable/disable sources
                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_location_enabled_title),
                    checked = uiState.locationEnabled,
                    onCheckedChange = onLocationEnabledChanged,
                    enabled = customControlsEnabled,
                )

                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_activity_enabled_title),
                    checked = uiState.activityEnabled,
                    onCheckedChange = onActivityEnabledChanged,
                    enabled = customControlsEnabled,
                )

                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_steps_enabled_title),
                    checked = uiState.stepsEnabled,
                    onCheckedChange = onStepsEnabledChanged,
                    enabled = customControlsEnabled,
                )

                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_wifi_enabled_title),
                    checked = uiState.wifiEnabled,
                    onCheckedChange = onWifiEnabledChanged,
                    enabled = customControlsEnabled,
                )

                // WiFi sub-options
                if (uiState.wifiEnabled) {
                    SwitchSettingsItem(
                        title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_wifi_network_enabled_title),
                        checked = uiState.wifiNetworkEnabled,
                        onCheckedChange = onWifiNetworkEnabledChanged,
                        enabled = customControlsEnabled,
                    )

                    SwitchSettingsItemWithHelp(
                        title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_wifi_location_count_enabled_title),
                        checked = uiState.wifiLocationCountEnabled,
                        onCheckedChange = onWifiLocationCountEnabledChanged,
                        helpTextRes = com.adsamcik.tracker.tracker.R.string.help_wifi_location_count,
                        enabled = customControlsEnabled,
                    )
                }

                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_cell_enabled_title),
                    checked = uiState.cellEnabled,
                    onCheckedChange = onCellEnabledChanged,
                    enabled = customControlsEnabled,
                )
            }
        }
    }
}

@Composable
private fun TrackingPresetRadioCarousel(
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
            modifier = Modifier.selectableGroup(),
            state = listState,
            contentPadding = PaddingValues(end = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(
                items = presets,
                key = { it.name },
            ) { preset ->
                val selected = preset == selectedPreset
                RadioCard(
                    selected = selected,
                    onClick = { onPresetSelected(preset) },
                    modifier = Modifier
                        .fillParentMaxWidth(0.72f)
                        .testTag(preset.testTag()),
                    cardColors = CardDefaults.cardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                    ),
                    cardBorder = BorderStroke(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
                    contentPadding = PaddingValues(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
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

@Composable
private fun TransitionDetectionSwitchItem(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    helpTextRes: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var showHelp by remember { mutableStateOf(false) }
    val switchOnDesc = stringResource(R.string.switch_state_on)
    val switchOffDesc = stringResource(R.string.switch_state_off)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics {
                stateDescription = if (checked) switchOnDesc else switchOffDesc
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )

                IconButton(
                    onClick = { showHelp = true },
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = stringResource(R.string.action_help),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(title) },
            text = { Text(stringResource(helpTextRes)) },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) {
                    Text(stringResource(R.string.got_it))
                }
            },
        )
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

private fun TrackingPreset.testTag(): String = when (this) {
    TrackingPreset.HIGH_ACCURACY -> "tracking_preset_high_accuracy_card"
    TrackingPreset.BALANCED -> "tracking_preset_balanced_card"
    TrackingPreset.POWER_SAVE -> "tracking_preset_power_save_card"
    TrackingPreset.CUSTOM -> "tracking_preset_custom_card"
}

private fun TrackingPreset.batteryImpact(current: BatteryImpact): BatteryImpact = when (this) {
    TrackingPreset.HIGH_ACCURACY -> BatteryImpact.HIGH
    TrackingPreset.BALANCED -> BatteryImpact.MODERATE
    TrackingPreset.POWER_SAVE -> BatteryImpact.LOW
    TrackingPreset.CUSTOM -> current
}
