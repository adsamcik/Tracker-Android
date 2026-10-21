package com.adsamcik.tracker.app.settings.tracking

import com.adsamcik.tracker.app.background.BackgroundReliabilitySettingsEntry

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.adsamcik.tracker.app.settings.TrackingSettingsUiState
import com.adsamcik.tracker.app.settings.TrackingSettingsViewModel
import com.adsamcik.tracker.app.settings.LocationSettingsPermissionKind
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.utils.compose.permission.PermissionDeniedSnackbar
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.app.settings.components.SectionHeader
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.app.settings.components.SettingsGroupCard
import com.adsamcik.tracker.app.settings.components.SettingsRowDivider
import com.adsamcik.tracker.app.settings.components.SliderSettingsItemWithHelp
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItemWithHelp
import com.adsamcik.tracker.app.settings.ui.TrackingPresetSelector
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.shared.preferences.tracking.LocationBackgroundMode
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.source.coordinator.EffectiveSourceState
import com.adsamcik.tracker.tracker.source.model.SourceKind

@Composable
fun TrackingSettingsScreen(onNavigateToNotificationManagement: () -> Unit = {}) {
    val trackingVm: TrackingSettingsViewModel = hiltViewModel()
    val lifecycleOwner = LocalLifecycleOwner.current
	val context = LocalContext.current
	var permissionDenied by remember { mutableStateOf(false) }
	val permissionSnackbar = remember { SnackbarHostState() }
	val locationAccessLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
		trackingVm.onLocationPermissionResult()
		permissionDenied = !trackingVm.uiState.value.permissionCapabilities.hasForegroundLocation
	}
	val backgroundAccessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
		trackingVm.onLocationPermissionResult()
		permissionDenied = !trackingVm.uiState.value.permissionCapabilities.hasBackgroundLocation
	}
	val locationSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		trackingVm.onLocationPermissionResult()
	}
	val motionAssistanceLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.RequestPermission(),
		{ granted ->
			trackingVm.onActivityPermissionResult(granted)
			permissionDenied = !trackingVm.uiState.value.activityPermissionGranted
		},
	)
	LaunchedEffect(trackingVm, context) {
		trackingVm.locationPermissionEvents.collect { request ->
			if (!trackingVm.isCurrentLocationPermissionRequest(request) ||
				!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
			) {
				return@collect
			}
			when (request.kind) {
				LocationSettingsPermissionKind.MOTION -> {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
						!trackingVm.uiState.value.activityPermissionGranted
					) {
						motionAssistanceLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
					}
				}
				LocationSettingsPermissionKind.FOREGROUND_LOCATION,
				LocationSettingsPermissionKind.LOCATION -> {
					val capability = trackingVm.uiState.value.permissionCapabilities
					when {
						!capability.coarseLocationGranted && !capability.preciseLocationGranted ->
							locationAccessLauncher.launch(arrayOf(
								Manifest.permission.ACCESS_COARSE_LOCATION,
								Manifest.permission.ACCESS_FINE_LOCATION,
							))
						!capability.locationServicesEnabled ->
							locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
						request.kind == LocationSettingsPermissionKind.LOCATION &&
							Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && !capability.backgroundLocationGranted ->
							backgroundAccessLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
						request.kind == LocationSettingsPermissionKind.LOCATION &&
							Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !capability.backgroundLocationGranted ->
							locationSettingsLauncher.launch(Intent(
								Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
								Uri.parse("package:${context.packageName}"),
							))
					}
				}
			}
		}
	}

    // Single consolidated state
    val uiState by trackingVm.uiState.collectAsState()
    DisposableEffect(lifecycleOwner, trackingVm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                trackingVm.onSettingsResumed()
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
        trackingVm.onWifiPermissionResult(results[Manifest.permission.ACCESS_FINE_LOCATION] == true)
		permissionDenied = !trackingVm.uiState.value.wifiPermissionGranted
    }
    val cellPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val fineLocationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val phoneStateGranted = results[Manifest.permission.READ_PHONE_STATE] == true
        trackingVm.onCellPermissionResult(fineLocationGranted && phoneStateGranted)
		permissionDenied = !trackingVm.uiState.value.cellPermissionGranted
    }
    val activityPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        { granted ->
			trackingVm.onActivityPermissionResult(granted)
			permissionDenied = !trackingVm.uiState.value.activityPermissionGranted
		},
    )
    val stepsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        { granted ->
			trackingVm.onStepsPermissionResult(granted)
			permissionDenied = !trackingVm.uiState.value.activityPermissionGranted
		},
    )
	val healthConnectStepsLauncher = rememberLauncherForActivityResult(
		PermissionController.createRequestPermissionResultContract(),
	) { trackingVm.refreshStepsProviderPermissionState() }
    var pendingAutoTrackingMode by remember { mutableStateOf<Int?>(null) }
    val autoTrackingPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val requestedMode = pendingAutoTrackingMode
        pendingAutoTrackingMode = null
        if (requestedMode != null) {
            trackingVm.onAutoTrackingPermissionResult(requestedMode, granted)
			permissionDenied = !trackingVm.uiState.value.activityPermissionGranted
        }
    }
	val requestSourcePermission: (TrackingSourceComponent) -> Unit = { source ->
		if (trackingVm.isSavedSourceSelected(source) &&
			lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
		) {
			when (source) {
				TrackingSourceComponent.LOCATION -> trackingVm.requestSavedLocationPermission(
					LocationSettingsPermissionKind.FOREGROUND_LOCATION,
				)
				TrackingSourceComponent.ACTIVITY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					activityPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
				}
				TrackingSourceComponent.STEPS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					stepsPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
				}
				TrackingSourceComponent.WIFI -> wifiPermissionLauncher.launch(arrayOf(
					Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
				))
				TrackingSourceComponent.CELL -> cellPermissionLauncher.launch(arrayOf(
					Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
					Manifest.permission.READ_PHONE_STATE,
				))
				TrackingSourceComponent.PRESSURE -> Unit
			}
		}
	}

	Box(Modifier.fillMaxSize()) {
    TrackingSettingsContent(
        uiState = uiState,
        onTurnOffTracking = trackingVm::turnOffTracking,
        onRetrySourceRecovery = trackingVm::retrySourcePolicyReconciliation,
		onBalanceSelected = trackingVm::adoptBalanceProfile,
		onSourceBalanceSelected = trackingVm::setSourceBalanceOverride,
        onAutoTrackingModeChanged = { mode ->
            trackingVm.setAutoTrackingMode(mode) {
                val current = trackingVm.uiState.value
                val needsActivityPermission = mode > 0 &&
                    !current.activityPermissionGranted
                if (needsActivityPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    pendingAutoTrackingMode = mode
                    autoTrackingPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
            }
        },
        onTransitionDetectionChanged = { trackingVm.setTransitionDetectionEnabled(it) },
        onNotificationStyledChanged = { trackingVm.setNotificationStyled(it) },
        onMinDistanceChanged = { trackingVm.setMinDistance(it) },
        onMinTimeChanged = { trackingVm.setMinTime(it) },
        onRequiredAccuracyChanged = { trackingVm.setRequiredAccuracy(it) },
        onLocationEnabledChanged = { trackingVm.setLocationEnabled(it) },
		onSourcePermission = requestSourcePermission,
		onAutomaticPermission = { requestSourcePermission(TrackingSourceComponent.ACTIVITY) },
        onActivityEnabledChanged = { enabled ->
            trackingVm.setActivityEnabled(enabled) {
                if (enabled && !trackingVm.uiState.value.activityPermissionGranted &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    activityPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
            }
        },
        onStepsEnabledChanged = { enabled ->
            trackingVm.setStepsEnabled(enabled) {
                if (enabled && trackingVm.uiState.value.shouldRequestStepsMotionPermission(
                        Build.VERSION.SDK_INT,
                        lifecycleOwner.lifecycle.currentState,
                        context.hasActivityPermission,
                    )
                ) {
                    stepsPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
            }
        },
		onStepsProviderPermission = {
			if (trackingVm.isSavedSourceSelected(TrackingSourceComponent.STEPS)) {
				val current = trackingVm.uiState.value
			val reason = current.ambientSourceAvailability.getValue(AmbientTrackingSource.STEPS).reason
			if (current.shouldRequestStepsMotionPermission(
					Build.VERSION.SDK_INT, lifecycleOwner.lifecycle.currentState, context.hasActivityPermission,
				)) {
				stepsPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
			} else if (current.stepsEnabled && reason in setOf(
					AmbientSourceUnavailableReason.HEALTH_CONNECT_STEPS_PERMISSION_REQUIRED,
					AmbientSourceUnavailableReason.HEALTH_CONNECT_BACKGROUND_PERMISSION_OPTIONAL,
				) &&
				lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
			) {
				val available = try {
					HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
				} catch (_: RuntimeException) {
					false
				} catch (_: LinkageError) {
					false
				}
				if (available) {
					try {
						val client = HealthConnectClient.getOrCreate(context)
						val permissions = buildSet {
							add(HealthPermission.getReadPermission(StepsRecord::class))
							if (client.features.getFeatureStatus(
								HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
							) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
								add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
							}
						}
						healthConnectStepsLauncher.launch(permissions)
					} catch (_: RuntimeException) {
						trackingVm.refreshStepsProviderPermissionState()
					} catch (_: LinkageError) {
						trackingVm.refreshStepsProviderPermissionState()
					}
				} else {
					trackingVm.refreshStepsProviderPermissionState()
				}
			}
			}
		},
        onBarometerEnabledChanged = { trackingVm.setBarometerEnabled(it) },
        onRetrySettingsSave = trackingVm::retryFailedSettings,
		onAmbientLocationEnabledChanged = { trackingVm.setAmbientLocationEnabled(it) },
		onLocationModeChanged = trackingVm::setLocationBackgroundMode,
		onLocationBalanceChanged = trackingVm::adoptLocationProfile,
		onLocationAssistanceChanged = trackingVm::setLocationMotionAssistanceEnabled,
		onLocationPermission = { trackingVm.requestSavedLocationPermission(LocationSettingsPermissionKind.LOCATION) },
		onMotionPermission = { trackingVm.requestSavedLocationPermission(LocationSettingsPermissionKind.MOTION) },
		onAmbientWifiEnabledChanged = { trackingVm.setAmbientWifiEnabled(it) },
		onAmbientCellEnabledChanged = { trackingVm.setAmbientCellEnabled(it) },
		onWifiNotificationChanged = trackingVm::setBackgroundWifiNotificationEnabled,
		onCellNotificationChanged = trackingVm::setBackgroundCellNotificationEnabled,
        onAdvancedSourceControlsChanged = { trackingVm.setAdvancedSourceControlsEnabled(it) },
        onSourceFrequencyChanged = trackingVm::setSourceFrequency,
        onWifiEnabledChanged = { enabled ->
            trackingVm.setWifiEnabled(enabled) {
                if (enabled && !trackingVm.uiState.value.wifiPermissionGranted &&
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    wifiPermissionLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ))
                }
            }
        },
        onCellEnabledChanged = { enabled ->
            trackingVm.setCellEnabled(enabled) {
                if (enabled && !trackingVm.uiState.value.cellPermissionGranted &&
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    cellPermissionLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE,
                    ))
                }
            }
        },
        onNotificationCustomize = onNavigateToNotificationManagement,
    )
	if (permissionDenied) {
		PermissionDeniedSnackbar(
			permissionSnackbar,
			stringResource(com.adsamcik.tracker.R.string.settings_permission_still_needed),
			onDismissed = { permissionDenied = false },
		)
		SnackbarHost(permissionSnackbar, Modifier.align(Alignment.BottomCenter))
	}
	}
}

