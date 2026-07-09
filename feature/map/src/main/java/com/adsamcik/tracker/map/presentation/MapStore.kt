package com.adsamcik.tracker.map.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.geocoder.ReverseGeocoder
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.cameraToBounds
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.map.export.MapImageShareHelper
import com.adsamcik.tracker.map.presentation.bridge.LayerEngine
import com.adsamcik.tracker.map.presentation.udf.LegendItem
import com.adsamcik.tracker.map.presentation.udf.CameraModel
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.adsamcik.tracker.map.presentation.udf.MapState
import com.adsamcik.tracker.map.presentation.udf.MapEffect
import com.adsamcik.tracker.map.presentation.udf.MapOverlayState
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.map.presentation.udf.PlaceCalloutModel
import com.adsamcik.tracker.map.presentation.udf.SearchResultStatus
import com.adsamcik.tracker.map.presentation.udf.SelectedTripMapContext
import com.adsamcik.tracker.map.presentation.udf.SheetVisibility
import com.adsamcik.tracker.map.presentation.udf.SpeedProbeModel
import com.adsamcik.tracker.map.shared.CoordinateBounds
import com.adsamcik.tracker.shared.preferences.map.OnlineMapTilesRepository
import com.adsamcik.tracker.shared.preferences.map.OnlineMapTilesState
import com.adsamcik.tracker.shared.preferences.map.MapSettingsRepository
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.floor

/**
 * MapStore: holds central MapState, reduces MapEvent, and bridges to LayerEngine.
 * Zero Google Maps dependencies -- uses CoordinateBounds and MapLibreLayerConfig.
 */
