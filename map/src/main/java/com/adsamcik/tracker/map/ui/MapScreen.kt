package com.adsamcik.tracker.map.ui

import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.adsamcik.tracker.logger.Reporter
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.map.basemap.BasemapManager
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.presentation.MapStore
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.presentation.udf.CameraModel
import com.adsamcik.tracker.map.presentation.udf.MapEffect
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.adsamcik.tracker.map.presentation.udf.MapOverlayState
import com.adsamcik.tracker.shared.map.CoordinateBounds
import com.adsamcik.tracker.shared.map.MapStyleProvider
import com.adsamcik.tracker.shared.preferences.Preferences
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.Feature
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.heatmapDensity
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.HeatmapLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Duration.Companion.milliseconds


/**
 * MapLibre-based MapScreen. Renders heatmaps, polylines, user location via
 * MapLibre Compose's declarative layer API with full reactivity.
 */
@Composable
fun MapScreen(
    store: MapStore,
    overlayMode: Boolean = false,
    bottomPaddingPx: Int = 0,
    isLocationPermissionGranted: Boolean = false,
) {
    val state by store.state.collectAsState()
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current

    // Resolve basemap style: custom imported PMTiles or bundled default.
    // PMTiles requires random-access I/O, so the bundled asset must be
    // extracted to the filesystem before MapLibre can read it.
    val basemapManager = remember { BasemapManager(context) }
    val prefs = remember { Preferences.getPref(context) }
    val basemapPathKey = remember {
        context.getString(com.adsamcik.tracker.map.R.string.settings_map_basemap_path_key)
    }
    val customPath by prefs.observeString(basemapPathKey, "")
        .collectAsState(initial = "")

    var defaultBasemapPath by remember {
        mutableStateOf(basemapManager.defaultBasemapPath())
    }

    LaunchedEffect(Unit) {
        defaultBasemapPath = basemapManager.ensureDefaultBasemap()
    }

    val baseStyle = remember(customPath, isDark, defaultBasemapPath) {
        when {
            customPath.isNotEmpty() -> {
                val json = MapStyleProvider.customStyleJson(customPath, isDark)
                if (json != null) {
                    BaseStyle.Json(json)
                } else {
                    defaultBasemapPath?.let {
                        BaseStyle.Json(MapStyleProvider.defaultStyleJson(it, isDark))
                    }
                }
            }
            defaultBasemapPath != null -> {
                BaseStyle.Json(
                    MapStyleProvider.defaultStyleJson(defaultBasemapPath!!, isDark)
                )
            }
            else -> null
        }
    }

    val density = LocalDensity.current
    val bottomPaddingDp = with(density) { bottomPaddingPx.toDp() }

    val snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }

    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(target = Position(0.0, 0.0), zoom = 2.0)
    )

    val gestureOptions = remember(overlayMode) {
        if (overlayMode) {
            GestureOptions(
                isRotateEnabled = false,
                isScrollEnabled = false,
                isTiltEnabled = false,
                isZoomEnabled = false,
                isDoubleTapEnabled = false,
                isQuickZoomEnabled = false,
            )
        } else {
            GestureOptions()
        }
    }

    val followCanceledText = stringResource(com.adsamcik.tracker.map.R.string.map_follow_canceled)

    Box(Modifier.fillMaxSize()) {
        if (baseStyle != null) {
            MaplibreMap(
                modifier = Modifier.fillMaxSize(),
                baseStyle = baseStyle,
                cameraState = cameraState,
                options = MapOptions(gestureOptions = gestureOptions),
            ) {
                // Declarative data layers -- reactive via Compose recomposition
                MapDataLayers(layerConfig = state.layerConfig)
                // Declarative user overlays
                MapUserOverlays(overlays = state.overlays.toList())
            }
        } else {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Layer loading indicator
        val isLayerLoading = state.layerLoadingProgress in 1..99
        androidx.compose.animation.AnimatedVisibility(
            visible = isLayerLoading,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Permission denied banner
        if (!isLocationPermissionGranted) {
            androidx.compose.animation.AnimatedVisibility(
                visible = true,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
            ) {
                androidx.compose.material3.Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(com.adsamcik.tracker.map.R.string.map_location_permission_denied),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomPaddingDp + 16.dp)
        )
    }

    // Observe gesture-initiated camera moves to cancel follow
    LaunchedEffect(cameraState) {
        snapshotFlow { cameraState.moveReason }
            .collect { reason ->
                if (!overlayMode && reason == CameraMoveReason.GESTURE) {
                    store.dispatch(MapEvent.FollowCanceled)
                }
            }
    }

    // Report camera position changes
    LaunchedEffect(cameraState) {
        snapshotFlow { cameraState.position }
            .distinctUntilChanged()
            .collect { pos ->
                store.dispatch(
                    MapEvent.CameraMoved(
                        CameraModel(
                            lat = pos.target.latitude,
                            lng = pos.target.longitude,
                            zoom = pos.zoom.toFloat(),
                            tilt = pos.tilt.toFloat(),
                            bearing = pos.bearing.toFloat(),
                        ),
                        byGesture = false,
                    )
                )
            }
    }

    // Consume one-off effects
    LaunchedEffect(Unit) {
        store.effects.collectLatest { effect ->
            when (effect) {
                is MapEffect.CenterCamera -> {
                    try {
                        val bounds = BoundingBox(
                            west = effect.bounds.left,
                            south = effect.bounds.bottom,
                            east = effect.bounds.right,
                            north = effect.bounds.top,
                        )
                        cameraState.animateTo(
                            boundingBox = bounds,
                            padding = PaddingValues(32.dp),
                            duration = 500.milliseconds,
                        )
                    } catch (e: Exception) {
                        Reporter.report(e)
                    }
                }
                is MapEffect.SetCameraBearing -> {
                    try {
                        cameraState.animateTo(
                            finalPosition = cameraState.position.copy(
                                bearing = effect.bearing.toDouble()
                            ),
                            duration = 500.milliseconds,
                        )
                    } catch (e: Exception) {
                        Reporter.report(e)
                    }
                }
                is MapEffect.ShowFollowCanceled -> {
                    try {
                        snackbarHostState.showSnackbar(followCanceledText)
                    } catch (e: Exception) {
                        Reporter.report(e)
                    }
                }
                is MapEffect.PerformGeocode -> {
                    try {
                        if (!Geocoder.isPresent()) {
                            snackbarHostState.showSnackbar(
                                context.getString(com.adsamcik.tracker.map.R.string.map_search_no_geocoder)
                            )
                        } else {
                            val geocoder = Geocoder(context)
                            val addresses = geocodeLocationName(geocoder, effect.query)
                            val address = addresses
                                ?.firstOrNull { it.hasLatitude() && it.hasLongitude() }
                            if (address == null) {
                                snackbarHostState.showSnackbar(
                                    context.getString(
                                        com.adsamcik.tracker.map.R.string.map_search_no_results,
                                        effect.query
                                    )
                                )
                            } else {
                                val lat = address.latitude
                                val lng = address.longitude
                                // ~1.1 km delta around the geocoded point,
                                // giving a reasonable zoom level for the result.
                                val delta = 0.01
                                val bounds = CoordinateBounds(
                                    topBound = lat + delta,
                                    rightBound = lng + delta,
                                    bottomBound = lat - delta,
                                    leftBound = lng - delta,
                                )
                                store.dispatch(MapEvent.GeocodeResult(bounds))
                            }
                        }
                    } catch (e: Exception) {
                        Reporter.report(e)
                        snackbarHostState.showSnackbar(
                            context.getString(
                                com.adsamcik.tracker.map.R.string.map_search_no_results,
                                effect.query
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Declarative data layer rendering. Replaces imperative applyLayerConfig().
 * Recomposes automatically when [layerConfig] changes.
 */
@Composable
private fun MapDataLayers(layerConfig: MapLibreLayerConfig?) {
    if (layerConfig == null) return

    key(layerConfig) {
        when (layerConfig) {
            is MapLibreLayerConfig.Heatmap -> {
                val source = rememberGeoJsonSource(
                    data = GeoJsonData.JsonString(layerConfig.geoJson)
                )
                HeatmapLayer(
                    id = "heatmap-layer",
                    source = source,
                    radius = const(layerConfig.radiusPx.dp),
                    intensity = const(layerConfig.intensity),
                    opacity = const(layerConfig.opacity),
                    weight = Feature[layerConfig.weightProperty] as Expression<FloatValue>,
                    color = buildHeatmapColorExpr(layerConfig.colorStops),
                )
            }
            is MapLibreLayerConfig.Line -> {
                val source = rememberGeoJsonSource(
                    data = GeoJsonData.JsonString(layerConfig.geoJson)
                )
                LineLayer(
                    id = "line-layer",
                    source = source,
                    color = const(Color(layerConfig.colorArgb)),
                    width = const(layerConfig.widthDp.dp),
                    opacity = const(layerConfig.opacity),
                )
            }
        }
    }
}

/**
 * Declarative user overlay rendering. Replaces imperative applyUserOverlays().
 * Each overlay gets a unique layer ID for stable Compose keys.
 */
@Composable
private fun MapUserOverlays(overlays: List<MapOverlayState>) {
    overlays.forEachIndexed { index, overlay ->
        when (overlay) {
            is MapOverlayState.UserMarker -> {
                val markerColor = MaterialTheme.colorScheme.primary
                val strokeColor = MaterialTheme.colorScheme.surface
                val src = rememberGeoJsonSource(
                    data = GeoJsonData.JsonString(
                        GeoJsonConverter.pointToFeature(overlay.latLng.lat, overlay.latLng.lng)
                    )
                )
                CircleLayer(
                    id = "user-dot-$index",
                    source = src,
                    radius = const(8.dp),
                    color = const(markerColor),
                    strokeColor = const(strokeColor),
                    strokeWidth = const(2.dp),
                )
            }
            is MapOverlayState.AccuracyCircle -> {
                val accuracyColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
                val src = rememberGeoJsonSource(
                    data = GeoJsonData.JsonString(
                        GeoJsonConverter.pointToFeature(overlay.latLng.lat, overlay.latLng.lng)
                    )
                )
                val radiusDp = overlay.radiusM.toFloat().coerceIn(10f, 200f).dp
                CircleLayer(
                    id = "accuracy-$index",
                    source = src,
                    radius = const(radiusDp),
                    color = const(accuracyColor),
                    opacity = const(0.3f),
                )
            }
            is MapOverlayState.Polyline -> {
                // Polylines handled via MapLibreLayerConfig.Line in the main data layer
            }
        }
    }
}

/**
 * Builds a heatmap color interpolation expression from color stops.
 */
@Suppress("UNCHECKED_CAST")
private fun buildHeatmapColorExpr(
    colorStops: List<Pair<Float, Int>>
): Expression<ColorValue> {
    val stops = colorStops
        .map { (stop, argb) -> stop.toNumber() to const(Color(argb)) }
        .toTypedArray()
    return interpolate(
        type = linear(),
        input = heatmapDensity(),
        stops = stops,
    )
}

private fun Float.toNumber(): Number = this

/**
 * Version-aware geocoding: uses the callback-based API on Android 13+ (API 33)
 * and falls back to the deprecated synchronous API on older devices.
 */
private suspend fun geocodeLocationName(
    geocoder: Geocoder,
    query: String,
    maxResults: Int = 1,
): List<Address>? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocationName(
                query,
                maxResults,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        continuation.resume(addresses)
                    }

                    override fun onError(errorMessage: String?) {
                        continuation.resume(null)
                    }
                },
            )
        }
    } else {
        withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            geocoder.getFromLocationName(query, maxResults)
        }
    }
}