@Composable
internal fun TrackingSettingsContent(
    uiState: TrackingSettingsUiState,
    onTurnOffTracking: () -> Unit = {},
    onRetrySourceRecovery: () -> Unit = {},
	onBalanceSelected: (Int) -> Unit = {},
	onSourceBalanceSelected: (TrackingSourceComponent, Int?) -> Unit = { _, _ -> },
    onAutoTrackingModeChanged: (Int) -> Unit = {},
    onTransitionDetectionChanged: (Boolean) -> Unit = {},
    onNotificationStyledChanged: (Boolean) -> Unit = {},
    onMinDistanceChanged: (Int) -> Unit = {},
    onMinTimeChanged: (Int) -> Unit = {},
    onRequiredAccuracyChanged: (Int) -> Unit = {},
    onLocationEnabledChanged: (Boolean) -> Unit = {},
	onSourcePermission: (TrackingSourceComponent) -> Unit = {},
	onAutomaticPermission: () -> Unit = {},
    onActivityEnabledChanged: (Boolean) -> Unit = {},
    onStepsEnabledChanged: (Boolean) -> Unit = {},
	onStepsProviderPermission: () -> Unit = {},
    onBarometerEnabledChanged: (Boolean) -> Unit = {},
    onRetrySettingsSave: () -> Unit = {},
	onAmbientLocationEnabledChanged: (Boolean) -> Unit = {},
	onLocationModeChanged: (LocationBackgroundMode) -> Unit = {},
	onLocationBalanceChanged: (Int) -> Unit = {},
	onLocationAssistanceChanged: (Boolean) -> Unit = {},
	onLocationPermission: () -> Unit = {},
	onMotionPermission: () -> Unit = {},
	onAmbientWifiEnabledChanged: (Boolean) -> Unit = {},
	onAmbientCellEnabledChanged: (Boolean) -> Unit = {},
	onWifiNotificationChanged: (Boolean) -> Unit = {},
	onCellNotificationChanged: (Boolean) -> Unit = {},
    onWifiEnabledChanged: (Boolean) -> Unit = {},
    onCellEnabledChanged: (Boolean) -> Unit = {},
    onAdvancedSourceControlsChanged: (Boolean) -> Unit = {},
    onSourceFrequencyChanged: (TrackingSourceComponent, SourceCollectionFrequency) -> Unit = { _, _ -> },
    onNotificationCustomize: () -> Unit = {},
) {
    val locale = LocalConfiguration.current.locales[0]
    val meterAbbreviation = stringResource(com.adsamcik.tracker.shared.preferences.R.string.meter_abbr)
    val secondAbbreviation = stringResource(com.adsamcik.tracker.shared.base.R.string.second_short)
    if (!uiState.isLoaded) {
        return
    }
    if (!uiState.sourcePolicyAvailable) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("trackingSettingsUnavailable"),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(
                                    com.adsamcik.tracker.R.string.settings_source_policy_unavailable_title,
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                stringResource(
                                    com.adsamcik.tracker.R.string.settings_source_policy_unavailable_summary,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            TextButton(onClick = onRetrySourceRecovery, modifier =
                                Modifier.testTag("trackingSourceRecoveryRetry")) {
                                Text(stringResource(com.adsamcik.tracker.R.string.settings_save_retry))
                            }
                        }
                    }
                }
            }
            item { TrackingTurnOffSetting(uiState, onTurnOffTracking) }
        }
        return
    }
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
        if (uiState.settingsSaveFailed) {
            item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("trackingSettingsSaveFailure"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(com.adsamcik.tracker.R.string.settings_changes_not_saved),
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = onRetrySettingsSave, modifier =
                        Modifier.testTag("trackingSettingsSaveRetry")) {
                        Text(stringResource(com.adsamcik.tracker.R.string.settings_save_retry))
                    }
                }
            }
        }
        }
        if (uiState.sourcePolicyReconciliationDebt != null) {
            item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("trackingSourceRecoveryFailure"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(com.adsamcik.tracker.R.string.settings_choices_not_applied),
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = onRetrySourceRecovery, modifier =
                        Modifier.testTag("trackingSourceRecoveryRetry")) {
                        Text(stringResource(com.adsamcik.tracker.R.string.settings_save_retry))
                    }
                }
            }
        }
        }
        // Changes reconcile into the active source flow; status below reports any blockers.
        if (uiState.trackingActive) {
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
                        stringResource(com.adsamcik.tracker.R.string.settings_tracking_changes_apply_live),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
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
        item { TrackingTurnOffSetting(uiState, onTurnOffTracking) }
        item {
            AutoTrackingModeSelector(
                selectedMode = uiState.autoTrackingMode.coerceIn(0, 2),
                onModeSelected = onAutoTrackingModeChanged,
            )
        }
		item {
			AutomaticTrackingAvailabilityCard(uiState, onPermission = onAutomaticPermission,
				onRetry = onRetrySourceRecovery, onEnableTransitions = {
				onTransitionDetectionChanged(true)
			})
		}
        if (uiState.automaticTrackingOperational && uiState.locationEnabled &&
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
        if (uiState.automaticTrackingOperational) {
            item {
                SwitchSettingsItemWithHelp(
                    title = stringResource(com.adsamcik.tracker.R.string.settings_transition_detection_friendly_title),
                    subtitle =
                        stringResource(com.adsamcik.tracker.R.string.settings_transition_detection_friendly_summary),
                    checked = uiState.transitionDetectionEnabled,
                    onCheckedChange = onTransitionDetectionChanged,
                    helpTextRes = com.adsamcik.tracker.tracker.R.string.help_transition_detection,
                )
            }
        }

        // Battery optimization and vendor restrictions affect both automatic and manual trips.
        item {
            BackgroundReliabilitySettingsEntry(
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
            SourceBalanceSettingsItem(uiState, onBalanceSelected)
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
                if (uiState.advancedSourceControlsEnabled && uiState.balanceProfileVersion == null) {
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

        if (uiState.advancedSourceControlsEnabled && uiState.balanceProfileVersion == null) {
            item {
                AdvancedDataSourceSettings(
                    uiState = uiState,
                    onSourceFrequencyChanged = onSourceFrequencyChanged,
					onLocationEnabledChanged = onLocationEnabledChanged,
					onStepsProviderPermission = onStepsProviderPermission,
					onSourcePermission = onSourcePermission,
                    onRetrySourceRecovery = onRetrySourceRecovery,
                )
            }
        } else {
            item {
                SimpleDataSourceSettings(
                    uiState = uiState,
                    onLocationEnabledChanged = onLocationEnabledChanged,
                    onActivityEnabledChanged = onActivityEnabledChanged,
                    onStepsEnabledChanged = onStepsEnabledChanged,
					onStepsProviderPermission = onStepsProviderPermission,
					onSourcePermission = onSourcePermission,
                    onBarometerEnabledChanged = onBarometerEnabledChanged,
                    onWifiEnabledChanged = onWifiEnabledChanged,
                    onCellEnabledChanged = onCellEnabledChanged,
					onSourceBalanceSelected = onSourceBalanceSelected,
                    onRetrySourceRecovery = onRetrySourceRecovery,
                )
            }
            if (uiState.balanceProfileVersion == null) {
                item {
                SettingsGroupCard(modifier = Modifier.padding(top = 8.dp)) {
                    SettingsItem(
                        title = stringResource(com.adsamcik.tracker.R.string.settings_advanced_source_controls_title),
                        subtitle =
                            stringResource(com.adsamcik.tracker.R.string.settings_advanced_source_controls_summary),
                        icon = Icons.Default.Tune,
                        onClick = { onAdvancedSourceControlsChanged(true) },
                    )
                }
            }
            }
        }

        // These parameters only control location requests/filtering, not other source cadences.
        if (uiState.advancedSourceControlsEnabled && uiState.locationEnabled &&
            uiState.locationProfileVersion == null) {
            item {
                SectionHeader(
                    stringResource(com.adsamcik.tracker.R.string.settings_tracking_section_detail),
                    icon = Icons.Default.Tune,
                )
            }
            item {
                SettingsGroupCard {
                    SliderSettingsItemWithHelp(
                        title =
                            stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_min_distance_title),
                        value = uiState.minDistance.toFloat(),
                        valueRange = 0f..200f,
                        steps = 19,
                        valueLabel = {
                            String.format(locale, meterAbbreviation,
                                String.format(locale, "%d", it.toInt()))
                        },
                        onValueChange = { onMinDistanceChanged(it.toInt()) },
                        helpTextRes = com.adsamcik.tracker.tracker.R.string.help_min_distance,
                    )
                    SettingsRowDivider()
                    SliderSettingsItemWithHelp(
                        title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_tracking_min_time_title),
                        value = uiState.minTime.toFloat(),
                        valueRange = 0f..60f,
                        steps = 11,
                        valueLabel = { String.format(locale, secondAbbreviation, it.toInt()) },
                        onValueChange = { onMinTimeChanged(it.toInt()) },
                        helpTextRes = com.adsamcik.tracker.tracker.R.string.help_min_time,
                    )
                    SettingsRowDivider()
                    SliderSettingsItemWithHelp(
                        title =
                            stringResource(
                                com.adsamcik.tracker.tracker.R.string.settings_tracking_required_accuracy_title
                            ),
                        value = uiState.requiredAccuracy.toFloat(),
                        valueRange = 10f..200f,
                        steps = 18,
                        valueLabel = {
                            String.format(locale, meterAbbreviation,
                                String.format(locale, "%d", it.toInt()))
                        },
                        onValueChange = { onRequiredAccuracyChanged(it.toInt()) },
                        helpTextRes = com.adsamcik.tracker.tracker.R.string.help_required_accuracy,
                    )
                }
            }
        }
		item {
			SectionHeader(
				stringResource(com.adsamcik.tracker.R.string.settings_tracking_section_ambient),
				icon = Icons.AutoMirrored.Filled.DirectionsWalk,
			)
		}
		item {
			AmbientSourcePurposeSettings(
				uiState = uiState,
				onLocationEnabledChanged = onAmbientLocationEnabledChanged,
				onLocationModeChanged = onLocationModeChanged,
				onLocationBalanceChanged = onLocationBalanceChanged,
				onLocationAssistanceChanged = onLocationAssistanceChanged,
				onLocationPermission = onLocationPermission,
				onMotionPermission = onMotionPermission,
				onSourcePermission = onSourcePermission,
				onWifiEnabledChanged = onAmbientWifiEnabledChanged,
				onCellEnabledChanged = onAmbientCellEnabledChanged,
				onWifiNotificationChanged = onWifiNotificationChanged,
				onCellNotificationChanged = onCellNotificationChanged,
			)
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
                    subtitle =
                        stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_styled_summary),
                    checked = uiState.notificationStyled,
                    onCheckedChange = onNotificationStyledChanged,
                )
                SettingsRowDivider()
                SettingsItem(
                    title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_customize_title),
                    subtitle =
                        stringResource(com.adsamcik.tracker.tracker.R.string.settings_notification_customize_summary),
                    icon = Icons.Default.Notifications,
                    onClick = onNotificationCustomize,
                )
            }
        }
    }
}

