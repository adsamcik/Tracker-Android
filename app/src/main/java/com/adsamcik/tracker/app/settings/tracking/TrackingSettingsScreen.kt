package com.adsamcik.tracker.app.settings.tracking

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.adsamcik.tracker.app.settings.TrackingSettingsUiState
import com.adsamcik.tracker.app.settings.TrackingSettingsViewModel
import com.adsamcik.tracker.app.settings.components.ExpandableSection
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.app.settings.components.SliderSettingsItemWithHelp
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItemWithHelp
import com.adsamcik.tracker.app.settings.ui.TrackingPresetSelector
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset

@Composable
fun TrackingSettingsScreen(onNavigateToNotificationManagement: () -> Unit = {}) {
    val trackingVm: TrackingSettingsViewModel = hiltViewModel()

    // Single consolidated state
    val uiState by trackingVm.uiState.collectAsState()
    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        trackingVm.onWifiPermissionResult(results.values.any { it })
    }
    val cellPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val fineLocationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val phoneStateGranted = results[Manifest.permission.READ_PHONE_STATE] == true
        trackingVm.onCellPermissionResult(fineLocationGranted && phoneStateGranted)
    }

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
        onWifiEnabledChanged = { enabled ->
            if (enabled && !uiState.wifiPermissionGranted) {
                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                } else {
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                }
                wifiPermissionLauncher.launch(permissions)
            } else {
                trackingVm.setWifiEnabled(enabled)
            }
        },
        onWifiNetworkEnabledChanged = { trackingVm.setWifiNetworkEnabled(it) },
        onWifiLocationCountEnabledChanged = { trackingVm.setWifiLocationCountEnabled(it) },
        onCellEnabledChanged = { enabled ->
            if (enabled && !uiState.cellPermissionGranted) {
                cellPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE,
                    ),
                )
            } else {
                trackingVm.setCellEnabled(enabled)
            }
        },
        onVehicleSpeedLimitKmhChanged = { trackingVm.setVehicleSpeedLimitKmh(it) },
        onNotificationCustomize = onNavigateToNotificationManagement,
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
    onVehicleSpeedLimitKmhChanged: (Int) -> Unit = {},
    onNotificationCustomize: () -> Unit = {},
) {
    if (!uiState.isLoaded) return
    val onPresetBaseline = uiState.currentPreset != TrackingPreset.CUSTOM

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

        // Background reliability (battery optimization / Samsung sleeping apps)
        item {
            com.adsamcik.tracker.app.background.BackgroundReliabilitySettingsEntry(
                modifier = Modifier.padding(vertical = 8.dp),
            )
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
            TrackingPresetSelector(
                selectedPreset = uiState.currentPreset,
                currentBatteryImpact = uiState.currentBatteryImpact,
                onPresetSelected = onPresetSelected,
            )
        }

        // Battery warning for high impact
        if (uiState.currentBatteryImpact == com.adsamcik.tracker.app.common.ui.BatteryImpact.HIGH) {
            item {
                com.adsamcik.tracker.app.common.ui.BatteryImpactWarning()
            }
        }

        // Auto-tracking toggle (essential setting with help)
        item {
            SwitchSettingsItemWithHelp(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_auto_tracking_transition_title),
                subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_auto_tracking_transition_summary),
                checked = uiState.transitionDetectionEnabled,
                onCheckedChange = onTransitionDetectionChanged,
                helpTextRes = com.adsamcik.tracker.tracker.R.string.help_transition_detection
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
                if (onPresetBaseline) {
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
                )

                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_min_time_title),
                    value = uiState.minTime.toFloat(),
                    valueRange = 0f..60f,
                    steps = 11,
                    valueLabel = { "${it.toInt()} s" },
                    onValueChange = { onMinTimeChanged(it.toInt()) },
                    helpTextRes = com.adsamcik.tracker.tracker.R.string.help_min_time,
                )

                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_required_accuracy_title),
                    value = uiState.requiredAccuracy.toFloat(),
                    valueRange = 10f..200f,
                    steps = 18,
                    valueLabel = { "${it.toInt()} m" },
                    onValueChange = { onRequiredAccuracyChanged(it.toInt()) },
                    helpTextRes = com.adsamcik.tracker.tracker.R.string.help_required_accuracy,
                )

                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_vehicle_speed_limit_baseline_title),
                    value = uiState.vehicleSpeedLimitKmh.toFloat(),
                    valueRange = 30f..130f,
                    steps = 99,
                    valueLabel = { "${it.toInt()} km/h" },
                    onValueChange = { onVehicleSpeedLimitKmhChanged(it.toInt()) },
                    helpTextRes = com.adsamcik.tracker.tracker.R.string.help_vehicle_speed_limit_baseline,
                )

                // Enable/disable sources
                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_location_enabled_title),
                    checked = uiState.locationEnabled,
                    onCheckedChange = onLocationEnabledChanged,
                )

                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_activity_enabled_title),
                    checked = uiState.activityEnabled,
                    onCheckedChange = onActivityEnabledChanged,
                )

                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_steps_enabled_title),
                    checked = uiState.stepsEnabled,
                    onCheckedChange = onStepsEnabledChanged,
                )

                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_wifi_enabled_title),
                    subtitle = if (uiState.wifiPermissionGranted) {
                        stringResource(com.adsamcik.tracker.R.string.settings_wifi_privacy_summary)
                    } else {
                        stringResource(com.adsamcik.tracker.R.string.settings_wifi_permission_required)
                    },
                    checked = uiState.wifiEnabled,
                    onCheckedChange = onWifiEnabledChanged,
                )

                // WiFi sub-options
                if (uiState.wifiEnabled) {
                    SwitchSettingsItem(
                        title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_wifi_network_enabled_title),
                        checked = uiState.wifiNetworkEnabled,
                        onCheckedChange = onWifiNetworkEnabledChanged,
                    )

                    SwitchSettingsItemWithHelp(
                        title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_wifi_location_count_enabled_title),
                        checked = uiState.wifiLocationCountEnabled,
                        onCheckedChange = onWifiLocationCountEnabledChanged,
                        helpTextRes = com.adsamcik.tracker.tracker.R.string.help_wifi_location_count,
                    )
                }

                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_cell_enabled_title),
                    subtitle = if (uiState.cellPermissionGranted) {
                        stringResource(com.adsamcik.tracker.R.string.settings_cell_privacy_summary)
                    } else {
                        stringResource(com.adsamcik.tracker.R.string.settings_cell_permission_required)
                    },
                    checked = uiState.cellEnabled,
                    onCheckedChange = onCellEnabledChanged,
                )
            }
        }
    }
}
