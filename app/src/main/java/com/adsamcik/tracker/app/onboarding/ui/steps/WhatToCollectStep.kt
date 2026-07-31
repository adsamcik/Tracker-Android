package com.adsamcik.tracker.app.onboarding.ui.steps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.onboarding.data.SetupUiState
import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionMode
import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionSelector
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.shared.utils.style.compose.LocalReducedMotion
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import com.adsamcik.tracker.shared.utils.style.compose.tweenStandard

/**
 * Step 3 – What to Collect.
 *
 * Data source toggles with inline permission explanations and grant buttons.
 * Styled with Material 3 Expressive: animated tonal icon badges, selected-state
 * accents, and spring-revealed sub-content that respects reduced-motion.
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
    onWifiPermissionResult: (Boolean) -> Unit,
    onCellPermissionResult: (Boolean) -> Unit,
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

    val wifiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        onWifiPermissionResult(results.values.any { it })
    }

    val cellLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val fineLocationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val phoneStateGranted = results[Manifest.permission.READ_PHONE_STATE] == true
        onCellPermissionResult(fineLocationGranted && phoneStateGranted)
    }

    SetupStepScaffold(
        actionText = stringResource(R.string.setup_continue),
        actionTestTag = "setup_cta_complete",
        onAction = onComplete,
        modifier = modifier,
        bottomPaddingTestTag = "setup_what_to_collect_scroll_bottom_padding",
    ) {
        Spacer(modifier = Modifier.height(RidgelineSpacing.Md))

        StepHeader()

        Spacer(modifier = Modifier.height(RidgelineSpacing.Md))

        // --- Location ---
        DataSourceCard(
            icon = Icons.Default.MyLocation,
            title = stringResource(R.string.setup_source_location),
            description = stringResource(R.string.setup_source_location_desc),
            enabled = state.locationEnabled,
            onToggle = onLocationEnabledChange,
        )

        PermissionDeniedSlot(
            visible = state.locationPermissionDenied,
            sourceName = stringResource(R.string.setup_source_location),
            testTag = "setup_perm_location_denied",
        )

        Reveal(visible = state.locationEnabled) {
            Column(
                modifier = Modifier.padding(top = RidgelineSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
            ) {
                LocationPrecisionSelector(
                    selectedMode = state.locationPrecision,
                    onModeSelected = onLocationPrecisionChange,
                )

                // Foreground location permission
                if (state.needsLocationPermission && !state.locationPermissionGranted) {
                    PermissionExplanation(
                        explanation = stringResource(R.string.setup_perm_location_why),
                        grantContentDescription = stringResource(R.string.setup_perm_grant_location_content_description),
                        rationaleTestTag = "setup_perm_location_rationale",
                        grantButtonTestTag = "setup_perm_location_grant",
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
                    PermissionExplanation(
                        explanation = stringResource(R.string.setup_perm_location_bg_why),
                        grantContentDescription = stringResource(R.string.setup_perm_grant_background_location_content_description),
                        rationaleTestTag = "setup_perm_background_location_rationale",
                        grantButtonTestTag = "setup_perm_background_location_grant",
                        onGrant = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                backgroundLocationLauncher.launch(
                                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                                )
                            }
                        },
                    )
                } else if (state.backgroundLocationGranted && state.needsBackgroundLocationPermission) {
                    PermissionGrantedBadge()
                }
            }
        }

        Spacer(modifier = Modifier.height(RidgelineSpacing.Md))

        // --- Activity ---
        DataSourceCard(
            icon = Icons.AutoMirrored.Filled.DirectionsRun,
            title = stringResource(R.string.setup_source_activity),
            description = stringResource(R.string.setup_source_activity_desc),
            enabled = state.activityEnabled,
            onToggle = onActivityEnabledChange,
        )

        PermissionDeniedSlot(
            visible = state.activityPermissionDenied,
            sourceName = stringResource(R.string.setup_source_activity),
            testTag = "setup_perm_activity_denied",
        )

        Reveal(visible = state.needsActivityPermission) {
            Column(modifier = Modifier.padding(top = RidgelineSpacing.Sm)) {
                if (!state.activityPermissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    PermissionExplanation(
                        explanation = stringResource(R.string.setup_perm_activity_why),
                        grantContentDescription = stringResource(R.string.setup_perm_grant_activity_content_description),
                        rationaleTestTag = "setup_perm_activity_rationale",
                        grantButtonTestTag = "setup_perm_activity_grant",
                        onGrant = {
                            activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                        },
                    )
                } else if (state.activityPermissionGranted) {
                    PermissionGrantedBadge()
                }
            }
        }

        Spacer(
            modifier = Modifier
                .height(RidgelineSpacing.Xxl)
                .testTag("setup_perm_activity_clearance_anchor"),
        )

        // --- Steps ---
        DataSourceCard(
            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
            title = stringResource(R.string.setup_source_steps),
            description = stringResource(
                if (state.stepCounterAvailable) {
                    R.string.setup_source_steps_desc
                } else {
                    R.string.settings_steps_unavailable
                },
            ),
            enabled = state.stepsEnabled,
            onToggle = onStepsEnabledChange,
            available = state.stepCounterAvailable,
        )

        Spacer(modifier = Modifier.height(RidgelineSpacing.Md))

        // --- WiFi ---
        DataSourceCard(
            icon = Icons.Default.Wifi,
            title = stringResource(R.string.setup_source_wifi),
            description = stringResource(R.string.setup_source_wifi_desc),
            enabled = state.wifiEnabled,
            onToggle = onWifiEnabledChange,
        )

        PermissionDeniedSlot(
            visible = state.wifiPermissionDenied,
            sourceName = stringResource(R.string.setup_source_wifi),
            testTag = "setup_perm_wifi_denied",
        )

        Reveal(visible = state.wifiEnabled) {
            Column(modifier = Modifier.padding(top = RidgelineSpacing.Sm)) {
                if (state.needsWifiPermission) {
                    PermissionExplanation(
                        explanation = stringResource(R.string.setup_perm_wifi_why),
                        grantContentDescription = stringResource(R.string.setup_perm_grant_wifi_content_description),
                        rationaleTestTag = "setup_perm_wifi_rationale",
                        grantButtonTestTag = "setup_perm_wifi_grant",
                        onGrant = {
                            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                            } else {
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            }
                            wifiLauncher.launch(perms)
                        },
                    )
                } else {
                    PermissionGrantedBadge()
                }
            }
        }

        Spacer(modifier = Modifier.height(RidgelineSpacing.Md))

        // --- Cell ---
        DataSourceCard(
            icon = Icons.Default.CellTower,
            title = stringResource(R.string.setup_source_cell),
            description = stringResource(R.string.setup_source_cell_desc),
            enabled = state.cellEnabled,
            onToggle = onCellEnabledChange,
        )

        PermissionDeniedSlot(
            visible = state.cellPermissionDenied,
            sourceName = stringResource(R.string.setup_source_cell),
            testTag = "setup_perm_cell_denied",
        )

        Reveal(visible = state.cellEnabled) {
            Column(modifier = Modifier.padding(top = RidgelineSpacing.Sm)) {
                if (state.needsCellPermission) {
                    PermissionExplanation(
                        explanation = stringResource(R.string.setup_perm_cell_why),
                        grantContentDescription = stringResource(R.string.setup_perm_grant_cell_content_description),
                        rationaleTestTag = "setup_perm_cell_rationale",
                        grantButtonTestTag = "setup_perm_cell_grant",
                        onGrant = {
                            cellLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.READ_PHONE_STATE,
                                ),
                            )
                        },
                    )
                } else {
                    PermissionGrantedBadge()
                }
            }
        }

        // Notification permission (always relevant)
        Spacer(modifier = Modifier.height(RidgelineSpacing.Xl))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !state.notificationPermissionGranted
        ) {
            PermissionExplanation(
                explanation = stringResource(R.string.setup_perm_notification_why),
                grantContentDescription = stringResource(R.string.setup_perm_grant_notification_content_description),
                rationaleTestTag = "setup_perm_notification_rationale",
                grantButtonTestTag = "setup_perm_notification_grant",
                onGrant = {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            )
        }
    }
}

// region Private composables

@Composable
private fun StepHeader(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Lg),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(30.dp),
                )
            }

            Text(
                text = stringResource(R.string.setup_what_to_collect_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(RidgelineSpacing.Md))

        Text(
            text = stringResource(R.string.setup_what_to_collect_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Expressive, springy reveal for permission/precision sub-content.
 * Collapses to no transition under reduced-motion.
 */
