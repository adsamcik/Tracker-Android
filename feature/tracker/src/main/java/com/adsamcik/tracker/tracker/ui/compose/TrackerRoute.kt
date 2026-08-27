package com.adsamcik.tracker.tracker.ui.compose

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.utils.compose.permission.ContextualPermissionRequest
import com.adsamcik.tracker.shared.utils.compose.permission.PermissionDeniedSnackbar
import com.adsamcik.tracker.shared.utils.compose.permission.PermissionType
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.shared.utils.compose.StopTrackingOptionsDialog
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.api.ManualTrackingStartReadiness
import com.adsamcik.tracker.tracker.api.ManualTrackingStartPrerequisite
import com.adsamcik.tracker.tracker.api.ManualTrackingStartRepairNavigation
import com.adsamcik.tracker.tracker.api.ManualTrackingStartResult
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/**
 * Duration used for the "Stop for N minutes" auto-tracking lock offered when stopping a session
 * that auto-tracking could otherwise silently restart. Matches the duration used by the
 * equivalent "stop for N minutes" action on the persistent tracking notification.
 */
private const val STOP_LOCK_MINUTES = 30

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface TrackerRouteEntryPoint {
    fun trackingParamsRepository(): TrackingParamsRepository
}

/**
 * Entry point composable for Tracker tab.
 * Manages contextual permission handling and tracking state observation per Apple-style guidelines.
 * 
 * Permission flow:
 * - Location permission requested on first "Start Tracking" tap (not during onboarding)
 * - Rationale shown BEFORE system prompt
 * - Denial handled gracefully with snackbar + settings redirect
 */
