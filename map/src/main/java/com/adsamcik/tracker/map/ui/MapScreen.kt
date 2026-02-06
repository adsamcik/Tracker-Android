package com.adsamcik.tracker.map.ui

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.map.presentation.MapStore
import com.adsamcik.tracker.map.presentation.udf.CameraModel
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.adsamcik.tracker.map.presentation.udf.MapOverlayState
import com.adsamcik.tracker.map.ui.bitmapDescriptorFromVector
import com.adsamcik.tracker.shared.utils.style.compose.AppColors
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.google.android.gms.maps.GoogleMap
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.TileOverlay
import com.google.maps.android.compose.TileOverlayState
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.google.maps.android.compose.MapUiSettings as ComposeMapUiSettings
import com.google.maps.android.compose.Polyline as ComposePolyline
import kotlinx.coroutines.flow.collectLatest

private const val TAG = "MapScreen"

/**
 * Phase 3 scaffolding: Compose-based MapScreen using Maps Compose.
 * Redesigned with custom Glass controls for "Immersive Cartography".
 */
@Composable
fun MapScreen(
    store: MapStore,
    overlayMode: Boolean = false,
    bottomPaddingPx: Int = 0,
    onGoogleMapReady: ((GoogleMap) -> Unit)? = null,
    isLocationPermissionGranted: Boolean = false
) {
    val state by store.state.collectAsState()

    val cameraPositionState = rememberCameraPositionState()

    // Disable default UI controls in favor of custom Glass overlays
    val ui = state.uiSettings
    val composeUi = remember(ui, overlayMode) {
        ComposeMapUiSettings(
            mapToolbarEnabled = false,
            indoorLevelPickerEnabled = false,
            compassEnabled = false, // We could implement a custom compass later
            myLocationButtonEnabled = false, // Replacing with custom button
            zoomControlsEnabled = false, // Replacing with gestures/custom
            scrollGesturesEnabled = !overlayMode,
            scrollGesturesEnabledDuringRotateOrZoom = !overlayMode,
            tiltGesturesEnabled = !overlayMode,
            rotationGesturesEnabled = !overlayMode,
            zoomGesturesEnabled = !overlayMode,
        )
    }

    val context = LocalContext.current
    val density = LocalDensity.current
    val bottomPaddingDp = with(density) { bottomPaddingPx.toDp() }
    
    // Use dark map style or standard based on theme (or force dark for Outdoor style?)
    // For now respecting system theme but the UI is definitely dark-optimized.
    val mapProperties = remember(isLocationPermissionGranted) {
        MapProperties(
            mapStyleOptions = null,
            isMyLocationEnabled = isLocationPermissionGranted // Enable the blue dot layer only if permitted
        )
    }

    val snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = if (overlayMode) Modifier.fillMaxSize().pointerInteropFilter { false } else Modifier.fillMaxSize(),
            properties = mapProperties,
            uiSettings = composeUi,
            cameraPositionState = cameraPositionState,
            onMapClick = { /* tap does not cancel follow itself; keep for future interactions */ },
            onMapLongClick = { /* no-op */ },
            onPOIClick = { /* no-op */ },
        ) {
            // Expose GoogleMap instance when available and set up listeners
            MapEffect(Unit) { gMap ->
                try { onGoogleMapReady?.invoke(gMap) } catch (e: Throwable) { Log.e("MapScreen", "Error in onGoogleMapReady callback: ${e.message}", e) }
                gMap.setOnCameraMoveStartedListener { reason ->
                    if (!overlayMode && reason == com.google.android.gms.maps.GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                        store.dispatch(MapEvent.FollowCanceled)
                    }
                }
                // Camera idle -> prefetch surrounding tiles if heatmap provider active
                gMap.setOnCameraIdleListener {
                    val provider = state.tileProvider
                    val hp = provider as? com.adsamcik.tracker.map.tiles.HeatmapTileProviderBase
                    if (hp != null) {
                        try {
                            val vr = gMap.projection.visibleRegion.latLngBounds
                            val bounds = com.adsamcik.tracker.shared.map.CoordinateBounds(
                                vr.northeast.latitude,
                                vr.northeast.longitude,
                                vr.southwest.latitude,
                                vr.southwest.longitude
                            )
                            val zoom = gMap.cameraPosition.zoom.toInt()
                            hp.prefetchViewport(bounds, zoom, borderTiles = 1)
                        } catch (e: Throwable) { Log.w("MapScreen", "Failed to prefetch heatmap viewport: ${e.message}") }
                    }
                }
            }
            // Update Google Map padding for Google Logo/Copyright
            MapEffect(bottomPaddingPx) { gMap ->
                try {
                    gMap.setPadding(0, 0, 0, bottomPaddingPx)
                } catch (e: Throwable) { Log.w("MapScreen", "Failed to set map padding: ${e.message}") }
            }
            // Declarative overlays
            val overlays = state.overlays
            overlays.forEach { overlay ->
                when (overlay) {
                    is MapOverlayState.UserMarker -> {
                        val pos = com.google.android.gms.maps.model.LatLng(overlay.latLng.lat, overlay.latLng.lng)
                        val markerState: MarkerState = rememberUpdatedMarkerState(position = pos)
                        Marker(
                            state = markerState,
                            icon = remember(overlay.bearing) { 
                                bitmapDescriptorFromVector(context, com.adsamcik.tracker.map.R.drawable.ic_heading_arrow, scale = 1.25f)
                            },
                            anchor = Offset(0.5f, 0.85f),
                            rotation = overlay.bearing ?: 0f,
                            flat = true
                        )
                    }
                    is MapOverlayState.AccuracyCircle -> {
                        Circle(
                            center = com.google.android.gms.maps.model.LatLng(overlay.latLng.lat, overlay.latLng.lng),
                            radius = overlay.radiusM,
                            strokeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.33f),
                            fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                            strokeWidth = 2f
                        )
                    }
                    is MapOverlayState.Polyline -> {
                        val pts = remember(overlay.points) {
                            overlay.points.map { p ->
                                com.google.android.gms.maps.model.LatLng(p.lat, p.lng)
                            }
                        }
                        ComposePolyline(
                            points = pts,
                            color = Color(overlay.colorArgb),
                            width = overlay.widthPx
                        )
                    }
                }
            }
    
            // Hoisted TileOverlay wired to legacy TileProvider
            val provider = state.tileProvider
            if (provider != null) {
                val tileState = remember { TileOverlayState() }
                TileOverlay(state = tileState, tileProvider = provider)
            }
        }
        
        // Custom Controls Overlay
        // All controls are now integrated into the MapSheet bottom bar for better reachability. 
        if (!overlayMode) {
             // Intentionally empty
        }
        
        // Snackbar Host (positioned above controls?)
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPaddingDp + 16.dp)
        )
    }

    // Emit camera change events
    LaunchedEffect(cameraPositionState) {
        snapshotFlow { cameraPositionState.position }
            .collectLatest { pos ->
                val model = CameraModel(
                    lat = pos.target.latitude,
                    lng = pos.target.longitude,
                    zoom = pos.zoom,
                    tilt = pos.tilt,
                    bearing = pos.bearing,
                )
                store.dispatch(MapEvent.CameraMoved(model, byGesture = false))
            }
    }

    // Consume one-off effects
    LaunchedEffect(Unit) {
        store.effects.collectLatest { effect ->
            when (effect) {
                is com.adsamcik.tracker.map.presentation.udf.MapEffect.CenterCamera -> {
                    try {
                        val padding = 32
                        val update = com.google.android.gms.maps.CameraUpdateFactory.newLatLngBounds(effect.bounds, padding)
                        cameraPositionState.animate(update, 1000)
                    } catch (e: Exception) {
                        val center = effect.bounds.center
                        val update = com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(center, 15f)
                        cameraPositionState.animate(update, 1000)
                    }
                }
                is com.adsamcik.tracker.map.presentation.udf.MapEffect.SetCameraBearing -> {
                    try {
                        val current = cameraPositionState.position
                        val newPos = com.google.android.gms.maps.model.CameraPosition.Builder(current)
                            .bearing(effect.bearing)
                            .build()
                        val update = com.google.android.gms.maps.CameraUpdateFactory.newCameraPosition(newPos)
                        cameraPositionState.animate(update, 500)
                    } catch (e: Exception) { Log.e(TAG, "Failed to set camera bearing: ${e.message}", e) }
                }
                is com.adsamcik.tracker.map.presentation.udf.MapEffect.ShowFollowCanceled -> {
                    try { snackbarHostState.showSnackbar("Follow canceled") } catch (e: Exception) { Log.e(TAG, "Failed to show snackbar: ${e.message}", e) }
                }
                is com.adsamcik.tracker.map.presentation.udf.MapEffect.PerformGeocode -> { }
            }
        }
    }
}

