package com.adsamcik.tracker.map.ui

import android.util.Log
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.map.MapLibreInitializer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.map.basemap.BasemapManager
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.presentation.MapStore
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.presentation.bridge.renderKey
import com.adsamcik.tracker.map.presentation.bridge.boundsOrNull
import com.adsamcik.tracker.map.presentation.sensors.LocationAndSensorsManager
import com.adsamcik.tracker.map.presentation.udf.CameraModel
import com.adsamcik.tracker.map.presentation.udf.MapEffect
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.map.presentation.udf.MapOverlayState
import com.adsamcik.tracker.map.shared.MapStyleProvider
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.map.MapPreferenceKeys
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import org.maplibre.compose.map.OrnamentOptions
import com.adsamcik.tracker.shared.base.constant.LengthConstants
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.map.ui.controls.rememberMapLocationPermissionFlow

private const val MAP_LOAD_TAG = "MapScreen"


/**
 * MapLibre-based MapScreen. Renders heatmaps, polylines, user location via
 * MapLibre Compose's declarative layer API with full reactivity.
 */
@Composable
fun MapScreen(
    store: MapStore,
    snackbarHostState: SnackbarHostState,
    overlayMode: Boolean = false,
    bottomPaddingPx: Int = 0,
    isLocationPermissionGranted: Boolean = false,
    topInsetPadding: Dp = 0.dp,
) {
    val state by store.state.collectAsState()
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val appContext = context.applicationContext
    val locationPermissionFlow = rememberMapLocationPermissionFlow()
    val hasLocationPermission = isLocationPermissionGranted || locationPermissionFlow.isGranted
    var isPermissionBannerDismissed by rememberSaveable { mutableStateOf(false) }
    val trackerController = store.trackerController
    val isTracking by trackerController.isServiceRunningFlow.collectAsState()
    val activeSession by trackerController.sessionFlow.collectAsState()
    val livePathPoints by trackerController.pathPointsFlow.collectAsState()
    val activeTrackingPath by remember(isTracking, activeSession, livePathPoints) {
        derivedStateOf {
            if (!isTracking) {
                emptyList()
            } else {
                val sessionId = activeSession?.id
                val path = livePathPoints
                if (sessionId != null && path != null && path.first == sessionId) {
                    path.second.map { LatLngModel(lat = it.latitude, lng = it.longitude) }
                } else {
                    emptyList()
                }
            }
        }
    }

    // Resolve basemap style: custom imported PMTiles or bundled default.
    // PMTiles requires random-access I/O, so the bundled asset must be
    // extracted to the filesystem before MapLibre can read it.
    val basemapManager = remember { BasemapManager(context) }
    val prefs = remember { Preferences(context) }
    val basemapPathKey = remember {
        MapPreferenceKeys.BASEMAP_PATH
    }
    val customPath by prefs.observeString(basemapPathKey, "")
        .collectAsState(initial = "")

    var defaultBasemapPath by remember {
        mutableStateOf(basemapManager.defaultBasemapPath())
    }
    var basemapLoadError by remember {
        mutableStateOf<String?>(null)
    }

    LaunchedEffect(Unit) {
        basemapLoadError = null
        try {
            defaultBasemapPath = basemapManager.ensureDefaultBasemap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Reporter.w(MAP_LOAD_TAG, "Failed to prepare default basemap: ${e.message}")
            Reporter.report(e)
            defaultBasemapPath = null
            basemapLoadError = context.getString(com.adsamcik.tracker.map.R.string.map_basemap_unavailable)
        }
    }

    val baseStyle = remember(customPath, isDark, defaultBasemapPath) {
        val currentBasemapPath = defaultBasemapPath
        when {
            customPath.isNotEmpty() -> {
                val json = MapStyleProvider.customStyleJson(customPath, isDark)
                if (json != null) {
                    BaseStyle.Json(json)
                } else {
                    currentBasemapPath?.let {
                        BaseStyle.Json(MapStyleProvider.defaultStyleJson(it, isDark))
                    }
                }
            }
            currentBasemapPath != null -> {
                BaseStyle.Json(MapStyleProvider.defaultStyleJson(currentBasemapPath, isDark))
            }
            else -> null
        }
    }
    var isMapLoading by remember(baseStyle) { mutableStateOf(baseStyle != null) }

    // MapLibre requires UI-thread initialization on current SDKs.
    // Do it lazily when the user actually opens the map so app startup stays responsive.
    val mapLibreReady by MapLibreInitializer.isReady.collectAsState()
    LaunchedEffect(mapLibreReady) {
        if (!mapLibreReady) {
            MapLibreInitializer.initialize(appContext, store.dispatchersProvider)
        }
    }

    val density = LocalDensity.current
    val bottomPaddingDp = with(density) { bottomPaddingPx.toDp() }
    val sheetVisibility = state.sheet.visibility
    val shouldBlockMapGestures = sheetVisibility == com.adsamcik.tracker.map.presentation.udf.SheetVisibility.Expanded
    val hasUserLocation = remember(state.overlays) {
        derivedStateOf { state.overlays.any { it is MapOverlayState.UserMarker } }
    }
    val hasSearchResult = remember(state.overlays) {
        derivedStateOf { state.overlays.any { it is MapOverlayState.SearchMarker } }
    }
    val isLayerLoading = state.layerLoadingProgress in 1..99
    val isAccessibilityLoading = isMapLoading || isLayerLoading
    val hasActiveLayer = state.activeLayerIds.isNotEmpty()
    val activeLayerId = state.activeLayerIds.firstOrNull()
    val hasRenderableLayerData = remember(state.layerConfig) {
        state.layerConfig.hasRenderableData()
    }
    val hasNoData = !isAccessibilityLoading && hasActiveLayer && !hasRenderableLayerData
    val mapAccessibilityLabels = MapAccessibilityLabels(
        mapOverview = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_map_overview),
        centeredOnYourLocation = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_centered_on_location),
        showingYourLocation = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_showing_location),
        loadingData = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_loading_data),
        noRecordedData = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_no_recorded_data),
        noLayerSelected = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_no_layer_selected),
        searchResultVisible = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_search_result_visible),
        activeRecordingVisible = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_active_recording_visible),
        recordedRouteHistory = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_recorded_route_history),
        locationHeatmap = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_location_heatmap),
        cellHeatmap = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_cell_heatmap),
        wifiHeatmap = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_wifi_heatmap),
        wifiCountHeatmap = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_wifi_count_heatmap),
        speedHeatmap = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_speed_heatmap),
        savedMapData = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_saved_map_data),
        worldScale = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_world_scale),
        regionalScale = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_regional_scale),
        cityScale = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_city_scale),
        neighborhoodScale = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_neighborhood_scale),
        streetScale = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_street_scale),
    )
    val mapAccessibilitySummary = remember(
        state.isFollowing,
        hasUserLocation.value,
        hasSearchResult.value,
        isAccessibilityLoading,
        hasNoData,
        state.activeLayerIds,
        activeTrackingPath,
        state.camera.zoom,
        mapAccessibilityLabels,
    ) {
        buildMapAccessibilitySummary(
            isFollowing = state.isFollowing,
            hasUserLocation = hasUserLocation.value,
            hasSearchResult = hasSearchResult.value,
            isLoading = isAccessibilityLoading,
            hasNoData = hasNoData,
            activeLayerIds = state.activeLayerIds,
            activeTrackingVisible = activeTrackingPath.size >= 2,
            zoom = state.camera.zoom,
            labels = mapAccessibilityLabels,
        )
    }

    val initialCameraPosition = remember {
        val saved = state.camera
        if (saved.zoom > 0f) {
            CameraPosition(
                // GeoJSON / MapLibre Position is (longitude, latitude) — NOT (lat, lng).
                target = Position(saved.lng, saved.lat),
                zoom = saved.zoom.toDouble(),
                tilt = saved.tilt.toDouble(),
                bearing = saved.bearing.toDouble(),
            )
        } else {
            CameraPosition(target = Position(0.0, 0.0), zoom = 2.0)
        }
    }

    val cameraState = rememberCameraState(firstPosition = initialCameraPosition)

    val selectedTripBounds = remember(state.selectedTripContext, state.layerConfig) {
        if (state.selectedTripContext != null) {
            state.layerConfig?.boundsOrNull()
        } else {
            null
        }
    }

    LaunchedEffect(selectedTripBounds, isMapLoading) {
        val bounds = selectedTripBounds ?: return@LaunchedEffect
        if (isMapLoading) return@LaunchedEffect
        try {
            cameraState.animateTo(
                boundingBox = BoundingBox(
                    west = bounds.left,
                    south = bounds.bottom,
                    east = bounds.right,
                    north = bounds.top,
                ),
                padding = PaddingValues(48.dp),
                duration = 500.milliseconds,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Reporter.report(e)
        }
    }

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
    val emptyStateSubtitle = when (activeLayerId) {
        "cell_heatmap" -> stringResource(com.adsamcik.tracker.map.R.string.map_empty_subtitle_cell)
        "wifi_heatmap",
        "wifi_count_heatmap" -> stringResource(com.adsamcik.tracker.map.R.string.map_empty_subtitle_wifi)
        "speed_heatmap" -> stringResource(com.adsamcik.tracker.map.R.string.map_empty_subtitle_speed)
        else -> stringResource(com.adsamcik.tracker.map.R.string.map_empty_subtitle_location)
    }

    Box(Modifier.fillMaxSize()) {
        if (baseStyle != null && mapLibreReady) {
            MaplibreMap(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {
                        contentDescription = mapAccessibilitySummary
                    },
                baseStyle = baseStyle,
                cameraState = cameraState,
                options = MapOptions(
                    gestureOptions = gestureOptions,
                    ornamentOptions = OrnamentOptions(
                        padding = PaddingValues(
                            start = 16.dp,
                            top = 16.dp,
                            end = 16.dp,
                            bottom = bottomPaddingDp + 16.dp,
                        ),
                        isScaleBarEnabled = false,
                    ),
                ),
                onMapLoadFinished = {
                    isMapLoading = false
                },
                onMapLoadFailed = { reason ->
                    isMapLoading = false
                    Log.e(
                        MAP_LOAD_TAG,
                        "MapLibre failed to load basemap: ${reason ?: "unknown reason"}",
                    )
                },
            ) {
                // Declarative data layers -- reactive via Compose recomposition
                MapDataLayers(layerConfig = state.layerConfig)
                MapActiveTrackingLayer(path = activeTrackingPath)
                // Declarative user overlays
                MapUserOverlays(
                    overlays = state.overlays.toList(),
                    metersPerDp = cameraState.metersPerDpAtTarget,
                )
            }
        } else if (baseStyle == null && basemapLoadError != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 3.dp,
                shadowElevation = 2.dp,
            ) {
                Text(
                    text = basemapLoadError.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        } else {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Nav bar already labels this tab "Map" — a duplicate "Map" chip over the map was
        // noise; the content is unmistakably a map.

        androidx.compose.animation.AnimatedVisibility(
            visible = isMapLoading,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topInsetPadding + 12.dp),
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                tonalElevation = 3.dp,
                shadowElevation = 2.dp,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(com.adsamcik.tracker.map.R.string.map_loading),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        // Empty state — no data for active layer
        androidx.compose.animation.AnimatedVisibility(
            visible = hasNoData,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(start = 32.dp, end = 32.dp, bottom = bottomPaddingDp),
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            Surface(
                modifier = Modifier.testTag("map_empty_state_card"),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 2.dp,
                shadowElevation = 2.dp,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(com.adsamcik.tracker.map.R.string.map_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                    Text(
                        text = emptyStateSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        if (shouldBlockMapGestures) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxSize()
                    .padding(bottom = bottomPaddingDp)
                    .pointerInput(sheetVisibility, bottomPaddingPx) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
            )
        }

        // Layer loading indicator
        val loadingLayerDesc = stringResource(com.adsamcik.tracker.map.R.string.map_loading_layer)
        androidx.compose.animation.AnimatedVisibility(
            visible = isLayerLoading,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = topInsetPadding)
                    .semantics {
                        contentDescription = loadingLayerDesc
                    }
            )
        }

        // Custom scale bar respecting user's length system setting
        ScaleBar(
            metersPerDp = cameraState.metersPerDpAtTarget,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = bottomPaddingDp + 40.dp),
        )

        // Permission denied banner with recovery action
        androidx.compose.animation.AnimatedVisibility(
            visible = !hasLocationPermission && !isPermissionBannerDismissed,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topInsetPadding + 16.dp, start = 16.dp, end = 16.dp),
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            Surface(
                modifier = Modifier
                    .testTag("map_permission_banner")
                    .clickable(
                        role = Role.Button,
                        onClick = locationPermissionFlow.requestLocationAccess,
                    ),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.errorContainer,
                tonalElevation = 2.dp,
                shadowElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(com.adsamcik.tracker.map.R.string.map_location_permission_denied),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.TextButton(
                        onClick = {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", context.packageName, null)
                            )
                            context.startActivity(intent)
                        }
                    ) {
                        Text(
                            text = stringResource(com.adsamcik.tracker.map.R.string.map_open_settings),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    IconButton(onClick = { isPermissionBannerDismissed = true }) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(
                                com.adsamcik.tracker.map.R.string.map_permission_banner_dismiss
                            ),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

    }

    val locationManager = remember(appContext) { LocationAndSensorsManager(appContext) }

    LaunchedEffect(locationManager, hasLocationPermission, overlayMode) {
        if (!hasLocationPermission || overlayMode) return@LaunchedEffect
        try {
            locationManager.locationUpdates(highAccuracy = true).collectLatest { (lat, lng, accuracy) ->
                store.dispatch(
                    MapEvent.SetUserLocation(
                        latLng = LatLngModel(lat = lat, lng = lng),
                        accuracyM = accuracy.coerceAtLeast(0.0),
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Reporter.report(e)
        }
    }

    LaunchedEffect(locationManager, overlayMode) {
        if (overlayMode) return@LaunchedEffect
        try {
            locationManager.bearingUpdates().collectLatest { bearing ->
                store.dispatch(MapEvent.SetBearing(bearing))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Reporter.report(e)
        }
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
                    } catch (e: CancellationException) {
                        throw e
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
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Reporter.report(e)
                    }
                }
                is MapEffect.ShowFollowCanceled -> {
                    try {
                        snackbarHostState.showSnackbar(followCanceledText)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Reporter.report(e)
                    }
                }
                is MapEffect.ShowSearchFormatHint -> {
                    try {
                        snackbarHostState.showSnackbar(
                            context.getString(com.adsamcik.tracker.map.R.string.map_search_invalid_format)
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Reporter.report(e)
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

    val configs = when (layerConfig) {
        is MapLibreLayerConfig.Composite -> layerConfig.layers
        else -> listOf(layerConfig)
    }

    configs.forEachIndexed { index, config ->
        key(config.renderKey(index)) {
            when (config) {
            is MapLibreLayerConfig.Heatmap -> {
                val source = rememberGeoJsonSource(
                    data = GeoJsonData.JsonString(config.geoJson)
                )
                HeatmapLayer(
                    id = "heatmap-layer-$index",
                    source = source,
                    radius = const(config.radiusPx.dp),
                    intensity = const(config.intensity),
                    opacity = const(config.opacity),
                    weight = Feature[config.weightProperty] as Expression<FloatValue>,
                    color = buildHeatmapColorExpr(config.colorStops),
                )
            }
                is MapLibreLayerConfig.Line -> {
                val source = rememberGeoJsonSource(
                    data = GeoJsonData.JsonString(config.geoJson)
                )
                LineLayer(
                    id = "line-layer-$index",
                    source = source,
                    color = const(Color(config.colorArgb)),
                    width = const(config.widthDp.dp),
                    opacity = const(config.opacity),
                )
            }
                is MapLibreLayerConfig.Composite -> Unit
            }
        }
    }
}

@Composable
private fun MapActiveTrackingLayer(path: List<LatLngModel>) {
    if (path.size < 2) return

    val source = rememberGeoJsonSource(
        data = GeoJsonData.JsonString(GeoJsonConverter.lineToFeatureCollection(path))
    )
    LineLayer(
        id = "active-tracking-line",
        source = source,
        color = const(MaterialTheme.colorScheme.primary),
        width = const(5.dp),
        opacity = const(0.95f),
    )
}

/**
 * Declarative user overlay rendering. Replaces imperative applyUserOverlays().
 * Each overlay gets a unique layer ID for stable Compose keys.
 */
@Composable
private fun MapUserOverlays(
    overlays: List<MapOverlayState>,
    metersPerDp: Double,
) {
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
                val radiusDp = if (metersPerDp > 0.0) {
                    (overlay.radiusM / metersPerDp).toFloat().coerceIn(1f, 512f).dp
                } else {
                    1.dp
                }
                CircleLayer(
                    id = "accuracy-$index",
                    source = src,
                    radius = const(radiusDp),
                    color = const(accuracyColor),
                    opacity = const(0.3f),
                )
            }
            is MapOverlayState.SearchMarker -> {
                val src = rememberGeoJsonSource(
                    data = GeoJsonData.JsonString(
                        GeoJsonConverter.pointToFeature(overlay.latLng.lat, overlay.latLng.lng)
                    )
                )
                CircleLayer(
                    id = "search-dot-$index",
                    source = src,
                    radius = const(10.dp),
                    color = const(MaterialTheme.colorScheme.tertiary),
                    strokeColor = const(MaterialTheme.colorScheme.surface),
                    strokeWidth = const(2.dp),
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

private fun MapLibreLayerConfig?.hasRenderableData(): Boolean = when (this) {
    null -> false
    is MapLibreLayerConfig.Heatmap -> geoJson.hasRenderableGeoJsonData()
    is MapLibreLayerConfig.Line -> geoJson.hasRenderableGeoJsonData()
    is MapLibreLayerConfig.Composite -> layers.any { it.hasRenderableData() }
}

private fun String.hasRenderableGeoJsonData(): Boolean =
    !contains(""""features":[]""") && !contains(""""coordinates":[]""")

/**
 * Custom Compose scale bar that respects the user's [LengthSystem] preference.
 * Replaces the built-in MapLibre scale bar which ignores in-app settings.
 */
@Composable
private fun ScaleBar(
    metersPerDp: Double,
    modifier: Modifier = Modifier,
) {
    if (metersPerDp <= 0.0) return
    val context = LocalContext.current
    val lengthSystem = remember { TrackerSettingsQuick.lengthSystem(context) }
    val spec = remember(metersPerDp, lengthSystem) {
        computeScaleBarSpec(metersPerDp, lengthSystem)
    } ?: return
    if (spec.widthDp < 20f || spec.widthDp > 250f) return

    val contentColor = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = "Map scale: ${spec.label}" },
    ) {
        Text(
            text = spec.label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
        Box(
            Modifier
                .padding(top = 2.dp)
                .width(spec.widthDp.dp)
                .height(2.dp)
                .background(contentColor),
        )
    }
}

private data class ScaleBarSpec(val widthDp: Float, val label: String)

private fun computeScaleBarSpec(
    metersPerDp: Double,
    lengthSystem: LengthSystem,
    targetWidthDp: Float = 100f,
): ScaleBarSpec? {
    val targetMeters = metersPerDp * targetWidthDp
    if (targetMeters <= 0.0) return null

    return when (lengthSystem) {
        LengthSystem.Metric, LengthSystem.AncientRoman -> {
            val niceMeters = findNiceNumber(targetMeters)
            val widthDp = (niceMeters / metersPerDp).toFloat()
            val label = if (niceMeters >= 1000.0) {
                "${formatNice(niceMeters / 1000.0)} km"
            } else {
                "${niceMeters.toInt()} m"
            }
            ScaleBarSpec(widthDp, label)
        }
        LengthSystem.Imperial, LengthSystem.Flying -> {
            val targetFeet = targetMeters * LengthConstants.FEET_IN_METERS
            if (targetFeet >= LengthConstants.FEET_IN_MILE * 0.5) {
                val niceMiles = findNiceNumber(targetFeet / LengthConstants.FEET_IN_MILE)
                val niceMeters = niceMiles * LengthConstants.METERS_IN_MILE
                val widthDp = (niceMeters / metersPerDp).toFloat()
                ScaleBarSpec(widthDp, "${formatNice(niceMiles)} mi")
            } else {
                val niceFeet = findNiceNumber(targetFeet)
                val niceMeters = niceFeet / LengthConstants.FEET_IN_METERS
                val widthDp = (niceMeters / metersPerDp).toFloat()
                ScaleBarSpec(widthDp, "${niceFeet.toInt()} ft")
            }
        }
        LengthSystem.Sailing -> {
            val targetNm = targetMeters / LengthConstants.METERS_IN_NAUTICAL_MILE
            if (targetNm >= 0.1) {
                val niceNm = findNiceNumber(targetNm)
                val niceMeters = niceNm * LengthConstants.METERS_IN_NAUTICAL_MILE
                val widthDp = (niceMeters / metersPerDp).toFloat()
                ScaleBarSpec(widthDp, "${formatNice(niceNm)} nmi")
            } else {
                val niceMeters = findNiceNumber(targetMeters)
                val widthDp = (niceMeters / metersPerDp).toFloat()
                ScaleBarSpec(widthDp, "${niceMeters.toInt()} m")
            }
        }
    }
}

private fun findNiceNumber(value: Double): Double {
    if (value <= 0.0) return 1.0
    val exponent = floor(log10(value))
    val magnitude = 10.0.pow(exponent)
    val fraction = value / magnitude
    val step = when {
        fraction < 1.5 -> 1.0
        fraction < 3.5 -> 2.0
        fraction < 7.5 -> 5.0
        else -> 10.0
    }
    return step * magnitude
}

private fun formatNice(value: Double): String =
    if (value == floor(value)) value.toInt().toString() else "%.1f".format(value)