@Composable
// Keep the six selected-source controls and their matching permission/status labels in one declaration.
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun SimpleDataSourceSettings(
    uiState: TrackingSettingsUiState,
    onLocationEnabledChanged: (Boolean) -> Unit,
    onActivityEnabledChanged: (Boolean) -> Unit,
    onStepsEnabledChanged: (Boolean) -> Unit,
	onStepsProviderPermission: () -> Unit,
    onBarometerEnabledChanged: (Boolean) -> Unit,
    onWifiEnabledChanged: (Boolean) -> Unit,
    onCellEnabledChanged: (Boolean) -> Unit,
	onSourceBalanceSelected: (TrackingSourceComponent, Int?) -> Unit,
	onSourcePermission: (TrackingSourceComponent) -> Unit,
    onRetrySourceRecovery: () -> Unit,
) {
    SettingsGroupCard {
        SwitchSettingsItem(
            title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_location_enabled_title),
            subtitle = stringResource(com.adsamcik.tracker.R.string.settings_location_source_summary),
            iconRes = com.adsamcik.tracker.R.drawable.ic_tracking_source_location,
            checked = uiState.locationEnabled,
            onCheckedChange = onLocationEnabledChanged,
        )

		SourceBalanceOverrideItem(TrackingSourceComponent.LOCATION, uiState, onSourceBalanceSelected)
		SourcePermissionRepair(TrackingSourceComponent.LOCATION,
			uiState.locationEnabled && (!uiState.permissionCapabilities.hasForegroundLocation ||
				!uiState.permissionCapabilities.locationServicesEnabled), onSourcePermission)
        SettingsRowDivider()
        SwitchSettingsItem(
            title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_activity_enabled_title),
            subtitle = sourceSummaryWithAllDayStatus(
                summaryRes = com.adsamcik.tracker.R.string.settings_activity_source_summary,
                statusRes = if (uiState.activityPermissionGranted) {
                    null
                } else {
                    com.adsamcik.tracker.R.string.settings_activity_permission_required
                },
                selected = uiState.activityEnabled,
                availability = uiState.ambientSourceAvailability.getValue(AmbientTrackingSource.ACTIVITY),
            ),
            iconRes = com.adsamcik.tracker.R.drawable.ic_tracking_source_activity,
            checked = uiState.activityEnabled,
            onCheckedChange = onActivityEnabledChanged,
        )

		SourceBalanceOverrideItem(TrackingSourceComponent.ACTIVITY, uiState, onSourceBalanceSelected)
		SourcePermissionRepair(TrackingSourceComponent.ACTIVITY,
			uiState.activityEnabled && !uiState.activityPermissionGranted, onSourcePermission)
        AllDaySensorRecovery(AmbientTrackingSource.ACTIVITY, uiState.activityEnabled,
            uiState.ambientSourceAvailability.getValue(AmbientTrackingSource.ACTIVITY), onRetrySourceRecovery)
        SettingsRowDivider()
        SwitchSettingsItem(
            title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_steps_enabled_title),
            subtitle = stringResource(com.adsamcik.tracker.R.string.settings_steps_source_summary) +
                if (uiState.stepsEnabled) {
                    val availability = uiState.ambientSourceAvailability.getValue(AmbientTrackingSource.STEPS)
                    " " + if (availability.reason == AmbientSourceUnavailableReason.RECONCILIATION_PENDING) {
                        stringResource(com.adsamcik.tracker.R.string.settings_steps_status_checking)
                    } else {
                        ambientOperationalSummary(availability)
                    }
                } else {
                    ""
                },
            iconRes = com.adsamcik.tracker.R.drawable.ic_tracking_source_steps,
            checked = uiState.stepsEnabled,
            onCheckedChange = onStepsEnabledChanged,
        )
		StepsProviderPermissionRepair(uiState, onStepsProviderPermission)

		SourceBalanceOverrideItem(TrackingSourceComponent.STEPS, uiState, onSourceBalanceSelected)
        SettingsRowDivider()
        SwitchSettingsItem(
            title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_barometer_enabled_title),
            subtitle = sourceSummaryWithAllDayStatus(
                summaryRes = com.adsamcik.tracker.R.string.settings_barometer_source_summary,
                statusRes = if (uiState.barometerAvailable) {
                    null
                } else {
                    com.adsamcik.tracker.R.string.settings_barometer_unavailable
                },
                selected = uiState.barometerEnabled,
                availability = uiState.ambientSourceAvailability.getValue(AmbientTrackingSource.PRESSURE),
            ),
            iconRes = com.adsamcik.tracker.R.drawable.ic_tracking_source_pressure,
            checked = uiState.barometerEnabled,
            onCheckedChange = onBarometerEnabledChanged,
        )

		SourceBalanceOverrideItem(TrackingSourceComponent.PRESSURE, uiState, onSourceBalanceSelected)
        AllDaySensorRecovery(AmbientTrackingSource.PRESSURE, uiState.barometerEnabled,
            uiState.ambientSourceAvailability.getValue(AmbientTrackingSource.PRESSURE), onRetrySourceRecovery)
        SettingsRowDivider()
        SwitchSettingsItem(
            title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_wifi_enabled_title),
            subtitle = sourceSummaryWithOptionalStatus(
                summaryRes = com.adsamcik.tracker.R.string.settings_wifi_source_summary,
                statusRes = if (uiState.wifiPermissionGranted) {
                    null
                } else {
                    com.adsamcik.tracker.R.string.settings_wifi_permission_required
                },
            ),
            iconRes = com.adsamcik.tracker.R.drawable.ic_tracking_source_wifi,
            checked = uiState.wifiEnabled,
            onCheckedChange = onWifiEnabledChanged,
        )

		SourceBalanceOverrideItem(TrackingSourceComponent.WIFI, uiState, onSourceBalanceSelected)
		SourcePermissionRepair(TrackingSourceComponent.WIFI,
			uiState.wifiEnabled && !uiState.wifiPermissionGranted, onSourcePermission)
        SettingsRowDivider()
        SwitchSettingsItem(
            title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_cell_enabled_title),
            subtitle = sourceSummaryWithOptionalStatus(
                summaryRes = com.adsamcik.tracker.R.string.settings_cell_source_summary,
                statusRes = if (uiState.cellPermissionGranted) {
                    null
                } else {
                    com.adsamcik.tracker.R.string.settings_cell_permission_required
                },
            ),
            iconRes = com.adsamcik.tracker.R.drawable.ic_tracking_source_cell,
            checked = uiState.cellEnabled,
            onCheckedChange = onCellEnabledChanged,
        )
		SourceBalanceOverrideItem(TrackingSourceComponent.CELL, uiState, onSourceBalanceSelected)
		SourcePermissionRepair(TrackingSourceComponent.CELL,
			uiState.cellEnabled && !uiState.cellPermissionGranted, onSourcePermission)
    }
}

