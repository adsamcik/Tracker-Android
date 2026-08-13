package com.adsamcik.tracker.app.settings.tracking

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.adsamcik.tracker.app.settings.TrackingSettingsUiState
import com.adsamcik.tracker.app.settings.TrackingSettingsViewModel
import com.adsamcik.tracker.app.settings.components.SectionHeader
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.app.settings.components.SettingsGroupCard
import com.adsamcik.tracker.app.settings.components.SettingsRowDivider
import com.adsamcik.tracker.app.settings.components.SliderSettingsItemWithHelp
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItemWithHelp
import com.adsamcik.tracker.app.settings.ui.TrackingPresetSelector
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.source.coordinator.EffectiveSourceState
import com.adsamcik.tracker.tracker.source.model.SourceKind

@Composable
fun TrackingSettingsScreen(onNavigateToNotificationManagement: () -> Unit = {}) {
    val trackingVm: TrackingSettingsViewModel = hiltViewModel()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Single consolidated state
    val uiState by trackingVm.uiState.collectAsState()
    DisposableEffect(lifecycleOwner, trackingVm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                trackingVm.refreshPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val precise = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val nearby = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            results[Manifest.permission.NEARBY_WIFI_DEVICES] == true
        trackingVm.onWifiPermissionResult(precise && nearby)
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
    var pendingAutoTrackingMode by remember { mutableStateOf<Int?>(null) }
    val autoTrackingPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val requestedMode = pendingAutoTrackingMode
        pendingAutoTrackingMode = null
        if (requestedMode != null) {
            trackingVm.onAutoTrackingPermissionResult(requestedMode, granted)
        }
    }

    TrackingSettingsContent(
        uiState = uiState,
        onPresetSelected = { trackingVm.applyPreset(it) },
        onAutoTrackingModeChanged = { mode ->
            if (mode > 0 && !uiState.activityPermissionGranted &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                pendingAutoTrackingMode = mode
                autoTrackingPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            } else {
                trackingVm.setAutoTrackingMode(mode)
            }
        },
        onTransitionDetectionChanged = { trackingVm.setTransitionDetectionEnabled(it) },
        onNotificationStyledChanged = { trackingVm.setNotificationStyled(it) },
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
        onAdvancedSourceControlsChanged = { trackingVm.setAdvancedSourceControlsEnabled(it) },
        onSourceFrequencyChanged = trackingVm::setSourceFrequency,
        onWifiEnabledChanged = { enabled ->
            if (enabled && !uiState.wifiPermissionGranted) {
                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.NEARBY_WIFI_DEVICES,
                    )
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
        onNotificationCustomize = onNavigateToNotificationManagement,
    )
}

@Composable
internal fun TrackingSettingsContent(
    uiState: TrackingSettingsUiState,
    onPresetSelected: (TrackingPreset) -> Unit = {},
    onAutoTrackingModeChanged: (Int) -> Unit = {},
    onTransitionDetectionChanged: (Boolean) -> Unit = {},
    onNotificationStyledChanged: (Boolean) -> Unit = {},
    onMinDistanceChanged: (Int) -> Unit = {},
    onMinTimeChanged: (Int) -> Unit = {},
    onRequiredAccuracyChanged: (Int) -> Unit = {},
    onLocationEnabledChanged: (Boolean) -> Unit = {},
    onActivityEnabledChanged: (Boolean) -> Unit = {},
    onStepsEnabledChanged: (Boolean) -> Unit = {},
    onBarometerEnabledChanged: (Boolean) -> Unit = {},
    onWifiEnabledChanged: (Boolean) -> Unit = {},
    onCellEnabledChanged: (Boolean) -> Unit = {},
    onAdvancedSourceControlsChanged: (Boolean) -> Unit = {},
    onSourceFrequencyChanged: (TrackingSourceComponent, SourceCollectionFrequency) -> Unit = { _, _ -> },
    onNotificationCustomize: () -> Unit = {},
) {
    if (!uiState.isLoaded) return
    var technicalDetailsExpanded by remember { mutableStateOf(false) }
    val sourceNeedsAttention = uiState.runtimeFailureCode != null ||
        uiState.sourceStatuses.values.any { status ->
            status.state in setOf(
                EffectiveSourceState.BLOCKED,
                EffectiveSourceState.DEGRADED,
                EffectiveSourceState.FAILED,
            )
        }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("trackingSettingsList"),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
    ) {
        // Only active trips need to be restarted before changes can be applied.
        if (uiState.trackingActive) item {
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

        // --- Start & stop ---
        item {
            SectionHeader(
                stringResource(com.adsamcik.tracker.R.string.settings_tracking_section_start_stop),
                icon = Icons.AutoMirrored.Filled.DirectionsRun,
            )
        }
        item {
            AutoTrackingModeSelector(
                selectedMode = uiState.autoTrackingMode.coerceIn(0, 2),
                onModeSelected = onAutoTrackingModeChanged,
            )
        }
        if (uiState.autoTrackingEnabled && uiState.locationEnabled &&
            uiState.permissionCapabilities.isManualLocationOnly
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("automaticTrackingManualOnly"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Text(
                        text = stringResource(
                            com.adsamcik.tracker.R.string.setup_background_location_manual_only,
                        ),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (uiState.autoTrackingEnabled) {
            item {
                SwitchSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.R.string.settings_transition_detection_friendly_title),
                    subtitle = stringResource(com.adsamcik.tracker.R.string.settings_transition_detection_friendly_summary),
                    checked = uiState.transitionDetectionEnabled,
                    onCheckedChange = onTransitionDetectionChanged,
                    helpTextRes = com.adsamcik.tracker.tracker.R.string.help_transition_detection,
                )
            }
        }

        // Battery optimization and vendor restrictions affect both automatic and manual trips.
        item {
            com.adsamcik.tracker.app.background.BackgroundReliabilitySettingsEntry(
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        // --- Battery & detail ---
        item {
            SectionHeader(
                stringResource(com.adsamcik.tracker.R.string.settings_tracking_section_battery_detail),
                icon = Icons.Default.Battery6Bar,
            )
        }

        item {
            TrackingPresetSelector(
                selectedPreset = uiState.currentPreset,
                currentBatteryImpact = uiState.currentBatteryImpact,
                onPresetSelected = onPresetSelected,
            )
        }

        // --- Data collected ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(
                    stringResource(com.adsamcik.tracker.R.string.settings_tracking_section_data_collected),
                    icon = Icons.Default.Storage,
                )
                Spacer(Modifier.weight(1f))
                if (uiState.advancedSourceControlsEnabled) {
                    TextButton(onClick = { onAdvancedSourceControlsChanged(false) }) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(com.adsamcik.tracker.R.string.settings_simple_source_controls_title))
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }

        if (!uiState.hasValidSources) {
            item {
                MissingSourcesWarning()
            }
        }

        if (uiState.advancedSourceControlsEnabled) {
            item {
                AdvancedDataSourceSettings(
                    uiState = uiState,
                    onSourceFrequencyChanged = onSourceFrequencyChanged,
                )
            }
        } else {
            item {
                SimpleDataSourceSettings(
                    uiState = uiState,
                    onLocationEnabledChanged = onLocationEnabledChanged,
                    onActivityEnabledChanged = onActivityEnabledChanged,
                    onStepsEnabledChanged = onStepsEnabledChanged,
                    onBarometerEnabledChanged = onBarometerEnabledChanged,
                    onWifiEnabledChanged = onWifiEnabledChanged,
                    onCellEnabledChanged = onCellEnabledChanged,
                )
            }
            item {
                SettingsGroupCard(modifier = Modifier.padding(top = 8.dp)) {
                    SettingsItem(
                        title = stringResource(com.adsamcik.tracker.R.string.settings_advanced_source_controls_title),
                        subtitle = stringResource(com.adsamcik.tracker.R.string.settings_advanced_source_controls_summary),
                        icon = Icons.Default.Tune,
                        onClick = { onAdvancedSourceControlsChanged(true) },
                    )
                }
            }
        }

        // These parameters only control location requests/filtering, not other source cadences.
        if (uiState.advancedSourceControlsEnabled && uiState.locationEnabled) {
            item {
                SectionHeader(
                    stringResource(com.adsamcik.tracker.R.string.settings_tracking_section_detail),
                    icon = Icons.Default.Tune,
                )
            }
            item {
                SettingsGroupCard {
                    SliderSettingsItemWithHelp(
                        title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_min_distance_title),
                        value = uiState.minDistance.toFloat(),
                        valueRange = 0f..200f,
                        steps = 19,
                        valueLabel = { "${it.toInt()} m" },
                        onValueChange = { onMinDistanceChanged(it.toInt()) },
                        helpTextRes = com.adsamcik.tracker.tracker.R.string.help_min_distance,
                    )
                    SettingsRowDivider()
                    SliderSettingsItemWithHelp(
                        title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_min_time_title),
                        value = uiState.minTime.toFloat(),
                        valueRange = 0f..60f,
                        steps = 11,
                        valueLabel = { "${it.toInt()} s" },
                        onValueChange = { onMinTimeChanged(it.toInt()) },
                        helpTextRes = com.adsamcik.tracker.tracker.R.string.help_min_time,
                    )
                    SettingsRowDivider()
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
        }
        item {
            SettingsGroupCard(modifier = Modifier.padding(top = 8.dp)) {
                SettingsItem(
                    title = stringResource(com.adsamcik.tracker.R.string.settings_technical_status_title),
                    subtitle = stringResource(
                        if (sourceNeedsAttention) {
                            com.adsamcik.tracker.R.string.settings_technical_status_attention
                        } else {
                            com.adsamcik.tracker.R.string.settings_technical_status_summary
                        },
                    ),
                    icon = Icons.Default.Info,
                    onClick = { technicalDetailsExpanded = !technicalDetailsExpanded },
                    modifier = Modifier.testTag("technicalStatusToggle"),
                )
            }
        }
        if (technicalDetailsExpanded) {
            uiState.batteryEstimate?.let { estimate ->
                item {
                    BatteryEstimateCard(estimate)
                }
            }
            item {
                EffectiveTrackingStatusCard(uiState)
            }
        }

        // --- Notifications ---
        item {
            SectionHeader(
                stringResource(com.adsamcik.tracker.R.string.settings_tracking_section_notifications),
                icon = Icons.Default.Notifications,
            )
        }
        item {
            SettingsGroupCard {
                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_styled_title),
                    subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_styled_summary),
                    checked = uiState.notificationStyled,
                    onCheckedChange = onNotificationStyledChanged,
                )
                SettingsRowDivider()
                SettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_customize_title),
                    subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_customize_summary),
                    icon = Icons.Default.Notifications,
                    onClick = onNotificationCustomize,
                )
            }
        }
    }
}

