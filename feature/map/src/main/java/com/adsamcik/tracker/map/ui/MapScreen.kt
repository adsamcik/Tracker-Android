package com.adsamcik.tracker.map.ui

import android.os.StrictMode
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.map.MapLibreInitializer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.adsamcik.tracker.map.basemap.BasemapManager
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.graphics.PolylineOptimizer
import com.adsamcik.tracker.map.data.paddedBounds
import com.adsamcik.tracker.map.export.resolveOutputSizePx
import com.adsamcik.tracker.map.online.TileProvider
import com.adsamcik.tracker.map.presentation.MapStore
import com.adsamcik.tracker.map.presentation.bridge.LayerAnimation
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.presentation.bridge.buildFlowGradientStops
import com.adsamcik.tracker.map.presentation.bridge.renderKey
import com.adsamcik.tracker.map.presentation.bridge.boundsOrNull
import com.adsamcik.tracker.map.presentation.bridge.flattenedLeaves
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.adsamcik.tracker.map.presentation.sensors.LocationAndSensorsManager
import com.adsamcik.tracker.shared.base.location.UiLocationProvider
import com.adsamcik.tracker.map.presentation.udf.CameraModel
import com.adsamcik.tracker.map.presentation.udf.MapEffect
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.map.presentation.udf.MapOverlayState
import com.adsamcik.tracker.map.presentation.udf.PlaceCalloutModel
import com.adsamcik.tracker.map.presentation.udf.SpeedProbeModel
import com.adsamcik.tracker.map.shared.MapStyleProvider
import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.map.MapPreferenceKeys
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.Feature
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.heatmapDensity
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillExtrusionLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.HeatmapLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
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
import com.adsamcik.tracker.shared.utils.extension.formatSpeed
import com.adsamcik.tracker.map.ui.controls.mapChromeFrostedColor
import com.adsamcik.tracker.map.ui.controls.mapChromeGlassBorder
import com.adsamcik.tracker.map.ui.controls.rememberMapLocationPermissionFlow

private const val MAP_LOAD_TAG = "MapScreen"

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface MapScreenEntryPoint {
    fun uiLocationProvider(): UiLocationProvider
}
/**
 * Maximum initial zoom for a user with no data on the active layer. Above this,
 * the bundled basemap (z0-z6 PMTiles) has no detail and the user sees only a
 * solid background colour — a confusing first-frame for someone who just
 * navigated to the map. Capping to z=4 gives a country/region overview that
 * always renders real geography. User gestures override immediately after.
 */
private const val MAX_EMPTY_STATE_ZOOM = 4f

/**
 * Maximum zoom to restore from saved camera state. The bundled basemap is z0-z6,
 * so anything past z=6 renders as solid colour. Capping the restored zoom to z=8
 * gives the user a recognisable city-level view they can quickly zoom into rather
 * than a confusing solid-colour first frame. Users with custom imported PMTiles
 * that support higher zoom can still reach those zooms via the first pinch gesture.
 */
private const val MAX_RESTORE_ZOOM = 8f