@Composable
internal fun SourcePermissionRepair(
	source: TrackingSourceComponent,
	required: Boolean,
	onPermission: (TrackingSourceComponent) -> Unit,
	testTag: String = "sourcePermission-${source.name.lowercase()}",
) {
	if (!required) {
		return
	}
	TextButton(onClick = { onPermission(source) }, modifier = Modifier.testTag(testTag)) {
		Text(stringResource(com.adsamcik.tracker.R.string.settings_location_allow_access))
	}
}

@Composable
// Keep the finite Steps permission-repair states with their button, accessible name and error label.
@Suppress("CyclomaticComplexMethod")
private fun StepsProviderPermissionRepair(
	uiState: TrackingSettingsUiState,
	onClick: () -> Unit,
) {
	val reason = uiState.ambientSourceAvailability.getValue(AmbientTrackingSource.STEPS).reason
	val motionRepair = uiState.needsStepsMotionPermission(uiState.activityPermissionGranted)
	if (!uiState.stepsEnabled || (!motionRepair && reason !in setOf(
		AmbientSourceUnavailableReason.LOCAL_RECORDING_ACTIVITY_PERMISSION_REQUIRED,
		AmbientSourceUnavailableReason.HEALTH_CONNECT_STEPS_PERMISSION_REQUIRED,
		AmbientSourceUnavailableReason.HEALTH_CONNECT_BACKGROUND_PERMISSION_OPTIONAL,
	))) {
		return
	}
	val label = stringResource(when {
		motionRepair ->
			com.adsamcik.tracker.R.string.setup_perm_grant_steps_content_description
		reason == AmbientSourceUnavailableReason.HEALTH_CONNECT_BACKGROUND_PERMISSION_OPTIONAL ->
			com.adsamcik.tracker.R.string.settings_steps_allow_background_updates
		else -> com.adsamcik.tracker.R.string.settings_steps_allow_health_connect
	})
	TextButton(
		onClick = onClick,
		modifier = Modifier
			.testTag(if (motionRepair) {
				"stepsMotionRepair"
			} else {
				"stepsHealthConnectRepair"
			})
			.semantics { contentDescription = label },
	) {
		Text(label)
	}
	if (uiState.stepsProviderRepairFailed) {
		Text(
			text = stringResource(if (motionRepair) {
				com.adsamcik.tracker.R.string.settings_ambient_steps_health_connect_probe_failed
			} else {
				com.adsamcik.tracker.R.string.settings_steps_health_connect_repair_failed
			}),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.error,
			modifier = Modifier.testTag(if (motionRepair) {
				"stepsMotionRepairError"
			} else {
				"stepsHealthConnectRepairError"
			}),
		)
	}
}

