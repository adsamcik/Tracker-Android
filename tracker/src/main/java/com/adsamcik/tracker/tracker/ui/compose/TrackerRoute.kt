package com.adsamcik.tracker.tracker.ui.compose

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.asFlow
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.locker.TrackerLocker
import com.adsamcik.tracker.tracker.service.TrackerService

/**
 * Entry point composable for Tracker tab.
 * Manages permission handling and tracking state observation per evergreen guidelines.
 */
@Composable
fun TrackerRoute(
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    
    // Permission state
    var hasLocationPermission by remember {
        mutableStateOf(checkLocationPermission(context))
    }
    
    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions.values.any { it }
    }
    
    // Observe tracking state from TrackerService
    val isTracking by TrackerService.isServiceRunningFlow.collectAsState()
    
    // Observe locker state (Flow)
    val isLocked by TrackerLocker.isLockedFlow.collectAsState()
    
    // Observe session info (Flow)
    val sessionInfo by TrackerService.sessionInfoFlow.collectAsState()
    
    // Observe full session and collection data (Flow)
    val sessionData by TrackerService.sessionFlow.collectAsState()
    val collectionData by TrackerService.collectionDataFlow.collectAsState()
    
    TrackerDashboard(
        state = TrackerDashboardUiState(
            isTracking = isTracking,
            isLocked = isLocked,
            sessionData = sessionData,
            collectionData = collectionData,
            hasLocationPermission = hasLocationPermission
        ),
        onSettingsClick = onOpenSettings,
        onRequestPermission = {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        },
        onToggleTracking = { shouldStart ->
            if (shouldStart) {
                TrackerServiceApi.startService(context, isUserInitiated = true)
            } else {
                TrackerServiceApi.stopService(context)
            }
        }
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
