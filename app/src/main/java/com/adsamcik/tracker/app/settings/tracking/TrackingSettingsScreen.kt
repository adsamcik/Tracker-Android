package com.adsamcik.tracker.app.settings.tracking

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.adsamcik.tracker.app.settings.TrackingSettingsViewModel
import com.adsamcik.tracker.app.settings.components.ExpandableSection
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.app.settings.components.SliderSettingsItemWithHelp
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItemWithHelp

@Composable
fun TrackingSettingsScreen() {
    val context = LocalContext.current
    val trackingVm: TrackingSettingsViewModel = hiltViewModel()

    // Single consolidated state
    val uiState by trackingVm.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
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
            com.adsamcik.tracker.app.settings.ui.TrackingPolicySelector(
                selectedPreset = uiState.currentPreset,
                onPresetSelected = { trackingVm.applyPreset(it) },
                showDetails = false
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
                onCheckedChange = { trackingVm.setTransitionDetectionEnabled(it) },
                helpTextRes = com.adsamcik.tracker.tracker.R.string.help_transition_detection
            )
        }

        // Notification toggle (essential setting)
        item {
            SwitchSettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_styled_title),
                subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_styled_summary),
                checked = uiState.notificationStyled,
                onCheckedChange = { trackingVm.setNotificationStyled(it) }
            )
        }

        // Notification customization
        item {
            SettingsItem(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_customize_title),
                subtitle = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_customize_summary),
                icon = Icons.Default.Notifications,
                onClick = {
                    context.startActivity(
                        android.content.Intent(context, com.adsamcik.tracker.tracker.notification.NotificationManagementActivity::class.java)
                    )
                }
            )
        }

        // Advanced settings section (collapsed by default)
        item {
            ExpandableSection(
                title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_advanced_section_title),
                initiallyExpanded = false
            ) {
                // Tracking parameters with contextual help
                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_min_distance_title),
                    value = uiState.minDistance.toFloat(),
                    valueRange = 0f..200f,
                    steps = 19,
                    valueLabel = { "${it.toInt()} m" },
                    onValueChange = { trackingVm.setMinDistance(it.toInt()) },
                    helpTextRes = com.adsamcik.tracker.tracker.R.string.help_min_distance
                )

                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_min_time_title),
                    value = uiState.minTime.toFloat(),
                    valueRange = 0f..60f,
                    steps = 11,
                    valueLabel = { "${it.toInt()} s" },
                    onValueChange = { trackingVm.setMinTime(it.toInt()) },
                    helpTextRes = com.adsamcik.tracker.tracker.R.string.help_min_time
                )

                SliderSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_required_accuracy_title),
                    value = uiState.requiredAccuracy.toFloat(),
                    valueRange = 10f..200f,
                    steps = 18,
                    valueLabel = { "${it.toInt()} m" },
                    onValueChange = { trackingVm.setRequiredAccuracy(it.toInt()) },
                    helpTextRes = com.adsamcik.tracker.tracker.R.string.help_required_accuracy
                )

                // Enable/disable sources
                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_location_enabled_title),
                    checked = uiState.locationEnabled,
                    onCheckedChange = { trackingVm.setLocationEnabled(it) }
                )

                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_activity_enabled_title),
                    checked = uiState.activityEnabled,
                    onCheckedChange = { trackingVm.setActivityEnabled(it) }
                )

                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_steps_enabled_title),
                    checked = uiState.stepsEnabled,
                    onCheckedChange = { trackingVm.setStepsEnabled(it) }
                )

                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_wifi_enabled_title),
                    checked = uiState.wifiEnabled,
                    onCheckedChange = { trackingVm.setWifiEnabled(it) }
                )

                // WiFi sub-options
                if (uiState.wifiEnabled) {
                    SwitchSettingsItem(
                        title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_wifi_network_enabled_title),
                        checked = uiState.wifiNetworkEnabled,
                        onCheckedChange = { trackingVm.setWifiNetworkEnabled(it) }
                    )

                    SwitchSettingsItemWithHelp(
                        title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_wifi_location_count_enabled_title),
                        checked = uiState.wifiLocationCountEnabled,
                        onCheckedChange = { trackingVm.setWifiLocationCountEnabled(it) },
                        helpTextRes = com.adsamcik.tracker.tracker.R.string.help_wifi_location_count
                    )
                }

                SwitchSettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_cell_enabled_title),
                    checked = uiState.cellEnabled,
                    onCheckedChange = { trackingVm.setCellEnabled(it) }
                )
            }
        }
    }
}