@HiltViewModel
class MapStore @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    val trackerController: TrackerServiceController,
    private val dispatchers: DispatchersProvider,
    private val onlineMapTilesRepository: OnlineMapTilesRepository,
    private val mapSettingsRepository: MapSettingsRepository,
    private val reverseGeocoder: ReverseGeocoder,
    val mapImageShareHelper: MapImageShareHelper,
) : ViewModel() {

    internal val dispatchersProvider: DispatchersProvider
        get() = dispatchers

    private enum class CoordinateAxis { Latitude, Longitude }

    private data class ParsedCoordinateComponent(
        val axis: CoordinateAxis,
        val value: Double
    )

    companion object {
        private const val SELECTED_LAYER_ID_KEY = "selected_layer_id"
        private const val NONE_LAYER_ID = "none"
        private const val DEFAULT_LAYER_ID = "location_polyline"
        private const val SPEED_LAYER_ID = "speed_heatmap"

        /**
         * Layers whose data is derived from the live location-fix stream, so they should re-render in
         * real time while a tracking session is gathering new fixes. Signal/wifi/exploration/place
         * layers are excluded — they are not tied to the live fix stream, so reactive refreshes would
         * add cost with no benefit.
         */
        private val LIVE_REACTIVE_LAYER_IDS = setOf(
            "location_heatmap",
            "speed_heatmap",
            "legacy_heatmap",
            "life_terrain",
        )

        /**
         * Debounce for reactive live refreshes. Coalesces a burst of gathered fixes into at most one
         * refresh per window and gives the tracker's batched writes time to commit before we re-query,
         * so the refresh sees the newest persisted fixes (any it misses are caught on the next tick).
         */
        private const val REACTIVE_REFRESH_DEBOUNCE_MS = 1_500L

        /** Zoom levels stepped per zoom-button tap. */
        private const val ZOOM_STEP = 1f

        /**
         * Absolute zoom used when explicitly centering on the user (my-location tap / first GPS
         * fix). A street/neighbourhood level that shows the immediate surroundings. Previously the
         * camera fit a bounding box derived from the GPS accuracy radius, which produced a near-max
         * (~z18) zoom that felt "too much"; a fixed comfortable zoom is predictable instead.
         */
        private const val CENTER_ON_USER_ZOOM = 16.0
        private const val CAMERA_LAT_KEY = "camera_lat"
        private const val CAMERA_LNG_KEY = "camera_lng"
        private const val CAMERA_ZOOM_KEY = "camera_zoom"
        private const val CAMERA_TILT_KEY = "camera_tilt"
        private const val CAMERA_BEARING_KEY = "camera_bearing"
        private const val TRIP_ID_KEY = "tripId"
        private const val TRIP_START_MS_KEY = "startMs"
        private const val TRIP_END_MS_KEY = "endMs"

        /** Viewport-bucket quantization: fraction of a span (see [viewportBucket]). */
        private const val BUCKET_FRACTION = 0.1

        /** Smallest viewport bucket cell, guarding against zero-width spans. */
        private const val MIN_BUCKET_DEGREES = 0.000001
        private val coordinatePartDelimiterRegex = Regex("[,;\\n]+")
        private val coordinateNumberRegex = Regex("[-+]?\\d+(?:\\.\\d+)?")
        private val hemisphereRegex = Regex("[NSEW]", RegexOption.IGNORE_CASE)
        private val coordinateWithHemisphereRegex = Regex(
            pattern = """(?i)(?:[NSEW]\s*[-+]?\d+(?:\.\d+)?(?:[^\dNSEW+-]+\d+(?:\.\d+)?){0,2}|[-+]?\d+(?:\.\d+)?(?:[^\dNSEW+-]+\d+(?:\.\d+)?){0,2}(?:[^\dNSEW+-]+)?\s*[NSEW])"""
        )
    }

    private var layerManager: LayerEngine? = null
    private val initialTripContext = readTripContext(savedStateHandle)

    fun setLayerEngine(engine: LayerEngine) {
        layerManager = engine
        if (_state.value.activeLayerIds.isNotEmpty()) {
            applyLayer()
        }
    }

    val isEngineReady: Boolean
        get() = layerManager != null

    private val _state = MutableStateFlow(
        MapState(
            camera = CameraModel(
                lat = savedStateHandle[CAMERA_LAT_KEY] ?: 0.0,
                lng = savedStateHandle[CAMERA_LNG_KEY] ?: 0.0,
                zoom = savedStateHandle[CAMERA_ZOOM_KEY] ?: 0f,
                tilt = savedStateHandle[CAMERA_TILT_KEY] ?: 0f,
                bearing = savedStateHandle[CAMERA_BEARING_KEY] ?: 0f,
            ),
            activeLayerIds = when {
                initialTripContext != null -> persistentSetOf(DEFAULT_LAYER_ID)
                savedStateHandle.get<String>(SELECTED_LAYER_ID_KEY) == null -> persistentSetOf(DEFAULT_LAYER_ID)
                savedStateHandle.get<String>(SELECTED_LAYER_ID_KEY) == NONE_LAYER_ID -> persistentSetOf()
                else -> persistentSetOf(requireNotNull(savedStateHandle.get<String>(SELECTED_LAYER_ID_KEY)))
            },
            dateRange = initialTripContext?.let { it.startMs..it.endMs } ?: (0L..Long.MAX_VALUE),
            selectedTripContext = initialTripContext,
        )
    )
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<MapEffect>(extraBufferCapacity = 3)
    val effects = _effects.asSharedFlow()

    /**
     * Continuously emitted snapshot of the user's online map tile preference.
     * Defaults to disabled. Consumed by `MapScreen` to decide whether to pass
     * a remote `style.json` URL into MapLibre.
     *
     * Mirroring this preference into the `NetworkGateway` (kill switch +
     * allowlist + per-host rate limit) is the responsibility of
     * `com.adsamcik.tracker.map.network.MapTilesPolicyContributor`, which is
     * Hilt-registered via `@IntoSet NetworkPolicyContributor` and consumed by
     * `com.adsamcik.tracker.network.NetworkPolicyAggregator`. Keeping the
     * gateway plumbing OUT of `MapStore` is what lets a second consumer
     * (e.g. OSM PMTiles offline download) publish its own contribution
     * without fighting the map for global gateway state.
     */
    val onlineMapTiles: StateFlow<OnlineMapTilesState> =
        onlineMapTilesRepository.data
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = OnlineMapTilesState(),
            )

    /**
     * Whether the legacy grid-tile heatmap (easter egg) is enabled in Map settings. The map chrome
     * uses this to show/hide the "Legacy heatmap" entry in the layer picker.
     */
    val legacyHeatmapEnabled: StateFlow<Boolean> =
        mapSettingsRepository.data
            .map { it.legacyHeatmapEnabled }
            .distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    /**
     * Whether the on-screen accessibility zoom in/out buttons are enabled in Map settings. The map
     * chrome uses this to show/hide the zoom controls.
     */
    val zoomButtonsEnabled: StateFlow<Boolean> =
        mapSettingsRepository.data
            .map { it.zoomButtonsEnabled }
            .distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    private var lastBearing: Float = 0f

    private var applyLayerJob: Job? = null
    private var cameraRefreshJob: Job? = null
    private var reactiveRefreshJob: Job? = null
    private var speedProbeJob: Job? = null
    private var placeCalloutJob: Job? = null
    private var searchJob: Job? = null
    /**
     * Latest real viewport bounds reported by the map projection (see
     * [MapEvent.CameraMoved.visibleBounds]). Preferred over the camera-derived [cameraToBounds]
     * estimate for spatial layer queries; the estimate badly under-covers the viewport because it
     * assumes a single 256px tile is visible. Kept as the last non-null value so a transient null
     * (projection not ready) does not collapse the fetch box.
     */
    @Volatile
    private var lastVisibleBounds: Bounds? = null

    /**
     * Viewport "bucket" (coarsely-quantized bounds + integer zoom) whose data is currently rendered.
     * A camera move that lands in the same bucket already has its heatmap drawn, so re-running the
     * fetch/aggregate/encode pipeline would only re-publish identical data — wasted work that can
     * still cause a visible refresh. Used by [MapEvent.CameraMoved] to skip redundant refreshes on
     * micro-pans. Reset by [applyLayer] (a full re-select re-establishes the rendered bucket) and
     * updated by [refreshLayerDataInPlace] once a refresh actually completes.
     */
    @Volatile
    private var lastRenderedViewportBucket: ViewportBucket? = null
    private var overlayUpdateJob: Job? = null
    private var lastLocationUpdate: Long = 0L
    private val locationUpdateDebounceMs = 100L
    private val cameraRefreshDebounceMs = 500L
    private var hasReceivedInitialLocation: Boolean = false
    private var lastKnownUserLocation: LatLngModel? = null

    init {
        // Heatmap quality is configured in the Map settings screen (not an in-map pill anymore).
        // Observe it here so the persisted value at startup and any later setting change drive the
        // rendered quality. Reuses the existing SetQuality reducer (updates state + re-applies the
        // active layer); applyLayer() is a no-op until the engine is attached, after which
        // setLayerEngine() applies the current quality.
        viewModelScope.launch {
            mapSettingsRepository.data
                .map { it.quality }
                .distinctUntilChanged()
                .collect { quality ->
                    if (_state.value.quality != quality) {
                        dispatch(MapEvent.SetQuality(quality))
                    }
                }
        }
    }

    fun dispatch(event: MapEvent) {
        when (event) {
            is MapEvent.ShowSheet -> {
                _state.update { it.copy(sheet = it.sheet.copy(visibility = SheetVisibility.Expanded)) }
            }
            is MapEvent.HideSheet -> {
                _state.update { it.copy(sheet = it.sheet.copy(visibility = SheetVisibility.Hidden)) }
            }
            is MapEvent.SetSheet -> {
                _state.update { it.copy(sheet = it.sheet.copy(visibility = event.visibility)) }
            }
            is MapEvent.SelectLayer -> {
                speedProbeJob?.cancel()
                placeCalloutJob?.cancel()
                _state.update { current ->
                    val updatedLayerIds = if (event.id == NONE_LAYER_ID) {
                        persistentSetOf()
                    } else {
                        persistentSetOf(event.id)
                    }
                    current.copy(activeLayerIds = updatedLayerIds, speedProbe = null, placeCallout = null)
                }
                savedStateHandle[SELECTED_LAYER_ID_KEY] = if (event.id == NONE_LAYER_ID) {
                    NONE_LAYER_ID
                } else {
                    event.id
                }
                applyLayer()
            }
            is MapEvent.ToggleFollow -> {
                dispatch(MapEvent.SetFollowing(!_state.value.isFollowing))
            }
            is MapEvent.SetFollowing -> {
                val nowFollowing = event.isFollowing
                _state.update { it.copy(isFollowing = nowFollowing) }
                if (nowFollowing) {
                    _state.update { cur ->
                        val updatedOverlays = cur.overlays.map { ov ->
                            if (ov is MapOverlayState.UserMarker) ov.copy(bearing = lastBearing) else ov
                        }
                        cur.copy(overlays = persistentListOf(*updatedOverlays.toTypedArray()))
                    }
                    emitCenterOnUser(resetZoom = true)
                    _effects.tryEmit(MapEffect.SetCameraBearing(lastBearing))
                }
            }
            is MapEvent.FollowCanceled -> {
                _state.update { it.copy(isFollowing = false) }
            }
            is MapEvent.SetQuality -> {
                _state.update { it.copy(quality = event.value) }
                applyLayer()
            }
            is MapEvent.SetDateRange -> {
                _state.update { it.copy(dateRange = event.range, selectedTripContext = null) }
                applyLayer()
            }
            is MapEvent.UpdateSearchQuery -> {
                _state.update {
                    it.copy(
                        search = it.search.copy(query = event.query, resultStatus = SearchResultStatus.Idle),
                        overlays = if (event.query.isBlank()) {
                            persistentListOf(
                                *it.overlays.filterNot { overlay ->
                                    overlay is MapOverlayState.SearchMarker
                                }.toTypedArray()
                            )
                        } else {
                            it.overlays
                        }
                    )
                }
            }
            is MapEvent.SetSearchFocus -> {
                _state.update { it.copy(search = it.search.copy(hasFocus = event.hasFocus)) }
            }
            is MapEvent.SubmitSearch -> {
                val q = _state.value.search.query.trim()
                if (q.isEmpty()) {
                    _effects.tryEmit(MapEffect.ShowSearchFormatHint)
                    return
                }
                val coordinate = parseCoordinateQuery(q)
                if (coordinate != null) {
                    applySearchResult(coordinate)
                } else {
                    searchPlacesByName(q)
                }
            }
            is MapEvent.CameraMoved -> {
                _state.update { it.copy(camera = event.position) }
                event.visibleBounds?.let { lastVisibleBounds = it }
                savedStateHandle[CAMERA_LAT_KEY] = event.position.lat
                savedStateHandle[CAMERA_LNG_KEY] = event.position.lng
                savedStateHandle[CAMERA_ZOOM_KEY] = event.position.zoom
                savedStateHandle[CAMERA_TILT_KEY] = event.position.tilt
                savedStateHandle[CAMERA_BEARING_KEY] = event.position.bearing
                // Debounced viewport refresh so heatmaps update data without rebuilding layers.
                // Skip entirely when the camera is still inside the viewport bucket we already
                // rendered — refetching there would only re-publish identical data, an update the
                // user perceives as the heatmap needlessly "flickering" while panning.
                if (_state.value.activeLayerIds.any(::isBoundsSensitiveLayer)) {
                    val bucket = viewportBucket(lastVisibleBounds, event.position.zoom)
                    if (bucket == null || bucket != lastRenderedViewportBucket) {
                        cameraRefreshJob?.cancel()
                        cameraRefreshJob = viewModelScope.launch {
                            delay(cameraRefreshDebounceMs)
                            refreshLayerDataInPlace()
                        }
                    }
                }
            }
            is MapEvent.SetUserLocation -> {
                val now = System.currentTimeMillis()
                if (now - lastLocationUpdate < locationUpdateDebounceMs) {
                    overlayUpdateJob?.cancel()
                    overlayUpdateJob = viewModelScope.launch {
                        delay(locationUpdateDebounceMs)
                        updateUserLocation(event.latLng, event.accuracyM)
                    }
                } else {
                    lastLocationUpdate = now
                    updateUserLocation(event.latLng, event.accuracyM)
                }
            }
            is MapEvent.SetBearing -> {
                val normalizedBearing = ((event.bearing % 360f) + 360f) % 360f
                if (kotlin.math.abs(normalizedBearing - lastBearing) > 1f) {
                    lastBearing = normalizedBearing
                    val st = _state.value
                    if (st.isFollowing) {
                        _effects.tryEmit(MapEffect.SetCameraBearing(lastBearing))
                    }
                    _state.update { cur ->
                        val updatedOverlays = cur.overlays.map { ov ->
                            if (ov is MapOverlayState.UserMarker) {
                                ov.copy(bearing = if (cur.isFollowing) lastBearing else null)
                            } else {
                                ov
                            }
                        }
                        cur.copy(overlays = persistentListOf(*updatedOverlays.toTypedArray()))
                    }
                }
            }
            is MapEvent.ProbeSpeedAt -> probeSpeedAt(event.lat, event.lng, event.radiusMeters)
            is MapEvent.DismissSpeedProbe -> {
                speedProbeJob?.cancel()
                _state.update { it.copy(speedProbe = null) }
            }
            is MapEvent.ReverseGeocodeAt -> reverseGeocodeAt(event.lat, event.lng)
            is MapEvent.DismissPlaceCallout -> {
                placeCalloutJob?.cancel()
                _state.update { it.copy(placeCallout = null) }
            }
            is MapEvent.ZoomIn -> _effects.tryEmit(MapEffect.ZoomBy(ZOOM_STEP))
            is MapEvent.ZoomOut -> _effects.tryEmit(MapEffect.ZoomBy(-ZOOM_STEP))
        }
    }

    fun setQuality(value: Float) {
        dispatch(MapEvent.SetQuality(value))
    }

    fun setDateRange(range: LongRange) {
        dispatch(MapEvent.SetDateRange(range))
    }

    private fun updateUserLocation(latLng: LatLngModel, accuracyM: Double) {
        val isInitial = !hasReceivedInitialLocation
        if (isInitial) {
            hasReceivedInitialLocation = true
        }
        lastKnownUserLocation = latLng

        _state.update { st ->
            val userOverlayIndices = mutableListOf<Int>()
            st.overlays.forEachIndexed { index, overlay ->
                if (overlay is MapOverlayState.UserMarker || overlay is MapOverlayState.AccuracyCircle) {
                    userOverlayIndices.add(index)
                }
            }
            val filteredOverlays = st.overlays.filterIndexed { index, _ ->
                index !in userOverlayIndices
            }
            val newUserOverlays = listOf(
                MapOverlayState.UserMarker(latLng, bearing = if (st.isFollowing) lastBearing else null),
                MapOverlayState.AccuracyCircle(latLng, accuracyM)
            )
            st.copy(overlays = persistentListOf(*(filteredOverlays + newUserOverlays).toTypedArray()))
        }
        if (isInitial || _state.value.isFollowing) {
            // First fix snaps to a comfortable zoom; continuous follow updates pan only so the
            // user can zoom freely while following.
            emitCenterOnUser(resetZoom = isInitial)
            if (_state.value.isFollowing) {
                _effects.tryEmit(MapEffect.SetCameraBearing(lastBearing))
            }
        }
    }

    private fun emitCenterOnUser(resetZoom: Boolean) {
        val location = lastKnownUserLocation ?: return
        _effects.tryEmit(
            MapEffect.CenterOnUser(
                lat = location.lat,
                lng = location.lng,
                // Explicit / first centre snaps to a comfortable zoom; continuous follow updates
                // keep the user's current zoom so following never fights a manual zoom-out.
                zoom = if (resetZoom) CENTER_ON_USER_ZOOM else null,
            )
        )
    }

    /**
     * Query the speed summary around (lat, lng) and publish it as [MapState.speedProbe]. No-op
     * unless the speed heatmap is the active layer. A tap with no nearby data still publishes a
     * probe with sampleCount 0 so the UI can show an explicit "no data here" message.
     */
    private fun probeSpeedAt(lat: Double, lng: Double, radiusMeters: Double) {
        val engine = layerManager ?: return
        if (!_state.value.activeLayerIds.contains(SPEED_LAYER_ID)) return
        speedProbeJob?.cancel()
        speedProbeJob = viewModelScope.launch {
            val dateRange = _state.value.dateRange
            val summary = try {
                withContext(dispatchers.default) {
                    engine.querySpeedSummaryAt(lat, lng, radiusMeters, dateRange)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Reporter.report(e)
                null
            }
            _state.update {
                it.copy(
                    speedProbe = SpeedProbeModel(
                        latLng = LatLngModel(lat, lng),
                        avgSpeedMps = summary?.avgSpeedMps ?: 0.0,
                        maxSpeedMps = summary?.maxSpeedMps ?: 0.0,
                        sampleCount = summary?.sampleCount ?: 0,
                    )
                )
            }
        }
    }

    /**
     * Reverse-geocode the tapped point and publish it as [MapState.placeCallout]. Shows a loading
     * callout immediately, then resolves the nearest place name fully offline. A tap with no
     * resolvable place still publishes a callout (hasResult = false) so the UI can show an explicit
     * "no place here" message rather than silently doing nothing.
     */
    private fun reverseGeocodeAt(lat: Double, lng: Double) {
        placeCalloutJob?.cancel()
        _state.update {
            it.copy(placeCallout = PlaceCalloutModel(LatLngModel(lat, lng), isLoading = true))
        }
        placeCalloutJob = viewModelScope.launch {
            val place = try {
                reverseGeocoder.reverseGeocode(lat, lng)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Reporter.report(e)
                null
            }
            _state.update {
                it.copy(
                    placeCallout = PlaceCalloutModel(
                        latLng = LatLngModel(lat, lng),
                        title = place?.displayName,
                        subtitle = place?.countryCode,
                        isLoading = false,
                    )
                )
            }
        }
    }

    /**
     * Forward-search a free-text place name (non-coordinate query) against the offline places
     * dataset and jump the camera to the best (most prominent) match. Falls back to the existing
     * NotFound + format-hint path when nothing matches.
     */
    private fun searchPlacesByName(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val best = try {
                reverseGeocoder.searchPlaces(query, limit = 1).firstOrNull()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Reporter.report(e)
                null
            }
            if (best == null) {
                _state.update {
                    it.copy(search = it.search.copy(resultStatus = SearchResultStatus.NotFound))
                }
                _effects.tryEmit(MapEffect.ShowSearchFormatHint)
            } else {
                applySearchResult(LatLngModel(best.latitude, best.longitude))
            }
        }
    }

    /** Mark the search as found, drop a search marker, and center the camera on [coordinate]. */
    private fun applySearchResult(coordinate: LatLngModel) {
        _state.update { current ->
            current.copy(
                isFollowing = false,
                search = current.search.copy(resultStatus = SearchResultStatus.Found),
                overlays = persistentListOf(
                    *(current.overlays
                        .filterNot { overlay -> overlay is MapOverlayState.SearchMarker } +
                        MapOverlayState.SearchMarker(coordinate)).toTypedArray()
                )
            )
        }
        val delta = 0.005
        _effects.tryEmit(
            MapEffect.CenterCamera(
                CoordinateBounds(
                    topBound = coordinate.lat + delta,
                    rightBound = coordinate.lng + delta,
                    bottomBound = coordinate.lat - delta,
                    leftBound = coordinate.lng - delta
                )
            )
        )
    }

    private fun applyLayer() {
        val engine = layerManager ?: return
        cameraRefreshJob?.cancel()
        applyLayerJob?.cancel()
        // A full re-select renders fresh data (possibly a new layer / quality / date range); the
        // previously-rendered viewport bucket no longer reflects what's drawn, so invalidate it and
        // let the next camera move establish a new one.
        lastRenderedViewportBucket = null
        applyLayerJob = viewModelScope.launch {
            _state.update { it.copy(layerLoadingProgress = 50) }
            try {
                val s = _state.value
                val bounds = lastVisibleBounds
                    ?: cameraToBounds(s.camera.lat, s.camera.lng, s.camera.zoom.toDouble())
                withContext(dispatchers.default) {
                    engine.selectLayers(s.activeLayerIds, s.quality, s.dateRange, bounds, s.camera.zoom)
                }
                updateLayerStateFromEngine(engine, clearLayerOnMissingLegend = true)
                restartReactiveObserver()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update {
                    it.copy(
                        legend = persistentListOf(),
                        layerConfig = null,
                        layerLoadingProgress = 0
                    )
                }
            }
        }
    }

    /**
     * (Re)starts the reactive live-refresh observer for the currently active layers. Called whenever
     * the active layer set changes. The observer itself is a no-op unless a tracking session is
     * running and a live-reactive layer is active.
     *
     * Concurrency: the collector is a child of [viewModelScope] (cancelled on clear); a burst of
     * gathered fixes is `debounce`d + `conflate`d into at most one refresh per window; each refresh
     * goes through the single [applyLayerJob] slot (latest cancels the in-flight one) and the
     * generation guards in the engine drop any stale compute — so live updates never race the config
     * that camera moves or layer switches publish.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun restartReactiveObserver() {
        reactiveRefreshJob?.cancel()
        if (_state.value.activeLayerIds.none { it in LIVE_REACTIVE_LAYER_IDS }) {
            reactiveRefreshJob = null
            return
        }
        reactiveRefreshJob = viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                trackerController.isServiceRunningFlow,
                trackerController.pathPointsFlow,
            ) { running, path ->
                // A monotonically-changing token only while tracking: the live fix count. -1 when
                // not tracking (filtered out below) so we do zero reactive work off-session.
                if (running) path?.second?.size ?: 0 else -1
            }
                .distinctUntilChanged()
                .filter { it >= 0 }
                .debounce(REACTIVE_REFRESH_DEBOUNCE_MS)
                .conflate()
                .collect {
                    // Live data changed but the viewport did not: force a cache-bypassing refresh and
                    // suppress the loading indicator so live updates don't flash a spinner.
                    refreshLayerDataInPlace(forceReload = true, showLoading = false)
                }
        }
    }

    private fun refreshLayerDataInPlace(forceReload: Boolean = false, showLoading: Boolean = true) {
        val engine = layerManager ?: return
        applyLayerJob?.cancel()
        applyLayerJob = viewModelScope.launch {
            if (showLoading) _state.update { it.copy(layerLoadingProgress = 50) }
            try {
                val s = _state.value
                val bounds = lastVisibleBounds
                    ?: cameraToBounds(s.camera.lat, s.camera.lng, s.camera.zoom.toDouble())
                withContext(dispatchers.default) {
                    engine.refreshLayersInPlace(bounds, s.camera.zoom, s.dateRange, forceReload)
                }
                updateLayerStateFromEngine(engine, clearLayerOnMissingLegend = false)
                // Record the viewport bucket now rendered so subsequent micro-pans within it are
                // skipped (see MapEvent.CameraMoved).
                lastRenderedViewportBucket = viewportBucket(bounds, s.camera.zoom)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (showLoading) _state.update { it.copy(layerLoadingProgress = 0) }
            }
        }
    }

    private fun updateLayerStateFromEngine(
        engine: LayerEngine,
        clearLayerOnMissingLegend: Boolean,
    ) {
        val legend = engine.activeLegend()
        val config = engine.activeLayerConfig()
        val overlays = engine.overlays()
        val legendLabel = _state.value.activeLayerIds.joinToString(", ").ifBlank { "Unknown" }

        if (legend != null) {
            _state.update { state ->
                val mergedOverlays = mergeOverlays(state.overlays, overlays)
                state.copy(
                    legend = persistentListOf(*legend.legend.valueList.map { v ->
                        LegendItem(
                            label = legendLabel,
                            color = v.color,
                            labelRes = v.nameRes,
                        )
                    }.toTypedArray()),
                    layerConfig = config,
                    overlays = mergedOverlays,
                    layerLoadingProgress = 0
                )
            }
        } else {
            _state.update { state ->
                state.copy(
                    legend = if (clearLayerOnMissingLegend) persistentListOf() else state.legend,
                    layerConfig = if (clearLayerOnMissingLegend) config else config ?: state.layerConfig,
                    overlays = mergeOverlays(state.overlays, overlays),
                    layerLoadingProgress = 0
                )
            }
        }
    }

    private fun mergeOverlays(
        existingOverlays: List<MapOverlayState>,
        engineOverlays: List<MapOverlayState>
    ) = persistentListOf(
        *(existingOverlays.filter {
            it is MapOverlayState.UserMarker ||
                it is MapOverlayState.AccuracyCircle ||
                it is MapOverlayState.SearchMarker
        } + engineOverlays).toTypedArray()
    )

    private fun parseCoordinateQuery(query: String): LatLngModel? {
        val normalizedQuery = query.trim()
        return parseDelimitedCoordinateQuery(normalizedQuery)
            ?: parseHemisphereCoordinateQuery(normalizedQuery)
            ?: parseWhitespaceDecimalCoordinateQuery(normalizedQuery)
    }

    private fun parseDelimitedCoordinateQuery(query: String): LatLngModel? {
        val parts = query
            .split(coordinatePartDelimiterRegex)
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (parts.size != 2) return null

        return buildCoordinatePair(
            parseCoordinatePart(parts[0], expectedAxis = CoordinateAxis.Latitude),
            parseCoordinatePart(parts[1], expectedAxis = CoordinateAxis.Longitude)
        )
    }

    private fun parseHemisphereCoordinateQuery(query: String): LatLngModel? {
        val parts = coordinateWithHemisphereRegex
            .findAll(query)
            .map { it.value.trim() }
            .toList()
        if (parts.size != 2) return null

        return buildCoordinatePair(
            parseCoordinatePart(parts[0], expectedAxis = null),
            parseCoordinatePart(parts[1], expectedAxis = null)
        )
    }

    private fun parseWhitespaceDecimalCoordinateQuery(query: String): LatLngModel? {
        if (hemisphereRegex.containsMatchIn(query)) return null

        val values = coordinateNumberRegex
            .findAll(query)
            .mapNotNull { it.value.toDoubleOrNull() }
            .toList()
        if (values.size != 2) return null

        return buildCoordinatePair(
            ParsedCoordinateComponent(CoordinateAxis.Latitude, values[0]),
            ParsedCoordinateComponent(CoordinateAxis.Longitude, values[1])
        )
    }

    private fun isBoundsSensitiveLayer(layerId: String): Boolean = layerId in setOf(
        "location_heatmap",
        "cell_heatmap",
        "wifi_heatmap",
        "wifi_count_heatmap",
        "speed_heatmap",
    )

    /**
     * Coarse viewport identity: bounds quantized to ~10% of their own span plus the integer zoom.
     * Two camera positions that fall in the same bucket cover essentially the same data window, so
     * the heatmap rendered for one already covers the other. Returns `null` when bounds are unknown
     * (callers then always refresh). Mirrors the bucketing [com.adsamcik.tracker.map.ui.LayerController]
     * uses for its config cache, so a skipped refresh would have hit that cache anyway.
     */
    private fun viewportBucket(bounds: Bounds?, zoom: Float): ViewportBucket? {
        if (bounds == null) return null
        val latBucket = ((bounds.north - bounds.south) * BUCKET_FRACTION).coerceAtLeast(MIN_BUCKET_DEGREES)
        val lonBucket = ((bounds.east - bounds.west) * BUCKET_FRACTION).coerceAtLeast(MIN_BUCKET_DEGREES)
        return ViewportBucket(
            north = floor(bounds.north / latBucket).toLong(),
            east = floor(bounds.east / lonBucket).toLong(),
            south = floor(bounds.south / latBucket).toLong(),
            west = floor(bounds.west / lonBucket).toLong(),
            zoomInt = zoom.toInt(),
        )
    }

    private data class ViewportBucket(
        val north: Long,
        val east: Long,
        val south: Long,
        val west: Long,
        val zoomInt: Int,
    )

    private fun readTripContext(handle: SavedStateHandle): SelectedTripMapContext? {
        val tripId = handle.get<Long>(TRIP_ID_KEY) ?: return null
        val startMs = handle.get<Long>(TRIP_START_MS_KEY) ?: return null
        val endMs = handle.get<Long>(TRIP_END_MS_KEY) ?: return null
        if (tripId <= 0L || startMs < 0L || endMs < startMs) return null
        return SelectedTripMapContext(
            tripId = tripId,
            startMs = startMs,
            endMs = endMs,
        )
    }

    private fun parseCoordinatePart(
        part: String,
        expectedAxis: CoordinateAxis?
    ): ParsedCoordinateComponent? {
        val hemispheres = hemisphereRegex.findAll(part).map { it.value.uppercase()[0] }.toList()
        if (hemispheres.size > 1) return null

        val hemisphere = hemispheres.singleOrNull()
        val axis = when (hemisphere) {
            'N', 'S' -> CoordinateAxis.Latitude
            'E', 'W' -> CoordinateAxis.Longitude
            null -> expectedAxis ?: return null
            else -> return null
        }
        if (expectedAxis != null && axis != expectedAxis) return null

        val numericValues = coordinateNumberRegex
            .findAll(part)
            .mapNotNull { it.value.toDoubleOrNull() }
            .toList()
        if (numericValues.isEmpty() || numericValues.size > 3) return null

        val sign = when (hemisphere) {
            'S', 'W' -> -1
            'N', 'E' -> 1
            null -> if (numericValues.first() < 0) -1 else 1
            else -> return null
        }

        val absoluteValue = when (numericValues.size) {
            1 -> abs(numericValues[0])
            2, 3 -> {
                val degrees = abs(numericValues[0])
                val minutes = abs(numericValues[1])
                val seconds = numericValues.getOrElse(2) { 0.0 }.let(::abs)
                if (minutes >= 60.0 || seconds >= 60.0) return null
                degrees + (minutes / 60.0) + (seconds / 3600.0)
            }
            else -> return null
        }

        return ParsedCoordinateComponent(axis = axis, value = sign * absoluteValue)
    }

    private fun buildCoordinatePair(
        first: ParsedCoordinateComponent?,
        second: ParsedCoordinateComponent?
    ): LatLngModel? {
        if (first == null || second == null) return null

        val latitude = when {
            first.axis == CoordinateAxis.Latitude -> first.value
            second.axis == CoordinateAxis.Latitude -> second.value
            else -> return null
        }
        val longitude = when {
            first.axis == CoordinateAxis.Longitude -> first.value
            second.axis == CoordinateAxis.Longitude -> second.value
            else -> return null
        }
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null

        return LatLngModel(lat = latitude, lng = longitude)
    }

    override fun onCleared() {
        super.onCleared()
        applyLayerJob?.cancel()
        cameraRefreshJob?.cancel()
        reactiveRefreshJob?.cancel()
        overlayUpdateJob?.cancel()
        try {
            layerManager?.destroy()
        } catch (e: Exception) {
            Reporter.report(e)
        }
    }
}
