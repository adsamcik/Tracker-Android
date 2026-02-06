package com.adsamcik.tracker.map.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import android.Manifest
import android.content.pm.PackageManager
import com.adsamcik.tracker.map.layers.registry.DefaultLayerRegistry
import com.adsamcik.tracker.map.presentation.MapStore
import com.adsamcik.tracker.map.presentation.bridge.LayerManager
import com.google.android.gms.maps.GoogleMap
import com.adsamcik.tracker.shared.base.permission.ContextualPermissionRequest
import com.adsamcik.tracker.shared.base.permission.PermissionType

/**
 * Compose-native Map route, replacing FragmentMap. It hosts MapScreen and MapSheet,
 * and wires a MapStore once GoogleMap is ready (exposed from MapScreen).
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
    
    // Check for location permissions to ensure "My Location" layer works
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
            }
        )
    }

    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    val registry = remember { DefaultLayerRegistry() }
    // Get store via Hilt ViewModel injection (proper lifecycle management)
    val store: MapStore = hiltViewModel()

    // Set the layer engine when GoogleMap arrives
    LaunchedEffect(googleMap) {
        val gMap = googleMap ?: return@LaunchedEffect
        val layerManager = LayerManager(context, gMap, registry)
        store.setLayerEngine(layerManager)
    }

    // Dynamic padding handling from MapSheet (Search Bar height change or expansion)
    var extraBottomPadding by remember { mutableStateOf(0) }
    
    // UI - always render the same MapScreen and MapSheet
    Box(Modifier.fillMaxSize()) {
        // Main map surface
        // Use the MAX of passed navigation padding and internal sheet/search padding
        // This ensures Google Logo rises above search bar, and controls (if any)
        val finalBottomPadding = maxOf(bottomPaddingPx, extraBottomPadding)
        
        MapScreen(
            store = store,
            overlayMode = false,
            bottomPaddingPx = finalBottomPadding, 
            onGoogleMapReady = { m -> if (googleMap == null) googleMap = m },
            isLocationPermissionGranted = hasPermission
        )

        // Bottom sheet controller
        // MapSheet handles the search bar placement relative to bottomInsetPx (nav bar)
        // It reports back the total height needed to clear the search bar/sheet for the Map.
        MapSheet(
            registry = registry,
            store = store,
            bottomInsetPx = bottomPaddingPx, // Pass system/nav padding to sheet so it floats above it
            onBottomPaddingChanged = { padding ->
                extraBottomPadding = padding
            }
        )
    }
}
