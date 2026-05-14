package com.adsamcik.tracker.app.onboarding.ui.steps

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.common.ui.BatteryImpactIndicator
import com.adsamcik.tracker.app.onboarding.data.SetupPermissionState
import com.adsamcik.tracker.app.onboarding.data.SetupUiState
import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionMode
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.shared.utils.style.compose.RadioCard

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
    onLocationPermissionResult: (Boolean, Boolean, Boolean) -> Unit,
    onBackgroundLocationResult: (Boolean, Boolean) -> Unit,
    onActivityPermissionResult: (Boolean, Boolean) -> Unit,
    onNotificationPermissionResult: (Boolean) -> Unit,
    onPermissionStateHydrated: (SetupPermissionState) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    LaunchedEffect(context) {
        onPermissionStateHydrated(context.currentPermissionState(state))
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        onPermissionStateHydrated(context.currentPermissionState(state))
    }

    // Permission launchers
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val foregroundGranted = fineGranted || coarseGranted
        onLocationPermissionResult(
            fineGranted,
            coarseGranted,
            !foregroundGranted && context.isForegroundLocationPermanentlyDenied(),
        )
    }

    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onBackgroundLocationResult(
            granted,
            !granted && context.isPermissionPermanentlyDenied(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ),
        )
    }

    val activityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onActivityPermissionResult(
            granted,
            !granted && context.isPermissionPermanentlyDenied(
                Manifest.permission.ACTIVITY_RECOGNITION,
            ),
        )
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onNotificationPermissionResult(granted)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 24.dp)
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
                    buttonText = stringResource(
                        if (state.locationPermissionPermanentlyDenied) {
                            R.string.permission_grant_in_settings
                        } else {
                            R.string.setup_perm_grant
                        },
                    ),
                    onGrant = {
                        if (state.locationPermissionPermanentlyDenied) {
                            context.openApplicationSettings()
                            return@PermissionExplanation
                        }
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

            // Background location permission (needed for in-motion auto-tracking)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                state.needsBackgroundLocationPermission &&
                state.locationPermissionGranted
            ) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.setup_background_location_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
                Text(
                    text = stringResource(R.string.setup_background_location_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                )
                if (!state.backgroundLocationGranted) {
                    PermissionExplanation(
                        explanation = stringResource(R.string.setup_perm_location_bg_why),
                        buttonText = stringResource(
                            if (state.backgroundLocationPermissionPermanentlyDenied) {
                                R.string.permission_grant_in_settings
                            } else {
                                R.string.setup_perm_grant
                            },
                        ),
                        onGrant = {
                            if (state.backgroundLocationPermissionPermanentlyDenied) {
                                context.openApplicationSettings()
                                return@PermissionExplanation
                            }
                            backgroundLocationLauncher.launch(
                                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                            )
                        },
                    )
                } else {
                    PermissionGrantedBadge()
                }
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
                    buttonText = stringResource(
                        if (state.activityPermissionPermanentlyDenied) {
                            R.string.permission_grant_in_settings
                        } else {
                            R.string.setup_perm_grant
                        },
                    ),
                    onGrant = {
                        if (state.activityPermissionPermanentlyDenied) {
                            context.openApplicationSettings()
                            return@PermissionExplanation
                        }
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
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionGrantedBadge()
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// region Private composables

@Composable
private fun LocationPrecisionSelector(
    selectedMode: LocationPrecisionMode?,
    onModeSelected: (LocationPrecisionMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.location_precision_selector_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Text(
            text = stringResource(R.string.location_precision_selector_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(4.dp))

        PrecisionModeCard(
            mode = LocationPrecisionMode.APPROXIMATE,
            selected = selectedMode == LocationPrecisionMode.APPROXIMATE,
            onClick = { onModeSelected(LocationPrecisionMode.APPROXIMATE) },
            modifier = Modifier.testTag("precision_approximate_card"),
        )

        PrecisionModeCard(
            mode = LocationPrecisionMode.PRECISE,
            selected = selectedMode == LocationPrecisionMode.PRECISE,
            onClick = { onModeSelected(LocationPrecisionMode.PRECISE) },
            modifier = Modifier.testTag("precision_precise_card"),
        )
    }
}

@Composable
private fun PrecisionModeCard(
    mode: LocationPrecisionMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (icon, titleRes, descriptionRes, batteryImpact) = when (mode) {
        LocationPrecisionMode.APPROXIMATE -> PrecisionModeInfo(
            icon = Icons.Default.GpsNotFixed,
            title = R.string.location_precision_approximate_title,
            description = R.string.location_precision_approximate_description,
            batteryImpact = BatteryImpact.LOW,
        )

        LocationPrecisionMode.PRECISE -> PrecisionModeInfo(
            icon = Icons.Default.GpsFixed,
            title = R.string.location_precision_precise_title,
            description = R.string.location_precision_precise_description,
            batteryImpact = BatteryImpact.MODERATE,
        )
    }

    RadioCard(
        selected = selected,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = stringResource(descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            BatteryImpactIndicator(
                impact = batteryImpact,
                compact = true,
            )
        }
    }
}

private data class PrecisionModeInfo(
    val icon: ImageVector,
    val title: Int,
    val description: Int,
    val batteryImpact: BatteryImpact,
)

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
    buttonText: String? = null,
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
            Text(text = buttonText ?: stringResource(R.string.setup_perm_grant))
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

private fun Context.currentPermissionState(state: SetupUiState): SetupPermissionState {
    val fineLocationGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    val coarseLocationGranted = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    val foregroundLocationGranted = fineLocationGranted || coarseLocationGranted
    val backgroundLocationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    val activityRecognitionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
    val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        hasPermission(Manifest.permission.POST_NOTIFICATIONS)

    return SetupPermissionState(
        fineLocationGranted = fineLocationGranted,
        coarseLocationGranted = coarseLocationGranted,
        backgroundLocationGranted = backgroundLocationGranted,
        activityRecognitionGranted = activityRecognitionGranted,
        notificationGranted = notificationGranted,
        locationPermanentlyDenied = !foregroundLocationGranted &&
            state.locationPermissionDenied &&
            isForegroundLocationPermanentlyDenied(),
        backgroundLocationPermanentlyDenied = !backgroundLocationGranted &&
            state.backgroundLocationPermissionDenied &&
            isPermissionPermanentlyDenied(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        activityRecognitionPermanentlyDenied = !activityRecognitionGranted &&
            state.activityPermissionDenied &&
            isPermissionPermanentlyDenied(Manifest.permission.ACTIVITY_RECOGNITION),
    )
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.openApplicationSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

private fun Context.isForegroundLocationPermanentlyDenied(): Boolean {
    val activity = findActivity() ?: return false
    return !ActivityCompat.shouldShowRequestPermissionRationale(
        activity,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) && !ActivityCompat.shouldShowRequestPermissionRationale(
        activity,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
}

private fun Context.isPermissionPermanentlyDenied(permission: String): Boolean {
    val activity = findActivity() ?: return false
    return !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
