package com.adsamcik.tracker.map.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.graphics.Color
import com.adsamcik.tracker.map.presentation.MapStore
import com.adsamcik.tracker.map.presentation.udf.CameraModel
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings as ComposeMapUiSettings
import com.google.maps.android.compose.Marker
import com.adsamcik.tracker.map.ui.bitmapDescriptorFromVector
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.Polyline as ComposePolyline
import com.google.maps.android.compose.TileOverlay
import com.google.maps.android.compose.TileOverlayState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.adsamcik.tracker.map.presentation.style.MapStyleProvider
import com.adsamcik.tracker.map.presentation.udf.MapOverlayState
import com.adsamcik.tracker.shared.utils.style.StyleManager
import com.adsamcik.tracker.shared.utils.style.StyleController
import com.adsamcik.tracker.shared.utils.style.StyleData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.ui.geometry.Offset

/**
 * Phase 3 scaffolding: Compose-based MapScreen using Maps Compose.
 * Currently renders the base map and wires camera changes into the store.
 * Overlays and style will be added incrementally.
 */
@Composable
fun MapScreen(
    store: MapStore,
    overlayMode: Boolean = false,
    bottomPaddingPx: Int = 0,
) {
    val state by store.state.collectAsState()

    val cameraPositionState = rememberCameraPositionState()

    val ui = state.uiSettings
    val composeUi = remember(ui, overlayMode) {
        ComposeMapUiSettings(
            mapToolbarEnabled = ui.isMapToolbarEnabled,
            indoorLevelPickerEnabled = ui.isIndoorLevelPickerEnabled,
            compassEnabled = ui.isCompassEnabled,
            myLocationButtonEnabled = ui.isMyLocationButtonEnabled,
            zoomControlsEnabled = false,
            scrollGesturesEnabled = !overlayMode,
            scrollGesturesEnabledDuringRotateOrZoom = !overlayMode,
            tiltGesturesEnabled = !overlayMode,
            rotationGesturesEnabled = !overlayMode,
            zoomGesturesEnabled = !overlayMode,
        )
    }

    val context = LocalContext.current
    val initialStyle = remember { MapStyleProvider.default(context) }
    var mapProperties by remember {
        mutableStateOf(
            MapProperties(
                mapStyleOptions = initialStyle
            )
        )
    }

    // Observe shared style changes and update Compose map style reactively
    LaunchedEffect(Unit) {
        // Ensure StyleManager is initialized with preferences at least once in app lifecycle.
        // If already initialized elsewhere, this is a no-op path via listeners.
    }
    val styleController = remember { StyleManager.createController() }
    DisposableEffect(styleController) {
        val listener: (StyleData) -> Unit = { sd ->
            try {
                val updated = MapStyleProvider.fromStyleData(context, sd)
                mapProperties = mapProperties.copy(mapStyleOptions = updated)
            } catch (e: Exception) {
                // Fall back to default style if there's an error
                val defaultStyle = MapStyleProvider.default(context)
                mapProperties = mapProperties.copy(mapStyleOptions = defaultStyle)
            }
        }
        styleController.addListener(listener)
        onDispose {
            styleController.removeListener(listener)
            StyleManager.recycleController(styleController)
        }
    }

    val snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { _ ->
        GoogleMap(
            modifier = if (overlayMode) Modifier.fillMaxSize().pointerInteropFilter { false } else Modifier.fillMaxSize(),
            properties = mapProperties,
            uiSettings = composeUi,
            cameraPositionState = cameraPositionState,
            onMapClick = { /* tap does not cancel follow itself; keep for future interactions */ },
            onMapLongClick = { /* no-op */ },
            onPOIClick = { /* no-op */ },
        ) {
        // Imperative interop: cancel follow on user gesture using GoogleMap listener
        MapEffect(Unit) { gMap ->
            gMap.setOnCameraMoveStartedListener { reason ->
                if (!overlayMode && reason == com.google.android.gms.maps.GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                    store.dispatch(MapEvent.FollowCanceled)
                }
            }
        }
        // Update Google Map padding when the bottom sheet visible height changes
        MapEffect(bottomPaddingPx) { gMap ->
            try {
                gMap.setPadding(0, 0, 0, bottomPaddingPx)
            } catch (_: Throwable) { /* ignore */ }
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
                        strokeColor = Color(0x55007AFF),
                        fillColor = Color(0x22007AFF),
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
                // We cannot distinguish gesture vs programmatic here reliably; follow cancel is handled in onCameraMoveStarted.
                store.dispatch(MapEvent.CameraMoved(model, byGesture = false))
            }
    }

    // Consume one-off effects
    LaunchedEffect(Unit) {
        store.effects.collectLatest { effect ->
            when (effect) {
                is com.adsamcik.tracker.map.presentation.udf.MapEffect.CenterCamera -> {
                    try {
                        val padding = 32 // Reasonable padding in pixels
                        val update = com.google.android.gms.maps.CameraUpdateFactory.newLatLngBounds(effect.bounds, padding)
                        cameraPositionState.animate(update, 1000) // 1 second animation
                    } catch (e: Exception) {
                        // Fallback to simple camera move if bounds are invalid
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
                        cameraPositionState.animate(update, 500) // Shorter animation for bearing
                    } catch (e: Exception) {
                        // Ignore bearing update errors
                    }
                }
                is com.adsamcik.tracker.map.presentation.udf.MapEffect.ShowFollowCanceled -> {
                    try {
                        snackbarHostState.showSnackbar("Follow canceled")
                    } catch (e: Exception) {
                        // Ignore snackbar errors
                    }
                }
                is com.adsamcik.tracker.map.presentation.udf.MapEffect.PerformGeocode -> {
                    // Handled by Fragment bridge; no-op here
                }
            }
        }
    }
}
