package com.adsamcik.tracker.tracker.ui.compose

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.adsamcik.tracker.shared.base.permission.ContextualPermissionRequest
import com.adsamcik.tracker.shared.base.permission.PermissionDeniedSnackbar
import com.adsamcik.tracker.shared.base.permission.PermissionType
import com.adsamcik.tracker.shared.base.di.LocalTrackerController
import com.adsamcik.tracker.shared.base.di.LocalLockManager
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.controller.LockManager

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
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp)
) {
    val context = LocalContext.current
    
    // Access dependencies via CompositionLocal (no app.Application import needed!)
    val controller = LocalTrackerController.current as TrackerServiceController
    val lockManager = LocalLockManager.current as LockManager
    
    // Permission state
    var hasLocationPermission by remember {
        mutableStateOf(checkLocationPermission(context))
    }
    
    // Contextual permission request state
    var showLocationPermissionRequest by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Observe tracking state via injected controller (replaces TrackerService static access)
    val isTracking by controller.isServiceRunningFlow.collectAsState()
    
    // Observe lock state via injected manager (replaces TrackerLocker static access)
    val isLocked by lockManager.isLockedFlow.collectAsState()

    // Observe policy tier via injected controller (Phase 4a: visual indicator)
    val policyTier by controller.policyTierFlow.collectAsState()
    
    // Observe session info via injected controller
    val sessionInfo by controller.sessionInfoFlow.collectAsState()
    
    // Observe full session and collection data via injected controller
    val sessionData by controller.sessionFlow.collectAsState()
    val collectionData by controller.collectionDataFlow.collectAsState()
    val pathPoints by controller.pathPointsFlow.collectAsState()
    
    val lastSessionData by controller.lastSessionFlow.collectAsState()
    val lastPathPoints by controller.lastPathPointsFlow.collectAsState()
    
    val displaySession = if (isTracking) sessionData else (sessionData ?: lastSessionData)
    val displayPathPoints = if (isTracking) pathPoints else (pathPoints ?: lastPathPoints)
    
    val relevantPathPoints = remember(displaySession, displayPathPoints) {
        if (displaySession != null && displayPathPoints != null && displayPathPoints.first == displaySession.id) {
            displayPathPoints.second
        } else {
            null
        }
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
            hasLocationPermission = hasLocationPermission,
            pathPoints = relevantPathPoints,
            policyTier = policyTier
        ),
        onSettingsClick = onOpenSettings,
        onMapClick = onOpenMap,
        onRequestPermission = { showLocationPermissionRequest = true },
        onToggleTracking = { shouldStart ->
            if (shouldStart) {
                if (hasLocationPermission) {
                    TrackerServiceApi.startService(context, isUserInitiated = true)
                } else {
                    // Request permission contextually
                    showLocationPermissionRequest = true
                }
            } else {
                TrackerServiceApi.stopService(context)
            }
        },
        onGameClick = onOpenGame,
        onSessionDetailClick = onSessionDetailClick,
        modifier = Modifier.padding(contentPadding),
        snackbarHostState = snackbarHostState
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
