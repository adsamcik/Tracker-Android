package com.adsamcik.tracker.map.ui

import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.map.presentation.MapStore
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.presentation.udf.CameraModel
import com.adsamcik.tracker.map.presentation.udf.MapEffect
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.adsamcik.tracker.map.presentation.udf.MapOverlayState
import com.adsamcik.tracker.shared.map.MapStyleProvider
import kotlinx.coroutines.flow.collectLatest
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.compose.MapLibreMap
import org.maplibre.compose.rememberCameraState

private const val TAG = "MapScreen"

private const val SOURCE_LAYER = "layer-source"
private const val LAYER_HEATMAP = "heatmap-layer"
private const val LAYER_LINE = "line-layer"
private const val SOURCE_USER = "user-source"
private const val LAYER_USER_DOT = "user-dot"
private const val SOURCE_ACCURACY = "accuracy-source"
private const val LAYER_ACCURACY = "accuracy-circle"

/**
 * MapLibre-based MapScreen. Renders heatmaps, polylines, user location via
 * MapLibre GL Native's Compose wrapper.
 */
@Composable
fun MapScreen(
    store: MapStore,
    overlayMode: Boolean = false,
    bottomPaddingPx: Int = 0,
    onMapReady: ((MapLibreMap) -> Unit)? = null,
    isLocationPermissionGranted: Boolean = false
) {
    val state by store.state.collectAsState()
    val isDark = isSystemInDarkTheme()
    val styleUri = remember(isDark) { MapStyleProvider.styleUri(isDark) }

    val density = LocalDensity.current
    val bottomPaddingDp = with(density) { bottomPaddingPx.toDp() }

    val snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }

    val cameraState = rememberCameraState()

    Box(Modifier.fillMaxSize()) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            styleUri = styleUri,
            cameraState = cameraState,
            gesturesEnabled = !overlayMode,
            onMapReady = { map ->
                onMapReady?.invoke(map)

                // Detect gesture-initiated camera moves to cancel follow
                map.addOnCameraMoveStartedListener { reason ->
                    if (!overlayMode && reason == MapLibreMap.OnCameraMoveStartedReason.REASON_API_GESTURE) {
                        store.dispatch(MapEvent.FollowCanceled)
                    }
                }

                // Report camera position changes
                map.addOnCameraIdleListener {
                    val pos = map.cameraPosition
                    val model = CameraModel(
                        lat = pos.target.latitude,
                        lng = pos.target.longitude,
                        zoom = pos.zoom.toFloat(),
                        tilt = pos.tilt.toFloat(),
                        bearing = pos.bearing.toFloat(),
                    )
                    store.dispatch(MapEvent.CameraMoved(model, byGesture = false))
                }
            },
            onStyleLoaded = { style ->
                // Apply current layer config when style loads
                applyLayerConfig(style, state.layerConfig)

                // Apply user overlays
                applyUserOverlays(style, state.overlays.toList())
            }
        )

        // Snackbar Host
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomPaddingDp + 16.dp)
        )
    }

    // Consume one-off effects
    LaunchedEffect(Unit) {
        store.effects.collectLatest { effect ->
            when (effect) {
                is MapEffect.CenterCamera -> {
                    try {
                        val bounds = LatLngBounds.Builder()
                            .include(LatLng(effect.bounds.topBound, effect.bounds.leftBound))
                            .include(LatLng(effect.bounds.bottomBound, effect.bounds.rightBound))
                            .build()
                        cameraState.animateTo(bounds, padding = 32)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to center camera: ${e.message}", e)
                    }
                }
                is MapEffect.SetCameraBearing -> {
                    try {
                        cameraState.animateBearing(effect.bearing.toDouble(), durationMs = 500)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set camera bearing: ${e.message}", e)
                    }
                }
                is MapEffect.ShowFollowCanceled -> {
                    try {
                        snackbarHostState.showSnackbar("Follow canceled")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to show snackbar: ${e.message}", e)
                    }
                }
                is MapEffect.PerformGeocode -> { /* geocoding not yet implemented */ }
            }
        }
    }
}

/**
 * Placeholder composable wrapping MapLibre. The actual MapLibre Compose API
 * may differ slightly; this represents the target interface.
 */