/** Permission repair never changes participation or resumes a stopped collection. */
internal fun TrackingSettingsUiState.shouldRequestStepsMotionPermission(
	sdkInt: Int,
	lifecycleState: Lifecycle.State,
	motionPermissionGranted: Boolean,
): Boolean = stepsEnabled && needsStepsMotionPermission(motionPermissionGranted) &&
	sdkInt >= Build.VERSION_CODES.Q && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

/** Repair the chosen system provider without requiring an unrelated direct-sensor grant first. */
private fun TrackingSettingsUiState.needsStepsMotionPermission(
	motionPermissionGranted: Boolean,
): Boolean {
	val reason = ambientSourceAvailability.getValue(AmbientTrackingSource.STEPS).reason
	return !motionPermissionGranted &&
		reason != AmbientSourceUnavailableReason.HEALTH_CONNECT_STEPS_PERMISSION_REQUIRED &&
		(stepCounterAvailable || reason == AmbientSourceUnavailableReason.LOCAL_RECORDING_ACTIVITY_PERMISSION_REQUIRED)
}

@Composable
private fun TrackingTurnOffSetting(
    state: TrackingSettingsUiState,
    onTurnOffTracking: () -> Unit,
) {
    SettingsGroupCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(
                    if (state.trackingTurnedOff) {
                        com.adsamcik.tracker.R.string.settings_tracking_is_off
                    } else {
                        com.adsamcik.tracker.R.string.settings_turn_off_tracking_explanation
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!state.trackingTurnedOff) {
                TextButton(
                    onClick = onTurnOffTracking,
                    enabled = !state.turnOffTrackingInProgress,
                    modifier = Modifier.testTag("turnOffTracking"),
                ) {
                    Text(stringResource(
                        if (state.turnOffTrackingInProgress) {
                            com.adsamcik.tracker.R.string.settings_turning_off_tracking
                        } else {
                            com.adsamcik.tracker.R.string.settings_turn_off_tracking
                        },
                    ))
                }
            }
            if (state.turnOffTrackingFailed) {
                Text(
                    text = stringResource(com.adsamcik.tracker.R.string.settings_turn_off_tracking_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("turnOffTrackingError"),
                )
            }
        }
    }
}