@Composable
private fun SimpleDataSourceSettings(
    uiState: TrackingSettingsUiState,
    onLocationEnabledChanged: (Boolean) -> Unit,
    onActivityEnabledChanged: (Boolean) -> Unit,
    onStepsEnabledChanged: (Boolean) -> Unit,
    onBarometerEnabledChanged: (Boolean) -> Unit,
    onWifiEnabledChanged: (Boolean) -> Unit,
    onCellEnabledChanged: (Boolean) -> Unit,
) {
    SettingsGroupCard {
        SwitchSettingsItem(
            title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_location_enabled_title),
            subtitle = stringResource(com.adsamcik.tracker.R.string.settings_location_source_summary),
            iconRes = com.adsamcik.tracker.R.drawable.ic_tracking_source_location,
            checked = uiState.locationEnabled,
            onCheckedChange = onLocationEnabledChanged,
        )
        SettingsRowDivider()
        SwitchSettingsItem(
            title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_activity_enabled_title),
            subtitle = sourceSummaryWithOptionalStatus(
                summaryRes = com.adsamcik.tracker.R.string.settings_activity_source_summary,
                statusRes = if (uiState.activityPermissionGranted) null else {
                    com.adsamcik.tracker.R.string.settings_activity_permission_required
                },
            ),
            iconRes = com.adsamcik.tracker.R.drawable.ic_tracking_source_activity,
            checked = uiState.activityEnabled,
            onCheckedChange = onActivityEnabledChanged,
        )
        SettingsRowDivider()
        SwitchSettingsItem(
            title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_steps_enabled_title),
            subtitle = sourceSummaryWithOptionalStatus(
                summaryRes = com.adsamcik.tracker.R.string.settings_steps_source_summary,
                statusRes = when {
                    !uiState.stepCounterAvailable -> com.adsamcik.tracker.R.string.settings_steps_unavailable
                    !uiState.activityPermissionGranted -> {
                        com.adsamcik.tracker.R.string.settings_activity_permission_required
                    }
                    else -> null
                },
            ),
            iconRes = com.adsamcik.tracker.R.drawable.ic_tracking_source_steps,
            checked = uiState.stepsEnabled,
            onCheckedChange = onStepsEnabledChanged,
            enabled = uiState.stepCounterAvailable,
        )
        SettingsRowDivider()
        SwitchSettingsItem(
            title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_barometer_enabled_title),
            subtitle = sourceSummaryWithOptionalStatus(
                summaryRes = com.adsamcik.tracker.R.string.settings_barometer_source_summary,
                statusRes = if (uiState.barometerAvailable) null else {
                    com.adsamcik.tracker.R.string.settings_barometer_unavailable
                },
            ),
            iconRes = com.adsamcik.tracker.R.drawable.ic_tracking_source_pressure,
            checked = uiState.barometerEnabled,
            onCheckedChange = onBarometerEnabledChanged,
            enabled = uiState.barometerAvailable,
        )
        SettingsRowDivider()
        SwitchSettingsItem(
            title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_wifi_enabled_title),
            subtitle = sourceSummaryWithOptionalStatus(
                summaryRes = com.adsamcik.tracker.R.string.settings_wifi_source_summary,
                statusRes = if (uiState.wifiPermissionGranted) null else {
                    com.adsamcik.tracker.R.string.settings_wifi_permission_required
                },
            ),
            iconRes = com.adsamcik.tracker.R.drawable.ic_tracking_source_wifi,
            checked = uiState.wifiEnabled,
            onCheckedChange = onWifiEnabledChanged,
        )
        SettingsRowDivider()
        SwitchSettingsItem(
            title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_cell_enabled_title),
            subtitle = sourceSummaryWithOptionalStatus(
                summaryRes = com.adsamcik.tracker.R.string.settings_cell_source_summary,
                statusRes = if (uiState.cellPermissionGranted) null else {
                    com.adsamcik.tracker.R.string.settings_cell_permission_required
                },
            ),
            iconRes = com.adsamcik.tracker.R.drawable.ic_tracking_source_cell,
            checked = uiState.cellEnabled,
            onCheckedChange = onCellEnabledChanged,
        )
    }
}

