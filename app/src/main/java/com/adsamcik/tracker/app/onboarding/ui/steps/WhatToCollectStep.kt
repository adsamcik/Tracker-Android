package com.adsamcik.tracker.app.onboarding.ui.steps

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.onboarding.data.SetupUiState
import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionMode
import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionSelector
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.shared.utils.style.compose.PrimaryActionButton

/**
 * Step 3 – What to Collect.
 *
 * Data source toggles with inline permission explanations and grant buttons.
 */
@Composable
fun WhatToCollectStep(
    state: SetupUiState,
    onLocationEnabledChange: (Boolean) -> Unit,
    onLocationPrecisionChange: (LocationPrecisionMode) -> Unit,
    onActivityEnabledChange: (Boolean) -> Unit,
    onStepsEnabledChange: (Boolean) -> Unit,
    onWifiEnabledChange: (Boolean) -> Unit,
    onCellEnabledChange: (Boolean) -> Unit,
    onLocationPermissionResult: (Boolean) -> Unit,
    onBackgroundLocationResult: (Boolean) -> Unit,
    onActivityPermissionResult: (Boolean) -> Unit,
    onNotificationPermissionResult: (Boolean) -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Permission launchers
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results.values.any { it }
        onLocationPermissionResult(granted)
    }

    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onBackgroundLocationResult(granted)
    }

    val activityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onActivityPermissionResult(granted)
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onNotificationPermissionResult(granted)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.setup_what_to_collect_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.setup_what_to_collect_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- Location ---
            DataSourceCard(
                icon = Icons.Default.MyLocation,
                title = stringResource(R.string.setup_source_location),
                description = stringResource(R.string.setup_source_location_desc),
                enabled = state.locationEnabled,
                onToggle = onLocationEnabledChange,
            )

            if (state.locationEnabled) {
                Spacer(modifier = Modifier.height(8.dp))

                LocationPrecisionSelector(
                    selectedMode = state.locationPrecision,
                    onModeSelected = onLocationPrecisionChange,
                    modifier = Modifier.padding(start = 16.dp),
                )

                // Foreground location permission
                if (state.needsLocationPermission && !state.locationPermissionGranted) {
                    PermissionExplanation(
                        explanation = stringResource(R.string.setup_perm_location_why),
                        onGrant = {
                            val perms = if (state.locationPrecision == LocationPrecisionMode.PRECISE) {
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            } else {
                                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
                            }
                            locationLauncher.launch(perms)
                        },
                    )
                } else if (state.locationPermissionGranted) {
                    PermissionGrantedBadge()
                }

                // Background location permission (needed for auto-tracking)
                if (state.needsBackgroundLocationPermission &&
                    state.locationPermissionGranted &&
                    !state.backgroundLocationGranted
                ) {
                    Spacer(modifier = Modifier.height(4.dp))
                    PermissionExplanation(
                        explanation = stringResource(R.string.setup_perm_location_bg_why),
                        onGrant = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                backgroundLocationLauncher.launch(
                                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                                )
                            }
                        },
                    )
                } else if (state.backgroundLocationGranted && state.needsBackgroundLocationPermission) {
                    Spacer(modifier = Modifier.height(4.dp))
                    PermissionGrantedBadge()
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- Activity ---
            DataSourceCard(
                icon = Icons.AutoMirrored.Filled.DirectionsRun,
                title = stringResource(R.string.setup_source_activity),
                description = stringResource(R.string.setup_source_activity_desc),
                enabled = state.activityEnabled,
                onToggle = onActivityEnabledChange,
            )

            if (state.activityEnabled && state.needsActivityPermission) {
                if (!state.activityPermissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    PermissionExplanation(
                        explanation = stringResource(R.string.setup_perm_activity_why),
                        onGrant = {
                            activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                        },
                    )
                } else if (state.activityPermissionGranted) {
                    PermissionGrantedBadge()
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- Steps ---
            DataSourceCard(
                icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                title = stringResource(R.string.setup_source_steps),
                description = stringResource(R.string.setup_source_steps_desc),
                enabled = state.stepsEnabled,
                onToggle = onStepsEnabledChange,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // --- WiFi ---
            DataSourceCard(
                icon = Icons.Default.Wifi,
                title = stringResource(R.string.setup_source_wifi),
                description = stringResource(R.string.setup_source_wifi_desc),
                enabled = state.wifiEnabled,
                onToggle = onWifiEnabledChange,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // --- Cell ---
            DataSourceCard(
                icon = Icons.Default.CellTower,
                title = stringResource(R.string.setup_source_cell),
                description = stringResource(R.string.setup_source_cell_desc),
                enabled = state.cellEnabled,
                onToggle = onCellEnabledChange,
            )

            // Notification permission (always relevant)
            Spacer(modifier = Modifier.height(20.dp))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !state.notificationPermissionGranted
            ) {
                PermissionExplanation(
                    explanation = stringResource(R.string.setup_perm_notification_why),
                    onGrant = {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Pinned CTA
        PrimaryActionButton(
            text = stringResource(R.string.setup_start_exploring),
            onClick = onComplete,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("setup_cta_complete"),
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// region Private composables

@Composable
private fun DataSourceCard(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onToggle(!enabled) },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(28.dp),
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

            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
            )
        }
    }
}

@Composable
private fun PermissionExplanation(
    explanation: String,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )

        Button(
            onClick = onGrant,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Text(text = stringResource(R.string.setup_perm_grant))
        }
    }
}

@Composable
private fun PermissionGrantedBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(start = 16.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.setup_perm_granted),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// endregion