@Composable
private fun ColumnScope.Reveal(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = if (reducedMotion) fadeIn(snap()) else expandVertically() + fadeIn(),
        exit = if (reducedMotion) fadeOut(snap()) else shrinkVertically() + fadeOut(),
    ) {
        content()
    }
}

/**
 * Animated slot that surfaces a recoverable alert when a source was auto-disabled
 * because the user denied its runtime permission.
 */
@Composable
private fun ColumnScope.PermissionDeniedSlot(
    visible: Boolean,
    sourceName: String,
    testTag: String,
) {
    Reveal(visible = visible) {
        Column(modifier = Modifier.padding(top = RidgelineSpacing.Sm)) {
            PermissionDeniedAlert(sourceName = sourceName, testTag = testTag)
        }
    }
}

@Composable
private fun PermissionDeniedAlert(
    sourceName: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(
                start = RidgelineSpacing.Lg,
                top = RidgelineSpacing.Md,
                end = RidgelineSpacing.Sm,
                bottom = RidgelineSpacing.Md,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Md),
        ) {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )

            Text(
                text = stringResource(R.string.permission_disabled_due_to_denial, sourceName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .weight(1f)
                    .testTag("${testTag}_text"),
            )

            TextButton(onClick = { context.openAppSettings() }) {
                Text(
                    text = stringResource(R.string.permission_grant_in_settings),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun DataSourceCard(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    available: Boolean = true,
) {
    val reducedMotion = LocalReducedMotion.current
    val colorSpec: AnimationSpec<Color> = if (reducedMotion) snap() else tweenStandard()

    val borderColor by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = colorSpec,
        label = "data_source_border",
    )
    val iconBackground by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = colorSpec,
        label = "data_source_icon_background",
    )
    val iconTint by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = colorSpec,
        label = "data_source_icon_tint",
    )

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .toggleable(
                value = enabled,
                enabled = available,
                role = androidx.compose.ui.semantics.Role.Switch,
                onValueChange = onToggle,
            )
            .border(BorderStroke(1.5.dp, borderColor), MaterialTheme.shapes.large),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Lg),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(iconBackground),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Switch(
                checked = enabled,
                onCheckedChange = null,
                enabled = available,
                thumbContent = if (enabled) {
                    {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun PermissionExplanation(
    explanation: String,
    grantContentDescription: String,
    rationaleTestTag: String,
    grantButtonTestTag: String,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(rationaleTestTag),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = RidgelineSpacing.Lg,
                vertical = RidgelineSpacing.Md,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Md),
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )

            Text(
                text = explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .weight(1f)
                    .testTag("${rationaleTestTag}_text"),
            )

            Button(
                onClick = onGrant,
                modifier = Modifier
                    .testTag(grantButtonTestTag)
                    .semantics { contentDescription = grantContentDescription },
            ) {
                Text(text = stringResource(R.string.setup_perm_grant))
            }
        }
    }
}

@Composable
private fun PermissionGrantedBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = RidgelineSpacing.Md,
                vertical = RidgelineSpacing.Sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Xs),
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.setup_perm_granted),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

private fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

// endregion