@Composable
private fun AdvancedDataSourceSettings(
    uiState: TrackingSettingsUiState,
    onSourceFrequencyChanged: (TrackingSourceComponent, SourceCollectionFrequency) -> Unit,
) {
    SettingsGroupCard {
        SourceFrequencySettingsItem(
            title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_location_enabled_title),
            description = stringResource(com.adsamcik.tracker.R.string.settings_location_source_compact),
            iconRes = com.adsamcik.tracker.R.drawable.ic_tracking_source_location,
            frequency = uiState.sourceCollectionSettings.location,
            status = uiState.sourceStatuses[SourceKind.LOCATION],
            plan = uiState.sourcePlans[SourceKind.LOCATION],
            optionPlans = uiState.sourceFrequencyOptions[SourceKind.LOCATION].orEmpty(),
            onFrequencySelected = { onSourceFrequencyChanged(TrackingSourceComponent.LOCATION, it) },
        )
        SettingsRowDivider()
        SourceFrequencySettingsItem(
            title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_activity_enabled_title),
            description = stringResource(com.adsamcik.tracker.R.string.settings_activity_source_compact),
            iconRes = com.adsamcik.tracker.R.drawable.ic_tracking_source_activity,
            frequency = uiState.sourceCollectionSettings.activity,
            status = uiState.sourceStatuses[SourceKind.ACTIVITY],
            plan = uiState.sourcePlans[SourceKind.ACTIVITY],
            optionPlans = uiState.sourceFrequencyOptions[SourceKind.ACTIVITY].orEmpty(),
            onFrequencySelected = { onSourceFrequencyChanged(TrackingSourceComponent.ACTIVITY, it) },
        )
        SettingsRowDivider()
        SourceFrequencySettingsItem(
            title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_steps_enabled_title),
            description = stringResource(com.adsamcik.tracker.R.string.settings_steps_source_compact),
            iconRes = com.adsamcik.tracker.R.drawable.ic_tracking_source_steps,
            frequency = uiState.sourceCollectionSettings.steps,
            status = uiState.sourceStatuses[SourceKind.STEPS],
            plan = uiState.sourcePlans[SourceKind.STEPS],
            optionPlans = uiState.sourceFrequencyOptions[SourceKind.STEPS].orEmpty(),
            onFrequencySelected = { onSourceFrequencyChanged(TrackingSourceComponent.STEPS, it) },
        )
        SettingsRowDivider()
        SourceFrequencySettingsItem(
            title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_barometer_enabled_title),
            description = stringResource(com.adsamcik.tracker.R.string.settings_barometer_source_compact),
            iconRes = com.adsamcik.tracker.R.drawable.ic_tracking_source_pressure,
            frequency = uiState.sourceCollectionSettings.pressure,
            status = uiState.sourceStatuses[SourceKind.PRESSURE],
            plan = uiState.sourcePlans[SourceKind.PRESSURE],
            optionPlans = uiState.sourceFrequencyOptions[SourceKind.PRESSURE].orEmpty(),
            onFrequencySelected = { onSourceFrequencyChanged(TrackingSourceComponent.PRESSURE, it) },
        )
        SettingsRowDivider()
        SourceFrequencySettingsItem(
            title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_wifi_enabled_title),
            description = stringResource(com.adsamcik.tracker.R.string.settings_wifi_source_compact),
            iconRes = com.adsamcik.tracker.R.drawable.ic_tracking_source_wifi,
            frequency = uiState.sourceCollectionSettings.wifi,
            status = uiState.sourceStatuses[SourceKind.WIFI],
            plan = uiState.sourcePlans[SourceKind.WIFI],
            optionPlans = uiState.sourceFrequencyOptions[SourceKind.WIFI].orEmpty(),
            onFrequencySelected = { onSourceFrequencyChanged(TrackingSourceComponent.WIFI, it) },
        )
        SettingsRowDivider()
        SourceFrequencySettingsItem(
            title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_cell_enabled_title),
            description = stringResource(com.adsamcik.tracker.R.string.settings_cell_source_compact),
            iconRes = com.adsamcik.tracker.R.drawable.ic_tracking_source_cell,
            frequency = uiState.sourceCollectionSettings.cell,
            status = uiState.sourceStatuses[SourceKind.CELL],
            plan = uiState.sourcePlans[SourceKind.CELL],
            optionPlans = uiState.sourceFrequencyOptions[SourceKind.CELL].orEmpty(),
            onFrequencySelected = { onSourceFrequencyChanged(TrackingSourceComponent.CELL, it) },
        )
    }
}

