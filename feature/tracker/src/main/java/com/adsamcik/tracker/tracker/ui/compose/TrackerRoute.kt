package com.adsamcik.tracker.tracker.ui.compose

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.base.permission.ContextualPermissionRequest
import com.adsamcik.tracker.shared.base.permission.PermissionDeniedSnackbar
import com.adsamcik.tracker.shared.base.permission.PermissionType
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.shared.utils.compose.StopTrackingOptionsDialog
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.R
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
    var hasLocationPermission by remember {
        mutableStateOf(checkLocationPermission(context))
    }
    
    // Contextual permission request state
    var showLocationPermissionRequest by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var showStopOptions by remember { mutableStateOf(false) }
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
    val trackingParams by trackingParamsRepository.data.collectAsStateWithLifecycle(initialValue = TrackingParamsState())
    val locationPermissionSatisfied = !trackingParams.locationEnabled || hasLocationPermission
    
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
    if (showLocationPermissionRequest) {
        ContextualPermissionRequest(
            permissionType = PermissionType.LOCATION_FOREGROUND,
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
            onPermissionResult = { granted ->
                hasLocationPermission = granted
                if (granted) {
                    // Permission granted - start tracking
                    TrackerServiceApi.startService(context, isUserInitiated = true)
                } else {
                    // Permission denied - show snackbar with settings action
                    permissionDenied = true
                }
            },
            onDismiss = { 
                showLocationPermissionRequest = false 
            }
        )
    }
    
    // Show snackbar if permission denied (non-blocking, allows retry)
    if (permissionDenied) {
        val message = context.getString(com.adsamcik.tracker.shared.base.R.string.permission_denied_tracking_disabled)
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
        ),
        dailyPointsProvider = viewModel.dailyPointsProvider,
        dailySummaryProvider = viewModel.dailySummaryProvider,
        goalProgressProvider = viewModel.goalProgressProvider,
        onSettingsClick = onOpenSettings,
        onMapClick = onOpenMap,
        onRequestPermission = { showLocationPermissionRequest = true },
        onToggleTracking = { shouldStart ->
            if (shouldStart) {
                if (locationPermissionSatisfied) {
                    TrackerServiceApi.startService(context, isUserInitiated = true)
                } else {
                    // Request permission contextually
                    showLocationPermissionRequest = true
                }
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
                    message = context.getString(
                        if (nextPreset == TrackingPreset.HIGH_ACCURACY) {
                            R.string.tracker_precision_mode_enabled
                        } else {
                            R.string.tracker_battery_mode_enabled
                        }
                    )
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
                    context.getString(R.string.settings_disabled_recharge_summary)
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
