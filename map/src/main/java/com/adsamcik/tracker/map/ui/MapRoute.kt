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
import com.adsamcik.tracker.map.layers.registry.DefaultLayerRegistry
import com.adsamcik.tracker.map.presentation.MapStore
import com.adsamcik.tracker.map.presentation.bridge.LayerManager
import com.google.android.gms.maps.GoogleMap

/**
 * Compose-native Map route, replacing FragmentMap. It hosts MapScreen and MapSheet,
 * and wires a MapStore once GoogleMap is ready (exposed from MapScreen).
 */
@Composable
fun MapRoute() {
    val context = LocalContext.current
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    val registry = remember { DefaultLayerRegistry() }
    // Create store once at the start (engine will be set when GoogleMap is ready)
    val store = remember { MapStore() }

    // Set the layer engine when GoogleMap arrives
    LaunchedEffect(googleMap) {
        val gMap = googleMap ?: return@LaunchedEffect
        val layerManager = LayerManager(context, gMap, registry)
        store.setLayerEngine(layerManager)
    }

    // UI - always render the same MapScreen and MapSheet
    Box(Modifier.fillMaxSize()) {
        // Main map surface
        MapScreen(
            store = store,
            overlayMode = false,
            bottomPaddingPx = 0,
            onGoogleMapReady = { m -> if (googleMap == null) googleMap = m }
        )

        // Bottom sheet controller
        MapSheet(
            registry = registry,
            store = store,
            bottomInsetPx = 0,
            onBottomPaddingChanged = { padding ->
                // MapScreen handles padding via MapEffect; update is applied by composable parameter
            }
        )
    }
}