@Composable
private fun MissingSourcesWarning() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                stringResource(com.adsamcik.tracker.tracker.R.string.error_nothing_to_track),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun sourceSummaryWithOptionalStatus(
    summaryRes: Int,
    statusRes: Int?,
): String {
    val summary = stringResource(summaryRes)
    return statusRes?.let { status ->
        stringResource(
            com.adsamcik.tracker.R.string.settings_source_summary_with_status,
            summary,
            stringResource(status),
        )
    } ?: summary
}

@Composable
private fun AutoTrackingModeSelector(
    selectedMode: Int,
    onModeSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("automaticTrackingMode"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(com.adsamcik.tracker.R.string.settings_auto_tracking_mode_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            AUTO_TRACKING_MODE_OPTIONS.forEachIndexed { index, option ->
                AutoTrackingModeOptionRow(
                    option = option,
                    selected = selectedMode == option.mode,
                    onSelect = { onModeSelected(option.mode) },
                )
                if (index != AUTO_TRACKING_MODE_OPTIONS.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoTrackingModeOptionRow(
    option: AutoTrackingModeOption,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainer,
            )
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("automaticTrackingModeOption-${option.mode}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(option.titleRes),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(option.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class AutoTrackingModeOption(
    val mode: Int,
    val titleRes: Int,
    val descriptionRes: Int,
)

private val AUTO_TRACKING_MODE_OPTIONS = listOf(
    AutoTrackingModeOption(
        mode = 0,
        titleRes = com.adsamcik.tracker.R.string.setup_auto_tracking_disabled,
        descriptionRes = com.adsamcik.tracker.R.string.setup_auto_tracking_disabled_desc,
    ),
    AutoTrackingModeOption(
        mode = 1,
        titleRes = com.adsamcik.tracker.R.string.setup_auto_tracking_on_foot,
        descriptionRes = com.adsamcik.tracker.R.string.setup_auto_tracking_on_foot_desc,
    ),
    AutoTrackingModeOption(
        mode = 2,
        titleRes = com.adsamcik.tracker.R.string.setup_auto_tracking_in_motion,
        descriptionRes = com.adsamcik.tracker.R.string.setup_auto_tracking_in_motion_desc,
    ),
)