@Composable
fun TrackerRoute(
    onOpenSettings: () -> Unit = {},
    onOpenMap: () -> Unit = {},
    onOpenGame: (() -> Unit)? = null,
    onSessionDetailClick: ((Long) -> Unit)? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    viewModel: TrackerRouteViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentContext by rememberUpdatedState(context)
    val scope = rememberCoroutineScope()
    
    // Access dependencies via Hilt ViewModel
    val controller = viewModel.trackerController
    val lockManager = viewModel.lockManager
    val trackingParamsRepository = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            TrackerRouteEntryPoint::class.java
        ).trackingParamsRepository()
    }
    
    // Permission state
    var hasLocationPermission by rememberSaveable {
        mutableStateOf(checkLocationPermission(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasLocationPermission = checkLocationPermission(currentContext)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // Contextual permission request state
    var manualStartPermissionRequest by remember {
        mutableStateOf<ManualTrackingStartPrerequisite?>(null)
    }
    var permissionDenied by remember { mutableStateOf(false) }
    var showStopOptions by remember { mutableStateOf(false) }
    var pendingLocationServicesRepair by remember { mutableStateOf(false) }
    var manualStartReadiness by remember { mutableStateOf<ManualTrackingStartReadiness?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Observe tracking state via injected controller (replaces TrackerService static access)
    val isTracking by controller.isServiceRunningFlow.collectAsStateWithLifecycle()
    
    // Observe lock state via injected manager (replaces TrackerLocker static access)
    val isLocked by lockManager.isLockedFlow.collectAsStateWithLifecycle()

    // Observe policy tier via injected controller (Phase 4a: visual indicator)
    val policyTier by controller.policyTierFlow.collectAsStateWithLifecycle()
    
    // Observe session info via injected controller
    val sessionInfo by controller.sessionInfoFlow.collectAsStateWithLifecycle()
    
    // Observe full session and collection data via injected controller
    val sessionData by controller.sessionFlow.collectAsStateWithLifecycle()
    val collectionData by controller.collectionDataFlow.collectAsStateWithLifecycle()
    val pathPoints by controller.pathPointsFlow.collectAsStateWithLifecycle()
    val recentTrips by viewModel.recentTrips.collectAsStateWithLifecycle()
    val trackingParams by trackingParamsRepository.data.collectAsStateWithLifecycle(initialValue = TrackingParamsState())

    val noAvailableCaptureSourceMessage = stringResource(R.string.error_nothing_to_track)
    val openSettingsActionLabel = stringResource(
        com.adsamcik.tracker.shared.utils.R.string.permission_denied_settings_action,
    )
    val trackingUnavailableMessage = stringResource(
        R.string.notification_tracking_start_failed_title,
    )
    val highAccuracyEnabledMessage = stringResource(R.string.tracker_precision_mode_enabled)
    val batteryModeEnabledMessage = stringResource(R.string.tracker_battery_mode_enabled)
    val stoppedUntilRechargeMessage = stringResource(R.string.settings_disabled_recharge_summary)

    LaunchedEffect(trackingParams.sourcePolicyRevision, hasLocationPermission) {
        manualStartReadiness = TrackerServiceApi.readManualTrackingStartReadiness(context)
    }

    fun requestManualStart() {
        scope.launch {
            when (val result = TrackerServiceApi.requestManualTrackingStart(context)) {
                ManualTrackingStartResult.ENQUEUED -> Unit
                is ManualTrackingStartResult.RepairRequired -> when (result.prerequisite) {
                    ManualTrackingStartPrerequisite.LOCATION_SERVICES -> {
                        val action = snackbarHostState.showSnackbar(
                            message = context.getString(
                                R.string.manual_tracking_location_services_required,
                            ),
                            actionLabel = context.getString(
                                com.adsamcik.tracker.shared.utils.R.string
                                    .permission_denied_settings_action,
                            ),
                        )
                        if (action == SnackbarResult.ActionPerformed) {
                            pendingLocationServicesRepair = true
                            if (!ManualTrackingStartRepairNavigation
                                    .openLocationServicesSettings(context)
                            ) {
                                pendingLocationServicesRepair = false
                                snackbarHostState.showSnackbar(
                                    context.getString(
                                        R.string.notification_tracking_start_failed_title,
                                    ),
                                )
                            }
                        }
                    }
                    else -> manualStartPermissionRequest = result.prerequisite
                }
                ManualTrackingStartResult.NO_AVAILABLE_CAPTURE_SOURCE -> {
                    val result = snackbarHostState.showSnackbar(
                        message = noAvailableCaptureSourceMessage,
                        actionLabel = openSettingsActionLabel,
                    )
                    if (result == SnackbarResult.ActionPerformed) onOpenSettings()
                }
                ManualTrackingStartResult.TRACKING_UNAVAILABLE ->
                    snackbarHostState.showSnackbar(
                        trackingUnavailableMessage,
                    )
            }
            manualStartReadiness = TrackerServiceApi.readManualTrackingStartReadiness(context)
        }
    }

    val locationPermissionSatisfied =
        (manualStartReadiness as? ManualTrackingStartReadiness.RepairRequired)?.prerequisite !=
            ManualTrackingStartPrerequisite.PRECISE_LOCATION_PERMISSION

    TrackerManualStartResumeEffect(
        context = context,
        pendingLocationServicesRepair = pendingLocationServicesRepair,
        onReadiness = { manualStartReadiness = it },
        onLocationServicesRepairCompleted = {
            pendingLocationServicesRepair = false
            requestManualStart()
        },
        onLocationServicesRepairUnchanged = { pendingLocationServicesRepair = false },
    )
    
    val lastSessionData by controller.lastSessionFlow.collectAsStateWithLifecycle()
    val lastPathPoints by controller.lastPathPointsFlow.collectAsStateWithLifecycle()
    
    val displaySession = if (isTracking) sessionData else (sessionData ?: lastSessionData)
    val displayPathPoints = if (isTracking) pathPoints else (pathPoints ?: lastPathPoints)
    
    val relevantPathPoints = remember(displaySession, displayPathPoints) {
        if (displaySession != null && displayPathPoints != null && displayPathPoints.first == displaySession.id) {
            displayPathPoints.second
        } else {
            null
        }
    }
    val precisionModePreset = when (trackingParams.preset) {
        TrackingPreset.POWER_SAVE -> TrackingPreset.POWER_SAVE
        else -> TrackingPreset.HIGH_ACCURACY
    }
    
    // Contextual permission request dialog (Apple-style: rationale before system prompt)
    val permissionRequest = manualStartPermissionRequest?.toTrackerPermissionRequest()
    if (permissionRequest != null) {
        ContextualPermissionRequest(
            permissionType = permissionRequest.type,
            permission = permissionRequest.permission,
            onPermissionResult = {
                manualStartPermissionRequest = null
                scope.launch {
                    val updated = TrackerServiceApi.readManualTrackingStartReadiness(context)
                    manualStartReadiness = updated
                    hasLocationPermission = checkLocationPermission(context)
                    val sameRepair = (updated as? ManualTrackingStartReadiness.RepairRequired)
                        ?.prerequisite == permissionRequest.prerequisite
                    if (sameRepair) {
                        permissionDenied = true
                    } else {
                        requestManualStart()
                    }
                }
            },
            onDismiss = { manualStartPermissionRequest = null }
        )
    }
    
    // Show snackbar if permission denied (non-blocking, allows retry)
    if (permissionDenied) {
        val message = stringResource(
            com.adsamcik.tracker.shared.utils.R.string.permission_denied_tracking_prerequisite,
        )
        PermissionDeniedSnackbar(
            snackbarHostState = snackbarHostState,
            message = message
        )
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(5000)
            permissionDenied = false
        }
    }
    
    TrackerDashboard(
        state = TrackerDashboardUiState(
            isTracking = isTracking,
            isLocked = isLocked,
            sessionData = displaySession,
            collectionData = collectionData,
            hasLocationPermission = locationPermissionSatisfied,
            pathPoints = relevantPathPoints,
            policyTier = policyTier,
            precisionModePreset = precisionModePreset,
            trackingParams = trackingParams,
            recentTrips = recentTrips,
        ),
        dailyPointsProvider = viewModel.dailyPointsProvider,
        dailySummaryProvider = viewModel.dailySummaryProvider,
        goalProgressProvider = viewModel.goalProgressProvider,
        onSettingsClick = onOpenSettings,
        onMapClick = onOpenMap,
        onRequestPermission = { requestManualStart() },
        onToggleTracking = { shouldStart ->
            if (shouldStart) {
                requestManualStart()
            } else if (trackingParams.autoTrackingMode != GroupedActivity.STILL.ordinal) {
                // Auto-tracking is enabled and would likely restart the session moments after a
                // plain stop (see BackgroundTrackingApi's activity callbacks). Let the user pick
                // how long tracking should actually stay off, mirroring the tracking notification's
                // "stop for N minutes" / "stop until charging" actions.
                showStopOptions = true
            } else {
                TrackerServiceApi.stopService(context)
            }
        },
        onGameClick = onOpenGame,
        onPrecisionModeToggle = {
            scope.launch {
                val nextPreset = if (precisionModePreset == TrackingPreset.POWER_SAVE) {
                    TrackingPreset.HIGH_ACCURACY
                } else {
                    TrackingPreset.POWER_SAVE
                }
                trackingParamsRepository.applyDashboardPreset(nextPreset)
                snackbarHostState.showSnackbar(
                    message = if (nextPreset == TrackingPreset.HIGH_ACCURACY) {
                        highAccuracyEnabledMessage
                    } else {
                        batteryModeEnabledMessage
                    },
                )
            }
        },
        onSessionDetailClick = onSessionDetailClick,
        modifier = Modifier.padding(contentPadding),
        snackbarHostState = snackbarHostState
    )

    StopTrackingOptionsDialog(
        visible = showStopOptions,
        title = stringResource(R.string.stop_tracking_dialog_title),
        message = stringResource(R.string.stop_tracking_dialog_message),
        stopForMinutesLabel = stringResource(R.string.notification_stop_for_minutes, STOP_LOCK_MINUTES),
        stopUntilChargingLabel = stringResource(R.string.notification_stop_til_recharge),
        justStopLabel = stringResource(R.string.notification_stop),
        cancelLabel = stringResource(com.adsamcik.tracker.shared.base.R.string.generic_cancel),
        onStopForMinutes = {
            lockManager.lockTimeLock(context, STOP_LOCK_MINUTES * Time.MINUTE_IN_MILLISECONDS)
            TrackerServiceApi.stopService(context)
            showStopOptions = false
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.resources.getQuantityString(
                        R.plurals.notification_auto_tracking_lock,
                        STOP_LOCK_MINUTES,
                        STOP_LOCK_MINUTES,
                    )
                )
            }
        },
        onStopUntilCharging = {
            lockManager.lockUntilRecharge(context)
            TrackerServiceApi.stopService(context)
            showStopOptions = false
            scope.launch {
                snackbarHostState.showSnackbar(
                    stoppedUntilRechargeMessage,
                )
            }
        },
        onJustStop = {
            TrackerServiceApi.stopService(context)
            showStopOptions = false
        },
        onDismiss = { showStopOptions = false },
    )
}

