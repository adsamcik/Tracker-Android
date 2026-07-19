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
import com.adsamcik.tracker.app.settings.components.SectionHeader
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
    val activityPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        trackingVm::onActivityPermissionResult,
    )
    val stepsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        trackingVm::onStepsPermissionResult,
    )

    TrackingSettingsContent(
        uiState = uiState,
        onPresetSelected = { trackingVm.applyPreset(it) },
        onTransitionDetectionChanged = { trackingVm.setTransitionDetectionEnabled(it) },
        onNotificationStyledChanged = { trackingVm.setNotificationStyled(it) },
        onSkiDetectionChanged = { trackingVm.setSkiDetectionEnabled(it) },
        onSailingDetectionChanged = { trackingVm.setSailingDetectionEnabled(it) },
        onPlaneDetectionChanged = { trackingVm.setPlaneDetectionEnabled(it) },
        onMinDistanceChanged = { trackingVm.setMinDistance(it) },
        onMinTimeChanged = { trackingVm.setMinTime(it) },
        onRequiredAccuracyChanged = { trackingVm.setRequiredAccuracy(it) },
        onLocationEnabledChanged = { trackingVm.setLocationEnabled(it) },
        onActivityEnabledChanged = { enabled ->
            if (enabled && !uiState.activityPermissionGranted &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                activityPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            } else {
                trackingVm.setActivityEnabled(enabled)
            }
        },
        onStepsEnabledChanged = { enabled ->
            if (enabled && !uiState.activityPermissionGranted &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                stepsPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            } else {
                trackingVm.setStepsEnabled(enabled)
            }
        },
        onBarometerEnabledChanged = { trackingVm.setBarometerEnabled(it) },
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
    onSailingDetectionChanged: (Boolean) -> Unit = {},
    onPlaneDetectionChanged: (Boolean) -> Unit = {},
    onMinDistanceChanged: (Int) -> Unit = {},
    onMinTimeChanged: (Int) -> Unit = {},
    onRequiredAccuracyChanged: (Int) -> Unit = {},
    onLocationEnabledChanged: (Boolean) -> Unit = {},
    onActivityEnabledChanged: (Boolean) -> Unit = {},
    onStepsEnabledChanged: (Boolean) -> Unit = {},
    onBarometerEnabledChanged: (Boolean) -> Unit = {},
    onWifiEnabledChanged: (Boolean) -> Unit = {},
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

        // Custom-profile hint: editing any control below builds a Custom profile.
        if (onPresetBaseline) {
            item {
                Text(
                    text = stringResource(com.adsamcik.tracker.R.string.tracking_preset_custom_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // --- Detection & automation ---
        item {
            SectionHeader(
                stringResource(com.adsamcik.tracker.R.string.settings_tracking_section_detection),
            )
        }
        item {
            SwitchSettingsItemWithHelp(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_auto_tracking_transition_title),
                subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_auto_tracking_transition_summary),
                checked = uiState.transitionDetectionEnabled,
                onCheckedChange = onTransitionDetectionChanged,
                helpTextRes = com.adsamcik.tracker.tracker.R.string.help_transition_detection,
            )
        }
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_ski_detection_title),
                subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_ski_detection_summary),
                checked = uiState.skiDetectionEnabled,
                onCheckedChange = onSkiDetectionChanged,
                enabled = uiState.barometerAvailable && uiState.barometerEnabled,
            )
        }
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_sailing_detection_title),
                subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_sailing_detection_summary),
                checked = uiState.sailingDetectionEnabled,
                onCheckedChange = onSailingDetectionChanged,
            )
        }
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_plane_detection_title),
                subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_plane_detection_summary),
                checked = uiState.planeDetectionEnabled,
                onCheckedChange = onPlaneDetectionChanged,
                enabled = uiState.barometerAvailable && uiState.barometerEnabled,
            )
        }

        // --- Data sources ---
        item {
            SectionHeader(
                stringResource(com.adsamcik.tracker.R.string.settings_tracking_section_sources),
            )
        }
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_location_enabled_title),
                checked = uiState.locationEnabled,
                onCheckedChange = onLocationEnabledChanged,
            )
        }
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_activity_enabled_title),
                subtitle = if (uiState.activityPermissionGranted) {
                    null
                } else {
                    stringResource(com.adsamcik.tracker.R.string.settings_activity_permission_required)
                },
                checked = uiState.activityEnabled,
                onCheckedChange = onActivityEnabledChanged,
            )
        }
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_steps_enabled_title),
                subtitle = when {
                    !uiState.stepCounterAvailable -> stringResource(
                        com.adsamcik.tracker.R.string.settings_steps_unavailable,
                    )
                    !uiState.activityPermissionGranted -> stringResource(
                        com.adsamcik.tracker.R.string.settings_activity_permission_required,
                    )
                    else -> null
                },
                checked = uiState.stepsEnabled,
                onCheckedChange = onStepsEnabledChanged,
                enabled = uiState.stepCounterAvailable,
            )
        }
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_barometer_enabled_title),
                subtitle = if (uiState.barometerAvailable) {
                    null
                } else {
                    stringResource(com.adsamcik.tracker.R.string.settings_barometer_unavailable)
                },
                checked = uiState.barometerEnabled,
                onCheckedChange = onBarometerEnabledChanged,
                enabled = uiState.barometerAvailable,
            )
        }
        item {
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
        }
        item {
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

        // These parameters only control location requests/filtering, not other source cadences.
        if (uiState.locationEnabled) {
            item {
                SectionHeader(
                    stringResource(com.adsamcik.tracker.R.string.settings_tracking_section_detail),
                )
            }
            item {
                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_min_distance_title),
                    value = uiState.minDistance.toFloat(),
                    valueRange = 0f..200f,
                    steps = 19,
                    valueLabel = { "${it.toInt()} m" },
                    onValueChange = { onMinDistanceChanged(it.toInt()) },
                    helpTextRes = com.adsamcik.tracker.tracker.R.string.help_min_distance,
                )
            }
            item {
                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_min_time_title),
                    value = uiState.minTime.toFloat(),
                    valueRange = 0f..60f,
                    steps = 11,
                    valueLabel = { "${it.toInt()} s" },
                    onValueChange = { onMinTimeChanged(it.toInt()) },
                    helpTextRes = com.adsamcik.tracker.tracker.R.string.help_min_time,
                )
            }
            item {
                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_required_accuracy_title),
                    value = uiState.requiredAccuracy.toFloat(),
                    valueRange = 10f..200f,
                    steps = 18,
                    valueLabel = { "${it.toInt()} m" },
                    onValueChange = { onRequiredAccuracyChanged(it.toInt()) },
                    helpTextRes = com.adsamcik.tracker.tracker.R.string.help_required_accuracy,
                )
            }
        }
        item {
            SliderSettingsItemWithHelp(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_vehicle_speed_limit_baseline_title),
                value = uiState.vehicleSpeedLimitKmh.toFloat(),
                valueRange = 30f..130f,
                steps = 99,
                valueLabel = { "${it.toInt()} km/h" },
                onValueChange = { onVehicleSpeedLimitKmhChanged(it.toInt()) },
                helpTextRes = com.adsamcik.tracker.tracker.R.string.help_vehicle_speed_limit_baseline,
            )
        }

        // --- Notifications ---
        item {
            SectionHeader(
                stringResource(com.adsamcik.tracker.R.string.settings_tracking_section_notifications),
            )
        }
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_styled_title),
                subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_styled_summary),
                checked = uiState.notificationStyled,
                onCheckedChange = onNotificationStyledChanged,
            )
        }
        item {
            SettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_customize_title),
                subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_customize_summary),
                icon = Icons.Default.Notifications,
                onClick = onNotificationCustomize,
            )
        }
    }
}