@Composable
private fun MaplibreMap(
    modifier: Modifier,
    styleUri: String,
    cameraState: Any,
    gesturesEnabled: Boolean,
    onMapReady: (MapLibreMap) -> Unit,
    onStyleLoaded: (Style) -> Unit,
) {
    // TODO: Replace with actual MapLibre Compose API call when integrating.
    // The MapLibre Compose library (org.maplibre.compose:maplibre-compose:0.12.1)
    // provides MaplibreMap composable that should be used here.
    // For now this serves as a compilation scaffold.
    Box(modifier)
}

private fun applyLayerConfig(style: Style, config: MapLibreLayerConfig?) {
    // Remove existing layer/source if present
    style.removeLayer(LAYER_HEATMAP)
    style.removeLayer(LAYER_LINE)
    style.removeSource(SOURCE_LAYER)

    if (config == null) return

    when (config) {
        is MapLibreLayerConfig.Heatmap -> {
            val source = GeoJsonSource(SOURCE_LAYER, config.geoJson)
            style.addSource(source)

            val heatmapLayer = HeatmapLayer(LAYER_HEATMAP, SOURCE_LAYER)
            heatmapLayer.setProperties(
                PropertyFactory.heatmapRadius(config.radiusPx),
                PropertyFactory.heatmapIntensity(config.intensity),
                PropertyFactory.heatmapOpacity(config.opacity),
                PropertyFactory.heatmapWeight(
                    Expression.get(config.weightProperty)
                ),
            )
            style.addLayer(heatmapLayer)
        }
        is MapLibreLayerConfig.Line -> {
            val source = GeoJsonSource(SOURCE_LAYER, config.geoJson)
            style.addSource(source)

            val lineLayer = LineLayer(LAYER_LINE, SOURCE_LAYER)
            lineLayer.setProperties(
                PropertyFactory.lineColor(config.colorArgb),
                PropertyFactory.lineWidth(config.widthDp),
                PropertyFactory.lineOpacity(config.opacity),
            )
            style.addLayer(lineLayer)
        }
    }
}

private fun applyUserOverlays(style: Style, overlays: List<MapOverlayState>) {
    // Remove existing user overlays
    style.removeLayer(LAYER_USER_DOT)
    style.removeSource(SOURCE_USER)
    style.removeLayer(LAYER_ACCURACY)
    style.removeSource(SOURCE_ACCURACY)

    overlays.forEach { overlay ->
        when (overlay) {
            is MapOverlayState.UserMarker -> {
                val geoJson = com.adsamcik.tracker.map.data.GeoJsonConverter.pointToFeature(
                    overlay.latLng.lat,
                    overlay.latLng.lng
                )
                val source = GeoJsonSource(SOURCE_USER, geoJson)
                style.addSource(source)

                val dot = CircleLayer(LAYER_USER_DOT, SOURCE_USER)
                dot.setProperties(
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleColor(android.graphics.Color.BLUE),
                    PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                    PropertyFactory.circleStrokeWidth(2f),
                )
                style.addLayer(dot)
            }
            is MapOverlayState.AccuracyCircle -> {
                val geoJson = com.adsamcik.tracker.map.data.GeoJsonConverter.pointToFeature(
                    overlay.latLng.lat,
                    overlay.latLng.lng
                )
                val source = GeoJsonSource(SOURCE_ACCURACY, geoJson)
                style.addSource(source)

                val circle = CircleLayer(LAYER_ACCURACY, SOURCE_ACCURACY)
                circle.setProperties(
                    PropertyFactory.circleRadius(overlay.radiusM.toFloat()),
                    PropertyFactory.circleColor(android.graphics.Color.argb(33, 66, 133, 244)),
                    PropertyFactory.circleStrokeColor(android.graphics.Color.argb(85, 66, 133, 244)),
                    PropertyFactory.circleStrokeWidth(1f),
                    PropertyFactory.circleOpacity(0.3f),
                )
                style.addLayer(circle)
            }
            is MapOverlayState.Polyline -> {
                // Polylines handled via MapLibreLayerConfig.Line in the main layer
            }
        }
    }
}