@Composable
private fun AdvancedDataSourceSettings(
    uiState: TrackingSettingsUiState,
    onSourceFrequencyChanged: (TrackingSourceComponent, SourceCollectionFrequency) -> Unit,
	onLocationEnabledChanged: (Boolean) -> Unit,
	onStepsProviderPermission: () -> Unit,
	onSourcePermission: (TrackingSourceComponent) -> Unit,
    onRetrySourceRecovery: () -> Unit,
) {
    SettingsGroupCard {
		if (uiState.locationProfileVersion != null) {
			SwitchSettingsItem(
				title = stringResource(com.adsamcik.tracker.tracker.R.string.settings_location_enabled_title),
				subtitle = stringResource(com.adsamcik.tracker.R.string.settings_location_adopted_source_summary),
				iconRes = com.adsamcik.tracker.R.drawable.ic_tracking_source_location,
				checked = uiState.locationEnabled,
				onCheckedChange = onLocationEnabledChanged,
			)
		} else {
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
		}
        SourcePermissionRepair(TrackingSourceComponent.LOCATION,
            uiState.locationEnabled && (!uiState.permissionCapabilities.hasForegroundLocation ||
                !uiState.permissionCapabilities.locationServicesEnabled), onSourcePermission)
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
        SourcePermissionRepair(TrackingSourceComponent.ACTIVITY,
            uiState.activityEnabled && !uiState.activityPermissionGranted, onSourcePermission)
        AllDaySensorRecovery(AmbientTrackingSource.ACTIVITY, uiState.activityEnabled,
            uiState.ambientSourceAvailability.getValue(AmbientTrackingSource.ACTIVITY), onRetrySourceRecovery)
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
		StepsProviderPermissionRepair(uiState, onStepsProviderPermission)
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
        AllDaySensorRecovery(AmbientTrackingSource.PRESSURE, uiState.barometerEnabled,
            uiState.ambientSourceAvailability.getValue(AmbientTrackingSource.PRESSURE), onRetrySourceRecovery)
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
        SourcePermissionRepair(TrackingSourceComponent.WIFI,
            uiState.wifiEnabled && !uiState.wifiPermissionGranted, onSourcePermission)
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
        SourcePermissionRepair(TrackingSourceComponent.CELL,
            uiState.cellEnabled && !uiState.cellPermissionGranted, onSourcePermission)
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
private fun AllDaySensorRecovery(
    source: AmbientTrackingSource,
    selected: Boolean,
    availability: AmbientSourceOperationalAvailability,
    onRetry: () -> Unit,
) {
    if (selected && availability.state == AmbientSourceOperationalState.UNAVAILABLE &&
        availability.reason !in setOf(AmbientSourceUnavailableReason.USER_TURNED_OFF,
            AmbientSourceUnavailableReason.PRESSURE_SENSOR_UNAVAILABLE)) {
        TextButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 16.dp)
            .testTag("allDaySourceRetry-${source.name.lowercase()}")) {
            Text(stringResource(com.adsamcik.tracker.R.string.settings_save_retry))
        }
    }
}

@Composable
private fun sourceSummaryWithAllDayStatus(
    summaryRes: Int,
    statusRes: Int?,
    selected: Boolean,
    availability: AmbientSourceOperationalAvailability,
): String {
    val summary = sourceSummaryWithOptionalStatus(summaryRes, statusRes)
    return if (selected && statusRes == null && !availability.isOperational) {
        stringResource(com.adsamcik.tracker.R.string.settings_source_summary_with_status,
            summary, ambientOperationalSummary(availability))
    } else {
        summary
    }
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
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                }
                else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
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
