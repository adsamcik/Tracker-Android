package com.adsamcik.tracker.map.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
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
import com.adsamcik.tracker.map.export.MapShareResolution
import com.adsamcik.tracker.map.layers.registry.DefaultLayerRegistry
import com.adsamcik.tracker.map.presentation.MapStore
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerEngine
import com.adsamcik.tracker.map.ui.compose.MapShareSheet
import com.adsamcik.tracker.map.ui.controls.MapChromeHost
import com.adsamcik.tracker.shared.utils.compose.permission.ContextualPermissionRequest
import com.adsamcik.tracker.shared.utils.compose.permission.PermissionType
import kotlinx.coroutines.launch

/**
 * Compose-native Map route. Hosts MapScreen and MapSheet,
 * and wires a MapStore with MapLibreLayerEngine immediately (no map instance needed).
 */
@Composable
fun MapRoute(
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val bottomPaddingPx = remember(contentPadding, density) {
        with(density) { contentPadding.calculateBottomPadding().roundToPx() }
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }

    if (!hasPermission && !permissionRequested) {
        ContextualPermissionRequest(
            permissionType = PermissionType.LOCATION_FOREGROUND,
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
            onPermissionResult = { granted ->
                hasPermission = granted
                permissionRequested = true
            },
            onDismiss = {
                permissionRequested = true
            },
            rationaleMessageOverride = com.adsamcik.tracker.map.R.string.permission_rationale_location_map
        )
    }

    val registry = remember { DefaultLayerRegistry() }
    val store: MapStore = hiltViewModel()

    // Wire MapLibreLayerEngine immediately -- no map instance required
    LaunchedEffect(Unit) {
        if (!store.isEngineReady) {
            val engine = MapLibreLayerEngine(context.applicationContext, registry)
            store.setLayerEngine(engine)
        }
    }

    var extraBottomPadding by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // "Share map as image" — sheet visibility/selection live here (transient UI, not MapStore
    // state); the actual capture runs inside MapScreen, which owns the map's baseStyle/camera/
    // layer data this needs.
    var showShareSheet by remember { mutableStateOf(false) }
    var shareResolution by remember { mutableStateOf(MapShareResolution.STANDARD) }
    var shareCaptureRequest by remember { mutableStateOf<MapShareResolution?>(null) }
    var isPreparingShareImage by remember { mutableStateOf(false) }
    val shareErrorMessage = stringResource(com.adsamcik.tracker.map.R.string.map_share_error)

    // Read real status-bar height instead of hard-coding 72dp so the Map badge/loading indicator never
    // clip behind a tall status bar (large-font / cutout / always-on displays).
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize()) {
        val finalBottomPadding = maxOf(bottomPaddingPx, extraBottomPadding)

        MapScreen(
            store = store,
            snackbarHostState = snackbarHostState,
            overlayMode = false,
            bottomPaddingPx = finalBottomPadding,
            isLocationPermissionGranted = hasPermission,
            topInsetPadding = statusBarTop + 16.dp,
            shareCaptureRequest = shareCaptureRequest,
            onShareCaptureStarted = { isPreparingShareImage = true },
            onShareCaptureFinished = { success ->
                isPreparingShareImage = false
                shareCaptureRequest = null
                if (!success) {
                    coroutineScope.launch { snackbarHostState.showSnackbar(shareErrorMessage) }
                }
            },
        )

        MapChromeHost(
            registry = registry,
            store = store,
            snackbarHostState = snackbarHostState,
            bottomInsetPx = bottomPaddingPx,
            onBottomPaddingChanged = { padding ->
                extraBottomPadding = padding
            },
            onShareMapClick = { showShareSheet = true },
        )

        MapShareSheet(
            visible = showShareSheet,
            selectedResolution = shareResolution,
            isPreparing = isPreparingShareImage,
            onResolutionSelected = { shareResolution = it },
            onShareClick = {
                showShareSheet = false
                shareCaptureRequest = shareResolution
            },
            onDismiss = { showShareSheet = false },
        )
    }
}

// Preview note: MapRoute depends on MapLibre native rendering which cannot
// be previewed in Android Studio. Use the emulator or device for visual
// testing of the map screen.