/** Zoom bounds for the accessibility zoom buttons (MapLibre supports ~0–22). */
private const val MIN_BUTTON_ZOOM = 1f
private const val MAX_BUTTON_ZOOM = 20f


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
    shareCaptureRequest: com.adsamcik.tracker.map.export.MapShareResolution? = null,
    onShareCaptureStarted: () -> Unit = {},
    onShareCaptureFinished: (success: Boolean) -> Unit = {},
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
    val activeSessionId = activeSession?.id
    val livePathMapper = remember(activeSessionId) { AppendOnlyPathMapper<Location, LatLngModel>() }
    val activeTrackingPath = remember(isTracking, activeSessionId, livePathPoints) {
        val path = livePathPoints
        if (isTracking && activeSessionId != null && path != null && path.first == activeSessionId) {
            livePathMapper.update(path.second) {
                LatLngModel(lat = it.latitude, lng = it.longitude)
            }
        } else {
            livePathMapper.update(emptyList()) {
                LatLngModel(lat = it.latitude, lng = it.longitude)
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
        // Start null and let the IO-bound LaunchedEffect below populate this via
        // ensureDefaultBasemap(). Calling basemapManager.defaultBasemapPath() here would read the
        // filesystem on the main thread during composition (StrictMode DiskReadViolation).
        mutableStateOf<String?>(null)
    }
    var basemapLoadError by remember {
        mutableStateOf<String?>(null)
    }

    val onlineTilesState by store.onlineMapTiles.collectAsState()
    val onlineTilesEnabled = onlineTilesState.enabled
    val onlineProviderId = onlineTilesState.providerId
    val onlineCustomUrl = onlineTilesState.customUrl

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

    // Resolve the basemap style off the main thread. MapStyleProvider.defaultStyleJson /
    // customStyleJson read the PMTiles header from disk (readPmtilesZoomRange); doing that inside a
    // composition `remember {}` block ran on the main thread and produced StrictMode
    // DiskReadViolations every time one of the keys settled at startup. produceState runs the
    // resolution in a coroutine and hops to dispatchers.io for the disk work; baseStyle stays null
    // (showing the loading state) until the first resolution completes.
    val dispatchers = store.dispatchersProvider
    val baseStyle by produceState<BaseStyle?>(
        initialValue = null,
        customPath,
        isDark,
        defaultBasemapPath,
        onlineTilesEnabled,
        onlineProviderId,
        onlineCustomUrl,
    ) {
        value = withContext(dispatchers.io) {
            resolveBaseStyle(
                customPath = customPath,
                isDark = isDark,
                defaultBasemapPath = defaultBasemapPath,
                onlineTilesEnabled = onlineTilesEnabled,
                onlineProviderId = onlineProviderId,
                onlineCustomUrl = onlineCustomUrl,
            )
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
		observedPresence = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_observed_presence),
		locationHeatmap = stringResource(com.adsamcik.tracker.map.R.string.map_a11y_distinct_visits),
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
			// Defensive cap on restored zoom. The bundled basemap is z0-z6 PMTiles
			// (see MapStyleProvider.DEFAULT_BUNDLE_ZOOM_RANGE), so anything past z=6
			// renders as solid earth colour on first frame — confusing for users who
			// reopen the map after previously zooming in for a route view. Cap to z=8
			// to keep at least city-level context visible while still giving a sense
			// of "zoomed in"; the user's first gesture restores full zoom control.
			val cappedZoom = saved.zoom.toDouble().coerceAtMost(MAX_RESTORE_ZOOM.toDouble())
			CameraPosition(
				// GeoJSON / MapLibre Position is (longitude, latitude) — NOT (lat, lng).
				target = Position(saved.lng, saved.lat),
				zoom = cappedZoom,
				tilt = saved.tilt.toDouble(),
				bearing = saved.bearing.toDouble(),
			)
		} else {
			CameraPosition(target = Position(0.0, 0.0), zoom = 2.0)
		}
	}

    val cameraState = rememberCameraState(firstPosition = initialCameraPosition)
    var suppressNextCameraPersistence by remember { mutableStateOf(false) }
    var hasAppliedEmptyStateZoomCap by rememberSaveable { mutableStateOf(false) }
    // Dismiss state for the empty-data banner. Keyed to the active layer so switching to a
    // different (still empty) layer surfaces the banner again instead of staying hidden.
    var emptyStateDismissed by remember(activeLayerId) { mutableStateOf(false) }

    /**
     * MapLibre Compose's rememberCameraState is backed by rememberSaveable
     * (CameraStateSaver), so a NavHost re-entry can restore CameraState.position
     * after ignoring firstPosition. The firstPosition cap alone therefore does not
     * protect users from a stale z17 camera over the bundled z0-z6 basemap. The
     * previous animateTo workaround was also unreliable before the map projection
     * had settled, and it polluted MapStore persistence through the camera
     * snapshotFlow below. Keep this as an instant, tagged one-shot cap so
     * legitimate user zooms > z8 still persist after the opening frame.
     *
     * History: map-camera-zoom-cap-not-applied (id 96),
     * r1r7-map-empty-state-cap-persists (id 121),
     * r3r7-map-zoom-cap-caveat-doc (id 117).
     */
    LaunchedEffect(cameraState) {
        cameraState.position.cappedAt(MAX_RESTORE_ZOOM)?.let { capped ->
            suppressNextCameraPersistence = true
            cameraState.position = capped
        }
    }

    LaunchedEffect(hasNoData, cameraState) {
        if (!hasNoData || hasAppliedEmptyStateZoomCap) return@LaunchedEffect

        cameraState.awaitProjection()
        cameraState.position.cappedAt(MAX_EMPTY_STATE_ZOOM)?.let { capped ->
            suppressNextCameraPersistence = true
            cameraState.position = capped
        }
        hasAppliedEmptyStateZoomCap = true
    }

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

    val emptyStateSubtitle = when (activeLayerId) {
        "cell_heatmap",
        "signal_coverage",
        "signal_aurora" -> stringResource(com.adsamcik.tracker.map.R.string.map_empty_subtitle_cell)
        "wifi_heatmap",
        "wifi_count_heatmap" -> stringResource(com.adsamcik.tracker.map.R.string.map_empty_subtitle_wifi)
        "speed_heatmap" -> stringResource(com.adsamcik.tracker.map.R.string.map_empty_subtitle_speed)
        "vehicle_compliance" -> stringResource(com.adsamcik.tracker.map.R.string.map_empty_subtitle_vehicle_compliance)
        else -> stringResource(com.adsamcik.tracker.map.R.string.map_empty_subtitle_location)
    }

    // Tear down the native MapLibre renderer as soon as the screen leaves the foreground.
    // The render thread otherwise keeps drawing against a surface that becomes invalid during
    // background / screen-off / display-state transitions, which crashes natively inside
    // libmaplibre.so (mbgl::android::MapRenderer::render) and takes the whole process down.
    //
    // This must gate on RESUMED, not STARTED: on screen-off the SurfaceView's surface is destroyed
    // around onStop, so disposing the map only when the lifecycle drops below STARTED (i.e. at
    // onStop) races the surface teardown and the crash still slips through. Dropping the map at
    // onPause (below RESUMED) stops the GL thread *before* the surface is destroyed, closing the
    // window. The cost is that the map is re-created when returning from a transient pause (system
    // permission dialog, app switch, multi-window focus loss); cameraState is hoisted above this
    // gate so the camera position is restored seamlessly when the map returns to the foreground.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isMapVisible = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    var mapViewportSizePx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val activeTrackingColorArgb = MaterialTheme.colorScheme.primary.toArgb()

    // "Share map as image" capture. Rendering happens fully off-screen via MapSnapshotRenderer
    // (MapLibre's classic MapSnapshotter) — independent of whatever is on screen right now — so
    // this does not touch mapLibreReady/isMapVisible/baseStyle gating above; it only reuses their
    // already-resolved values as inputs.
    LaunchedEffect(shareCaptureRequest) {
        val resolution = shareCaptureRequest ?: return@LaunchedEffect
        val resolvedStyle = baseStyle ?: run {
            onShareCaptureFinished(false)
            return@LaunchedEffect
        }
        onShareCaptureStarted()
        val aspectRatio = mapViewportSizePx.let { size ->
            if (size.width > 0 && size.height > 0) size.width.toFloat() / size.height.toFloat() else 1f
        }
        val outputSize = resolution.resolveOutputSizePx(aspectRatio)
        val success = try {
            val bitmap = com.adsamcik.tracker.map.export.MapSnapshotRenderer.render(
                context = appContext,
                baseStyle = resolvedStyle,
                cameraPosition = cameraState.position,
                layerConfig = state.layerConfig,
                activeTrackingPath = activeTrackingPath,
                activeTrackingColorArgb = activeTrackingColorArgb,
                widthPx = outputSize.widthPx,
                heightPx = outputSize.heightPx,
            )
            store.mapImageShareHelper.saveAndShare(appContext, bitmap)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Reporter.report(e)
            false
        }
        onShareCaptureFinished(success)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates -> mapViewportSizePx = coordinates.size },
    ) {
        val resolvedBaseStyle = baseStyle
        if (resolvedBaseStyle != null && mapLibreReady && isMapVisible) {
            // MapLibre inflates its underlying AndroidView via
            // MapLibreMapOptions.createFromAttributes, which calls context.getFilesDir() on the
            // main thread — a one-time (~60 ms) disk read inside the library that cannot be moved
            // off-thread. Relax StrictMode's disk-read detection only for the frame that creates the
            // view (remember runs during composition, before the AndroidView factory in applyChanges;
            // SideEffect restores the original policy immediately after), so this benign library read
            // no longer logs a violation while genuine app-code reads are still caught. Our own
            // map-state init (basemap path, style JSON) was already moved off the main thread.
            val priorThreadPolicy = remember { StrictMode.allowThreadDiskReads() }
            SideEffect { StrictMode.setThreadPolicy(priorThreadPolicy) }
            MaplibreMap(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {
                        contentDescription = mapAccessibilitySummary
                    },
                baseStyle = resolvedBaseStyle,
                cameraState = cameraState,
                options = MapOptions(
                    gestureOptions = gestureOptions,
                    ornamentOptions = OrnamentOptions(
                        padding = PaddingValues(
                            start = 16.dp,
                            // The compass defaults to the top-end corner, same as the global
                            // settings gear button rendered by MainRoot ("settings_global":
                            // statusBarsPadding() + 4dp top + 8dp end, 48dp icon). A flat 16.dp
                            // here ignored the status bar/notch inset (leaving the compass
                            // un-offset from the notification bar) and put it in the same
                            // corner as the settings button, so the two overlapped. Reuse
                            // topInsetPadding (status bar height + margin) and push further
                            // down by the settings button's own footprint plus a gap so the
                            // compass always renders below it instead of on top of it.
                            top = topInsetPadding + 44.dp,
                            end = 16.dp,
                            bottom = bottomPaddingDp + 16.dp,
                        ),
                        isScaleBarEnabled = false,
                    ),
                ),
                onMapLoadFinished = {
                    isMapLoading = false
                },
                onMapClick = { position, _ ->
                    // The speed heatmap is interactive: tapping reads the avg/max speed travelled
                    // around the tapped point. Other layers leave the tap unconsumed so default
                    // map behaviour is unaffected. The probe radius tracks zoom (a fixed on-screen
                    // tap tolerance in dp), clamped to a sensible real-world range.
                    if (state.activeLayerIds.contains(SPEED_HEATMAP_LAYER_ID)) {
                        val metersPerDp = cameraState.metersPerDpAtTarget
                        val radiusMeters = (metersPerDp * SPEED_PROBE_TAP_TOLERANCE_DP)
                            .coerceIn(SPEED_PROBE_MIN_RADIUS_M, SPEED_PROBE_MAX_RADIUS_M)
                        store.dispatch(
                            MapEvent.ProbeSpeedAt(position.latitude, position.longitude, radiusMeters)
                        )
                        ClickResult.Consume
                    } else {
                        // No interactive layer: tap reveals the nearest place name (offline reverse
                        // geocoding) in a top-anchored callout.
                        store.dispatch(
                            MapEvent.ReverseGeocodeAt(position.latitude, position.longitude)
                        )
                        ClickResult.Consume
                    }
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

        // Empty state — no data for the active layer. A compact, dismissible frosted banner at the
        // top informs the user without the old large centered card blanketing the map. It reuses
        // the map chrome's frosted-glass material so it reads as part of the same design language.
        androidx.compose.animation.AnimatedVisibility(
            visible = hasNoData && !emptyStateDismissed,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topInsetPadding + 12.dp)
                .padding(horizontal = 16.dp),
            enter = androidx.compose.animation.fadeIn() +
                androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.fadeOut() +
                androidx.compose.animation.shrinkVertically(),
        ) {
            Surface(
                modifier = Modifier
                    .testTag("map_empty_state_card")
                    .widthIn(max = 440.dp),
                shape = RoundedCornerShape(24.dp),
                color = mapChromeFrostedColor(),
                border = mapChromeGlassBorder(),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = emptyStateSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { emptyStateDismissed = true },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("map_empty_state_dismiss"),
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(
                                com.adsamcik.tracker.map.R.string.map_empty_dismiss
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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

        // Interactive speed heatmap: callout with the speed/max speed at the tapped location, or a
        // hint to tap when the speed heatmap is active but nothing has been probed yet. Anchored at
        // the top because the bottom is occupied by the (separately-rendered) MapChromeHost controls.
        val speedProbe = state.speedProbe
        if (speedProbe != null) {
            SpeedProbeCallout(
                probe = speedProbe,
                topPadding = topInsetPadding + 12.dp,
                onDismiss = { store.dispatch(MapEvent.DismissSpeedProbe) },
            )
        } else if (state.activeLayerIds.contains(SPEED_HEATMAP_LAYER_ID) && !hasNoData) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 16.dp, end = 16.dp, top = topInsetPadding + 12.dp),
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 2.dp,
                shadowElevation = 1.dp,
            ) {
                Text(
                    text = stringResource(com.adsamcik.tracker.map.R.string.map_speed_probe_hint),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // Tap-to-identify place callout (offline reverse geocoding), shown when no interactive
        // layer consumed the tap. Mutually exclusive with the speed probe (which only triggers
        // when the speed heatmap is active).
        val placeCallout = state.placeCallout
        if (placeCallout != null) {
            PlaceCallout(
                callout = placeCallout,
                topPadding = topInsetPadding + 12.dp,
                onDismiss = { store.dispatch(MapEvent.DismissPlaceCallout) },
            )
        }

    }

    val uiLocationProvider = remember(appContext) {
        EntryPointAccessors.fromApplication(
            appContext,
            MapScreenEntryPoint::class.java,
        ).uiLocationProvider()
    }
    val locationManager = remember(appContext, uiLocationProvider) {
        LocationAndSensorsManager(appContext, uiLocationProvider)
    }

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
                if (suppressNextCameraPersistence) {
                    suppressNextCameraPersistence = false
                    return@collect
                }

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
                        // Real viewport from the projection. cameraToBounds (the store's fallback)
                        // assumes a single 256px tile is visible and badly under-covers the screen,
                        // so the heatmap would only fetch a central patch of the visible area.
                        visibleBounds = cameraState.projection
                            ?.queryVisibleBoundingBox()
                            ?.toPaddedBounds(),
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
                is MapEffect.CenterOnUser -> {
                    try {
                        // Preserve the current zoom when the effect carries none (continuous
                        // follow); otherwise snap to the requested comfortable zoom.
                        val targetZoom = effect.zoom ?: cameraState.position.zoom
                        cameraState.animateTo(
                            finalPosition = cameraState.position.copy(
                                target = Position(effect.lng, effect.lat),
                                zoom = targetZoom,
                            ),
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
                is MapEffect.ZoomBy -> {
                    try {
                        val current = cameraState.position.zoom
                        val target = (current + effect.delta)
                            .coerceIn(MIN_BUTTON_ZOOM.toDouble(), MAX_BUTTON_ZOOM.toDouble())
                        if (target != current) {
                            cameraState.animateTo(
                                finalPosition = cameraState.position.copy(zoom = target),
                                duration = 300.milliseconds,
                            )
                        }
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
 * GeoJSON source options for the data layers. [GeoJsonOptions.synchronousUpdate] applies in-memory
 * data updates on the same frame instead of asynchronously re-tiling on a worker thread. The async
 * default briefly clears the source between the old and new data, which makes the heatmap visibly
 * "pop" out and back in on every viewport refresh. These sources are refreshed frequently as the
 * user pans, so synchronous updates are the intended trade-off here (see GeoJsonOptions docs).
 */
private val SYNCHRONOUS_GEOJSON_OPTIONS = GeoJsonOptions(synchronousUpdate = true)

/**
 * Source options for gradient ribbons: [GeoJsonOptions.lineMetrics] computes line-distance metrics,
 * which MapLibre's `line-gradient` requires to resolve `lineProgress`. Synchronous updates match the
 * other data sources so live/reactive refreshes don't visibly pop.
 */
private val GRADIENT_GEOJSON_OPTIONS = GeoJsonOptions(synchronousUpdate = true, lineMetrics = true)

/**
 * Resolve the [BaseStyle] for the current basemap configuration. Performs disk I/O
 * (MapStyleProvider reads the PMTiles header), so this MUST be called off the main thread — see the
 * produceState that drives it in [MapScreen].
 */
private fun resolveBaseStyle(
    customPath: String,
    isDark: Boolean,
    defaultBasemapPath: String?,
    onlineTilesEnabled: Boolean,
    onlineProviderId: String,
    onlineCustomUrl: String,
): BaseStyle? {
    val onlineUri = if (onlineTilesEnabled) {
        val provider = TileProvider.resolve(onlineProviderId, onlineCustomUrl)
        MapStyleProvider.onlineStyleUri(provider, isDark)
    } else {
        null
    }
    return when {
        onlineUri != null -> BaseStyle.Uri(onlineUri)
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
            BaseStyle.Json(MapStyleProvider.defaultStyleJson(defaultBasemapPath, isDark))
        }
        else -> null
    }
}

/** Layer id of the speed heatmap (interactive: tap to read the speed at a location). */
private const val SPEED_HEATMAP_LAYER_ID = "speed_heatmap"

/** On-screen tap tolerance (dp) used to size the speed-probe query radius from the current zoom. */
private const val SPEED_PROBE_TAP_TOLERANCE_DP = 28.0

/** Minimum/maximum real-world radius (m) for a speed probe, so the query is useful at any zoom. */
private const val SPEED_PROBE_MIN_RADIUS_M = 20.0
private const val SPEED_PROBE_MAX_RADIUS_M = 250.0

/**
 * Callout shown when the user taps the interactive speed heatmap. Displays the average and maximum
 * speed recorded around the tapped point (in the user's configured units), or an explicit
 * no-data message when the spot has no nearby samples.
 */
@Composable
private fun BoxScope.SpeedProbeCallout(
    probe: SpeedProbeModel,
    topPadding: Dp,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(start = 16.dp, end = 16.dp, top = topPadding)
            .testTag("map_speed_probe_card"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(com.adsamcik.tracker.map.R.string.map_speed_probe_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (probe.hasData) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        SpeedProbeMetric(
                            label = stringResource(
                                com.adsamcik.tracker.map.R.string.map_speed_probe_avg_label
                            ),
                            value = context.resources.formatSpeed(context, probe.avgSpeedMps, 1),
                        )
                        SpeedProbeMetric(
                            label = stringResource(
                                com.adsamcik.tracker.map.R.string.map_speed_probe_max_label
                            ),
                            value = context.resources.formatSpeed(context, probe.maxSpeedMps, 1),
                        )
                    }
                } else {
                    Text(
                        text = stringResource(
                            com.adsamcik.tracker.map.R.string.map_speed_probe_empty
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(
                        com.adsamcik.tracker.map.R.string.map_speed_probe_dismiss
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SpeedProbeMetric(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Callout shown when the user taps the map to identify the nearest place (offline reverse
 * geocoding). Shows a loading state while resolving, the resolved place name, or an explicit
 * "no place here" message when nothing was found.
 */
@Composable
private fun BoxScope.PlaceCallout(
    callout: PlaceCalloutModel,
    topPadding: Dp,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(start = 16.dp, end = 16.dp, top = topPadding)
            .testTag("map_place_callout_card"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                when {
                    callout.isLoading -> {
                        Text(
                            text = stringResource(
                                com.adsamcik.tracker.map.R.string.map_place_callout_loading
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    callout.hasResult -> {
                        Text(
                            text = callout.title.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (!callout.subtitle.isNullOrBlank()) {
                            Text(
                                text = callout.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = stringResource(
                                com.adsamcik.tracker.map.R.string.map_place_callout_empty
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            IconButton(onClick = onDismiss) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(
                        com.adsamcik.tracker.map.R.string.map_place_callout_dismiss
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Convert the map's real visible bounding box into [Bounds] for spatial layer queries, padded by
 * [paddingFraction] on each side so a little data beyond the viewport is pre-fetched for smooth
 * panning. Returns `null` for degenerate or antimeridian-crossing boxes so the caller can fall back
 * to the camera-derived estimate.
 */
private fun BoundingBox.toPaddedBounds(paddingFraction: Double = 0.5): Bounds? =
    paddedBounds(
        north = northeast.latitude,
        east = northeast.longitude,
        south = southwest.latitude,
        west = southwest.longitude,
        paddingFraction = paddingFraction,
    )

/**
 * Declarative data layer rendering. Replaces imperative applyLayerConfig().
 * Recomposes automatically when [layerConfig] changes.
 */
@Composable
private fun MapDataLayers(layerConfig: MapLibreLayerConfig?) {
    if (layerConfig == null) return
    val animationsEnabled = rememberSystemAnimationsEnabled()

    val configs = remember(layerConfig) { layerConfig.flattenedLeaves() }
    val hasAnimatedFlow = animationsEnabled && configs.any {
        it is MapLibreLayerConfig.GradientLine && it.animation is LayerAnimation.Flow
    }
    val flowClockMs by produceState(0L, hasAnimatedFlow) {
        while (hasAnimatedFlow) {
            value = SystemClock.uptimeMillis()
            delay(FLOW_ANIMATION_FRAME_MILLIS)
        }
    }

    configs.forEachIndexed { index, config ->
        key(config.renderKey(index)) {
            when (config) {
            is MapLibreLayerConfig.Heatmap -> {
                val source = rememberLayerGeoJsonSource(
                    geoJson = config.geoJson,
                    options = SYNCHRONOUS_GEOJSON_OPTIONS,
                )
                val pulse = config.animation as? LayerAnimation.Pulse
                val pulseScale = if (pulse != null && animationsEnabled) {
                    val transition = rememberInfiniteTransition(label = "heatmap-pulse-$index")
                    val scale by transition.animateFloat(
                        initialValue = pulse.minScale,
                        targetValue = pulse.maxScale,
                        animationSpec = infiniteRepeatable(
                            animation = tween(pulse.periodMs, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "heatmap-pulse-scale-$index",
                    )
                    scale
                } else {
                    1f
                }
                val opacityScale = if (pulse != null && animationsEnabled) {
                    val span = (pulse.maxScale - pulse.minScale).takeIf { it > 0f } ?: 1f
                    0.85f + 0.15f * ((pulseScale - pulse.minScale) / span).coerceIn(0f, 1f)
                } else {
                    1f
                }
                HeatmapLayer(
                    id = "heatmap-layer-$index",
                    source = source,
                    radius = const((config.radiusPx * pulseScale).dp),
                    intensity = const(config.intensity * pulseScale),
                    opacity = const(config.opacity * opacityScale),
                    weight = Feature[config.weightProperty] as Expression<FloatValue>,
                    color = buildHeatmapColorExpr(config.colorStops),
                )
            }
                is MapLibreLayerConfig.HeatLine -> {
                val source = rememberGeoJsonSource(
                    data = GeoJsonData.JsonString(config.geoJson),
                    options = SYNCHRONOUS_GEOJSON_OPTIONS,
                )
                // A soft halo keeps the path visually part of the heat field, while the narrow
                // core preserves the actual travelled geometry. Colour reads the edge's resolved
                // heat directly, so neighbouring segments cannot add together and turn red.
                LineLayer(
                    id = "heat-line-glow-$index",
                    source = source,
                    color = buildFillColorExpr(config.colorStops, config.weightProperty),
                    width = buildLineWidthExpr(config.glowWidthDp),
                    opacity = const(config.glowOpacity),
                    cap = const(LineCap.Round),
                    join = const(LineJoin.Round),
                    blur = const(config.glowBlurDp.dp),
                )
                LineLayer(
                    id = "heat-line-core-$index",
                    source = source,
                    color = buildFillColorExpr(config.colorStops, config.weightProperty),
                    width = buildLineWidthExpr(config.widthDp),
                    opacity = const(config.opacity),
                    cap = const(LineCap.Round),
                    join = const(LineJoin.Round),
                )
            }
                is MapLibreLayerConfig.Line -> {
                val source = rememberLayerGeoJsonSource(
                    geoJson = config.geoJson,
                    options = SYNCHRONOUS_GEOJSON_OPTIONS,
                )
                // Realistic route styling: rounded caps/joins remove the hard spikes a GPS track
                // gets at turns, the width breathes with zoom instead of being a fixed-dp ribbon,
                // and an optional casing drawn underneath gives the line depth and legibility over
                // any basemap. Casing is composed first so it renders beneath the main stroke.
                config.casingColorArgb?.let { casingArgb ->
                    LineLayer(
                        id = "line-casing-$index",
                        source = source,
                        color = const(Color(casingArgb)),
                        width = buildLineWidthExpr(config.widthDp + config.casingWidthDp * 2f),
                        opacity = const(config.opacity),
                        cap = const(LineCap.Round),
                        join = const(LineJoin.Round),
                    )
                }
                LineLayer(
                    id = "line-layer-$index",
                    source = source,
                    color = const(Color(config.colorArgb)),
                    width = buildLineWidthExpr(config.widthDp),
                    opacity = const(config.opacity),
                    cap = const(LineCap.Round),
                    join = const(LineJoin.Round),
                    blur = const(config.blurDp.dp),
                )
            }
                is MapLibreLayerConfig.Fill -> {
                val source = rememberLayerGeoJsonSource(
                    geoJson = config.geoJson,
                    options = SYNCHRONOUS_GEOJSON_OPTIONS,
                )
                FillLayer(
                    id = "fill-layer-$index",
                    source = source,
                    color = buildFillColorExpr(config.colorStops, config.weightProperty),
                    opacity = const(config.opacity),
                    outlineColor = const(Color(config.outlineColorArgb ?: 0)),
                )
            }
                is MapLibreLayerConfig.FillExtrusion -> {
                val source = rememberLayerGeoJsonSource(
                    geoJson = config.geoJson,
                    options = SYNCHRONOUS_GEOJSON_OPTIONS,
                )
                FillExtrusionLayer(
                    id = "fill-extrusion-layer-$index",
                    source = source,
                    color = buildFillColorExpr(config.colorStops, config.weightProperty),
                    height = buildExtrusionHeightExpr(config.weightProperty, config.maxHeightMeters),
                    opacity = const(config.opacity),
                    verticalGradient = const(true),
                )
            }
                is MapLibreLayerConfig.Circle -> {
                val source = rememberLayerGeoJsonSource(
                    geoJson = config.geoJson,
                    options = SYNCHRONOUS_GEOJSON_OPTIONS,
                )
                // Render-time animation: a Pulse scales the marker radius via an infinite transition.
                // The pipeline output (geoJson/source) is unchanged — only the radius property is
                // re-applied each frame, so nothing re-aggregates.
                val pulse = config.animation as? LayerAnimation.Pulse
                val radiusScale = if (pulse != null && animationsEnabled) {
                    val transition = rememberInfiniteTransition(label = "circle-pulse")
                    val scale by transition.animateFloat(
                        initialValue = pulse.minScale,
                        targetValue = pulse.maxScale,
                        animationSpec = infiniteRepeatable(
                            animation = tween(pulse.periodMs, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "circle-pulse-scale",
                    )
                    scale
                } else {
                    1f
                }
                CircleLayer(
                    id = "circle-layer-$index",
                    source = source,
                    radius = buildCircleRadiusExpr(
                        config.weightProperty,
                        config.minRadiusDp * radiusScale,
                        config.maxRadiusDp * radiusScale,
                    ),
                    color = buildFillColorExpr(config.colorStops, config.weightProperty),
                    opacity = const(config.opacity),
                    strokeColor = const(Color(config.strokeColorArgb)),
                    strokeWidth = const(config.strokeWidthDp.dp),
                )
            }
                is MapLibreLayerConfig.GradientLine -> {
                // Attributed ribbon: line-gradient paints the route by a per-vertex weight (speed,
                // altitude, …) that flows along its length. The source carries line-distance metrics
                // (required by line-gradient); round caps/joins + an optional casing beneath give the
                // same smooth, realistic look as the polyline. Casing composed first = drawn under.
                val source = rememberLayerGeoJsonSource(
                    geoJson = config.geoJson,
                    options = GRADIENT_GEOJSON_OPTIONS,
                )
                val flow = config.animation as? LayerAnimation.Flow
                val gradientStops = if (flow != null && animationsEnabled) {
                    val periodMs = flow.periodMs.coerceAtLeast(1)
                    val phase = (flowClockMs % periodMs).toFloat() / periodMs + flow.phaseOffset
                    remember(flow.colorArgb, phase, flow.trailFraction) {
                        buildFlowGradientStops(
                            colorArgb = flow.colorArgb,
                            phase = phase,
                            trailFraction = flow.trailFraction,
                            sampleCount = FLOW_GRADIENT_SAMPLE_COUNT,
                        )
                    }
                } else {
                    config.gradientStops
                }
                config.casingColorArgb?.let { casingArgb ->
                    LineLayer(
                        id = "gradient-line-casing-$index",
                        source = source,
                        color = const(Color(casingArgb)),
                        width = buildLineWidthExpr(config.widthDp + config.casingWidthDp * 2f),
                        opacity = const(config.opacity),
                        cap = const(LineCap.Round),
                        join = const(LineJoin.Round),
                    )
                }
                LineLayer(
                    id = "gradient-line-$index",
                    source = source,
                    gradient = buildLineGradientExpr(gradientStops),
                    width = buildLineWidthExpr(config.widthDp),
                    opacity = const(config.opacity),
                    cap = const(LineCap.Round),
                    join = const(LineJoin.Round),
                )
            }

                is MapLibreLayerConfig.Symbol -> {
                val source = rememberLayerGeoJsonSource(
                    geoJson = config.geoJson,
                    options = SYNCHRONOUS_GEOJSON_OPTIONS,
                )
                val context = LocalContext.current
                val iconBitmap = remember(context, config.iconRes, config.iconSizeDp) {
                    requireNotNull(
                        sdfBitmapFromVector(context, config.iconRes, config.iconSizeDp),
                    ) {
                        "Unable to load map symbol drawable ${config.iconRes}"
                    }.asImageBitmap()
                }
                SymbolLayer(
                    id = "symbol-layer-$index",
                    source = source,
                    iconImage = image(
                        value = iconBitmap,
                        isSdf = true,
                    ),
                    iconColor = const(Color(config.iconColorArgb)),
                    iconHaloColor = const(Color(config.iconHaloColorArgb)),
                    iconHaloWidth = const(1.5f.dp),
                    iconAllowOverlap = const(config.allowOverlap),
                    textField = format(span(Feature[config.labelProperty].asString())),
                    textColor = const(Color(config.textColorArgb)),
                    textHaloColor = const(Color(config.textHaloColorArgb)),
                    textHaloWidth = const(1.5f.dp),
                    textSize = const(config.textSizeSp.sp),
                    textOffset = offset(0f.em, 1.5f.em),
                    textAnchor = const(SymbolAnchor.Center),
                    textOptional = const(true),
                    textAllowOverlap = const(config.allowOverlap),
                )
            }
                is MapLibreLayerConfig.Composite -> Unit
            }
        }
    }
}

@Composable
private fun rememberLayerGeoJsonSource(
    geoJson: String,
    options: GeoJsonOptions,
) = rememberGeoJsonSource(
    data = remember(geoJson) { GeoJsonData.JsonString(geoJson) },
    options = options,
)

@Composable
private fun rememberSystemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }
}

@Composable
private fun MapActiveTrackingLayer(path: List<LatLngModel>) {
    if (path.size < 2) return

    // Smooth the live path exactly like the historical route so the two never look different as a
    // session becomes history. Chaikin pins the endpoints, so the tip stays on the newest fix.
    val smoothed = remember(path) {
        PolylineOptimizer.optimize(
            path,
            toleranceMeters = LIVE_PATH_SIMPLIFY_TOLERANCE_METERS,
            maxPoints = LIVE_PATH_MAX_POINTS,
            smoothingIterations = LIVE_PATH_SMOOTHING_ITERATIONS,
        )
    }
    val source = rememberGeoJsonSource(
        data = GeoJsonData.JsonString(GeoJsonConverter.lineToFeatureCollection(smoothed))
    )
    val lineColor = MaterialTheme.colorScheme.primary
    val casingColor = MaterialTheme.colorScheme.surface
    // A light casing halo separates the active line from anything beneath and reads as "live".
    LineLayer(
        id = "active-tracking-casing",
        source = source,
        color = const(casingColor),
        width = buildLineWidthExpr(LIVE_PATH_WIDTH_DP + LIVE_PATH_CASING_DP * 2f),
        opacity = const(0.9f),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
    )
    LineLayer(
        id = "active-tracking-line",
        source = source,
        color = const(lineColor),
        width = buildLineWidthExpr(LIVE_PATH_WIDTH_DP),
        opacity = const(0.95f),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
    )
}

/** Douglas-Peucker tolerance (m) for smoothing the live tracking path before render. */
private const val LIVE_PATH_SIMPLIFY_TOLERANCE_METERS = 3.0

/** Point budget for the live tracking path; ample for shape yet cheap to smooth every fix. */
private const val LIVE_PATH_MAX_POINTS = 1500

/** Chaikin passes applied to the live tracking path (kept light for responsiveness). */
private const val LIVE_PATH_SMOOTHING_ITERATIONS = 1

/** Base stroke width (dp) of the live tracking line. */
private const val LIVE_PATH_WIDTH_DP = 5f

/** Extra width (dp) per side for the live tracking line's casing halo. */
private const val LIVE_PATH_CASING_DP = 2f

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

/**
 * Builds a fill color interpolation expression keyed on a per-feature numeric property in [0, 1].
 * Used by the legacy grid-tile heatmap to colour each tile by its normalized visit count.
 */
@Suppress("UNCHECKED_CAST")
private fun buildFillColorExpr(
    colorStops: List<Pair<Float, Int>>,
    weightProperty: String,
): Expression<ColorValue> {
    val stops = colorStops
        .map { (stop, argb) -> stop.toNumber() to const(Color(argb)) }
        .toTypedArray()
    return interpolate(
        type = linear(),
        input = Feature[weightProperty] as Expression<FloatValue>,
        stops = stops,
    )
}

private fun Float.toNumber(): Number = this

/**
 * Builds a zoom-interpolated line width so the stroke stays visually proportional across zoom
 * levels — a touch thinner when zoomed out, thicker when zoomed in — instead of a fixed-dp ribbon
 * that looks hairline on a city view and clumsy up close. [zoom] is the top-level interpolate input,
 * as MapLibre requires.
 */
private fun buildLineWidthExpr(baseWidthDp: Float): Expression<DpValue> = interpolate(
    type = linear(),
    input = zoom(),
    stops = arrayOf(
        10f.toNumber() to const((baseWidthDp * 0.75f).dp),
        15f.toNumber() to const(baseWidthDp.dp),
        19f.toNumber() to const((baseWidthDp * 1.5f).dp),
    ),
)

/**
 * Builds a `line-gradient` colour expression from pre-resolved (lineProgress -> ARGB) stops, so the
 * ribbon's colour flows along the route. Input is [Feature.lineProgress] (valid only on a source
 * with line metrics); each stop is already the vertex's weight-resolved colour.
 */
private fun buildLineGradientExpr(stops: List<Pair<Float, Int>>): Expression<ColorValue> = interpolate(
    type = linear(),
    input = Feature.lineProgress(),
    stops = stops.map { (progress, argb) -> progress.toNumber() to const(Color(argb)) }.toTypedArray(),
)

/**
 * Builds a fill-extrusion height expression: maps a per-feature weight in [0, 1] linearly to
 * `[0, maxHeightMeters]`, so denser cells stand physically taller.
 */
@Suppress("UNCHECKED_CAST")
private fun buildExtrusionHeightExpr(
    weightProperty: String,
    maxHeightMeters: Float,
): Expression<FloatValue> = interpolate(
    type = linear(),
    input = Feature[weightProperty] as Expression<FloatValue>,
    stops = arrayOf(
        0f.toNumber() to const(0f),
        1f.toNumber() to const(maxHeightMeters),
    ),
)

/**
 * Builds a circle-radius expression: maps a per-feature weight in [0, 1] linearly to
 * `[minRadiusDp, maxRadiusDp]`, so busier places render as larger dots.
 */
@Suppress("UNCHECKED_CAST")
private fun buildCircleRadiusExpr(
    weightProperty: String,
    minRadiusDp: Float,
    maxRadiusDp: Float,
): Expression<DpValue> = interpolate(
    type = linear(),
    input = Feature[weightProperty] as Expression<FloatValue>,
    stops = arrayOf(
        0f.toNumber() to const(minRadiusDp.dp),
        1f.toNumber() to const(maxRadiusDp.dp),
    ),
)

private fun MapLibreLayerConfig?.hasRenderableData(): Boolean = when (this) {
    null -> false
    is MapLibreLayerConfig.Heatmap -> geoJson.hasRenderableGeoJsonData()
    is MapLibreLayerConfig.HeatLine -> geoJson.hasRenderableGeoJsonData()
    is MapLibreLayerConfig.Line -> geoJson.hasRenderableGeoJsonData()
    is MapLibreLayerConfig.Fill -> geoJson.hasRenderableGeoJsonData()
    is MapLibreLayerConfig.FillExtrusion -> geoJson.hasRenderableGeoJsonData()
    is MapLibreLayerConfig.Circle -> geoJson.hasRenderableGeoJsonData()
    is MapLibreLayerConfig.GradientLine -> geoJson.hasRenderableGeoJsonData()
    is MapLibreLayerConfig.Symbol -> geoJson.hasRenderableGeoJsonData()
    is MapLibreLayerConfig.Composite -> layers.any { it.hasRenderableData() }
}

private const val FLOW_ANIMATION_FRAME_MILLIS = 83L
private const val FLOW_GRADIENT_SAMPLE_COUNT = 16

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

private fun CameraPosition.cappedAt(maxZoom: Float): CameraPosition? =
    if (zoom > maxZoom) copy(zoom = maxZoom.toDouble()) else null
