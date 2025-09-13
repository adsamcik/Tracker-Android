package com.adsamcik.tracker.map.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.adsamcik.tracker.map.layers.registry.DefaultLayerRegistry
import com.adsamcik.tracker.map.presentation.MapStore
import com.adsamcik.tracker.map.presentation.bridge.LayerManager
import com.adsamcik.tracker.map.presentation.udf.MapEvent
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
    // Build MapStore lazily once we have GoogleMap
    var store by remember { mutableStateOf<MapStore?>(null) }

    // Create store when map arrives
    LaunchedEffect(googleMap) {
        val gMap = googleMap ?: return@LaunchedEffect
        val layerManager = LayerManager(context, gMap, registry)
        store = MapStore(layerManager)
    }

    // UI
    Box(Modifier.fillMaxSize()) {
        val mapStore = store
        if (mapStore != null) {
            // Main map surface
            MapScreen(
                store = mapStore,
                overlayMode = false,
                bottomPaddingPx = 0,
                onGoogleMapReady = { m -> if (googleMap == null) googleMap = m }
            )

            // Bottom sheet controller
            MapSheet(
                registry = registry,
                store = mapStore,
                bottomInsetPx = 0,
                onBottomPaddingChanged = { padding ->
                    // MapScreen handles padding via MapEffect; update is applied by composable parameter
                }
            )
        } else {
            // Render map first to obtain GoogleMap; MapScreen will call onGoogleMapReady
            MapScreen(
                store = remember { MapStore(object : com.adsamcik.tracker.map.presentation.bridge.LayerEngine { // no-op temporary
                    override fun selectSingleLayer(id: String?, quality: Float, dateRange: LongRange) {}
                    override fun clear() {}
                    override fun activeLegend() = null
                    override fun activeTileProvider() = null
                    override fun overlays() = kotlinx.collections.immutable.persistentListOf<com.adsamcik.tracker.map.presentation.udf.MapOverlayState>()
                }) },
                overlayMode = false,
                onGoogleMapReady = { m -> if (googleMap == null) googleMap = m }
            )
        }
    }
}