internal data class TrackerManualStartPermissionRequest(
    val prerequisite: ManualTrackingStartPrerequisite,
    val type: PermissionType,
    val permission: String,
)

internal fun ManualTrackingStartPrerequisite.toTrackerPermissionRequest():
    TrackerManualStartPermissionRequest? = when (this) {
    ManualTrackingStartPrerequisite.PRECISE_LOCATION_PERMISSION ->
        TrackerManualStartPermissionRequest(
            prerequisite = this,
            type = PermissionType.LOCATION_FOREGROUND,
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
        )
    ManualTrackingStartPrerequisite.ACTIVITY_RECOGNITION_PERMISSION ->
        TrackerManualStartPermissionRequest(
            prerequisite = this,
            type = PermissionType.ACTIVITY_RECOGNITION,
            permission = Manifest.permission.ACTIVITY_RECOGNITION,
        )
    ManualTrackingStartPrerequisite.READ_PHONE_STATE_PERMISSION ->
        TrackerManualStartPermissionRequest(
            prerequisite = this,
            type = PermissionType.PHONE_STATE,
            permission = Manifest.permission.READ_PHONE_STATE,
        )
    ManualTrackingStartPrerequisite.LOCATION_SERVICES -> null
}

/** Re-reads readiness on resume without opening a system prompt from a lifecycle event alone. */
@Composable
internal fun TrackerManualStartResumeEffect(
    context: Context,
    pendingLocationServicesRepair: Boolean,
    onReadiness: (ManualTrackingStartReadiness) -> Unit,
    onLocationServicesRepairCompleted: () -> Unit,
    onLocationServicesRepairUnchanged: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val currentContext by rememberUpdatedState(context)
    val currentPendingRepair by rememberUpdatedState(pendingLocationServicesRepair)
    val currentOnReadiness by rememberUpdatedState(onReadiness)
    val currentOnRepairCompleted by rememberUpdatedState(onLocationServicesRepairCompleted)
    val currentOnRepairUnchanged by rememberUpdatedState(onLocationServicesRepairUnchanged)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    val readiness = TrackerServiceApi.readManualTrackingStartReadiness(currentContext)
                    currentOnReadiness(readiness)
                    if (currentPendingRepair) {
                        val stillBlocked = readiness == ManualTrackingStartReadiness.RepairRequired(
                            ManualTrackingStartPrerequisite.LOCATION_SERVICES,
                        )
                        if (stillBlocked) {
                            currentOnRepairUnchanged()
                        } else {
                            currentOnRepairCompleted()
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

internal data class TrackerPermissionResultState(
    val hasLocationPermission: Boolean,
    val showLocationPermissionRequest: Boolean,
    val permissionDenied: Boolean,
)

internal fun resolveTrackerPermissionResult(granted: Boolean) = TrackerPermissionResultState(
    hasLocationPermission = granted,
    showLocationPermissionRequest = false,
    permissionDenied = !granted,
)

private fun checkLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}

private suspend fun TrackingParamsRepository.applyDashboardPreset(preset: TrackingPreset) {
    update {
        copy(
            locationEnabled = preset.locationEnabled,
            activityEnabled = preset.activityEnabled,
            stepsEnabled = preset.stepsEnabled,
            wifiEnabled = preset.wifiEnabled,
            cellEnabled = preset.cellEnabled,
            barometerEnabled = preset.barometerEnabled,
            minDistanceMeters = preset.minDistanceMeters,
            minTimeSeconds = preset.minTimeSeconds,
            requiredAccuracyMeters = preset.requiredAccuracyMeters,
            presetName = preset.name,
        )
    }
}
