package com.adsamcik.tracker.map.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.map.data.cameraToBounds
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.map.online.TileProvider
import com.adsamcik.tracker.map.presentation.bridge.LayerEngine
import com.adsamcik.tracker.map.presentation.udf.LegendItem
import com.adsamcik.tracker.map.presentation.udf.CameraModel
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.adsamcik.tracker.map.presentation.udf.MapState
import com.adsamcik.tracker.map.presentation.udf.MapEffect
import com.adsamcik.tracker.map.presentation.udf.MapOverlayState
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.map.presentation.udf.SearchResultStatus
import com.adsamcik.tracker.map.presentation.udf.SelectedTripMapContext
import com.adsamcik.tracker.map.presentation.udf.SheetVisibility
import com.adsamcik.tracker.map.shared.CoordinateBounds
import com.adsamcik.tracker.network.NetworkGateway
import com.adsamcik.tracker.network.NetworkPolicy
import com.adsamcik.tracker.shared.preferences.map.OnlineMapTilesRepository
import com.adsamcik.tracker.shared.preferences.map.OnlineMapTilesState
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs

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
    private val networkGateway: NetworkGateway,
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
        private const val CAMERA_LAT_KEY = "camera_lat"
        private const val CAMERA_LNG_KEY = "camera_lng"
        private const val CAMERA_ZOOM_KEY = "camera_zoom"
        private const val CAMERA_TILT_KEY = "camera_tilt"
        private const val CAMERA_BEARING_KEY = "camera_bearing"
        private const val TRIP_ID_KEY = "tripId"
        private const val TRIP_START_MS_KEY = "startMs"
        private const val TRIP_END_MS_KEY = "endMs"
        /**
         * Per-host rate limit (requests / minute) applied to online tile providers.
         * Tile loads are bursty — a single viewport pan can request 20-50 vector
         * tiles in a few seconds plus sprite + glyph fetches. The 600/min
         * ([NetworkPolicy.DEFAULT_RATE_LIMIT]) per-host default is too tight for
         * that; 3600/min (60/s sustained) covers a vigorous pan/zoom without ever
         * spuriously rate-limiting the user, while still gating a runaway loop.
         */
        private const val TILE_HOST_RATE_LIMIT_PER_MIN: Int = 3600
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
     */
    val onlineMapTiles: StateFlow<OnlineMapTilesState> =
        onlineMapTilesRepository.data
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = OnlineMapTilesState(),
            )

    init {
        // Mirror the user's online-tile preference into the NetworkGateway. MapLibre's
        // OkHttp client is registered against the gateway at app startup
        // (Application.onCreate → MapLibreInitializer.setHttpCallFactory), so flipping
        // the gateway's kill switch here immediately gates every subsequent MapLibre
        // tile/style/sprite/glyph request through the gateway's interceptor chain
        // (kill switch → allowlist → rate limit).
        viewModelScope.launch {
            onlineMapTilesRepository.data.collect { prefs ->
                applyNetworkPolicy(prefs)
            }
        }
    }

    private fun applyNetworkPolicy(prefs: OnlineMapTilesState) {
        if (prefs.enabled) {
            val provider = TileProvider.resolve(prefs.providerId, prefs.customUrl)
            networkGateway.setPolicy(
                NetworkPolicy(
                    allowedHosts = provider.allowedHosts,
                    perHostRateLimit = TILE_HOST_RATE_LIMIT_PER_MIN,
                    perHostRateWindowMs = NetworkPolicy.DEFAULT_RATE_WINDOW_MS,
                ),
            )
            networkGateway.setEnabled(true)
        } else {
            networkGateway.setEnabled(false)
            networkGateway.setPolicy(NetworkPolicy.EMPTY)
        }
    }

    private var lastBearing: Float = 0f

    private var applyLayerJob: Job? = null
    private var cameraRefreshJob: Job? = null
    private var overlayUpdateJob: Job? = null
    private var lastLocationUpdate: Long = 0L
    private val locationUpdateDebounceMs = 100L
    private val cameraRefreshDebounceMs = 500L
    private var hasReceivedInitialLocation: Boolean = false
    private var lastKnownUserLocation: LatLngModel? = null
    private var lastKnownAccuracyM: Double = 0.0

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
                _state.update { current ->
                    val updatedLayerIds = if (event.id == NONE_LAYER_ID) {
                        persistentSetOf()
                    } else {
                        persistentSetOf(event.id)
                    }
                    current.copy(activeLayerIds = updatedLayerIds)
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
                    emitCenterOnUser()
                    _effects.tryEmit(MapEffect.SetCameraBearing(lastBearing))
                }
            }
            is MapEvent.FollowCanceled -> {
                _state.update { it.copy(isFollowing = false) }
                _effects.tryEmit(MapEffect.ShowFollowCanceled)
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
                if (coordinate == null) {
                    _state.update { it.copy(search = it.search.copy(resultStatus = SearchResultStatus.NotFound)) }
                    _effects.tryEmit(MapEffect.ShowSearchFormatHint)
                    return
                }
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
            is MapEvent.CameraMoved -> {
                _state.update { it.copy(camera = event.position) }
                savedStateHandle[CAMERA_LAT_KEY] = event.position.lat
                savedStateHandle[CAMERA_LNG_KEY] = event.position.lng
                savedStateHandle[CAMERA_ZOOM_KEY] = event.position.zoom
                savedStateHandle[CAMERA_TILT_KEY] = event.position.tilt
                savedStateHandle[CAMERA_BEARING_KEY] = event.position.bearing
                // Debounced viewport refresh so heatmaps update data without rebuilding layers.
                if (_state.value.activeLayerIds.any(::isBoundsSensitiveLayer)) {
                    cameraRefreshJob?.cancel()
                    cameraRefreshJob = viewModelScope.launch {
                        delay(cameraRefreshDebounceMs)
                        refreshLayerDataInPlace()
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
        lastKnownAccuracyM = accuracyM

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
            emitCenterOnUser()
            if (_state.value.isFollowing) {
                _effects.tryEmit(MapEffect.SetCameraBearing(lastBearing))
            }
        }
    }

    private fun emitCenterOnUser() {
        val location = lastKnownUserLocation ?: return
        val delta = kotlin.math.max(0.001, lastKnownAccuracyM / 111000.0)
        _effects.tryEmit(
            MapEffect.CenterCamera(
                CoordinateBounds(
                    topBound = location.lat + delta,
                    rightBound = location.lng + delta,
                    bottomBound = location.lat - delta,
                    leftBound = location.lng - delta,
                )
            )
        )
    }

    private fun applyLayer() {
        val engine = layerManager ?: return
        cameraRefreshJob?.cancel()
        applyLayerJob?.cancel()
        applyLayerJob = viewModelScope.launch {
            _state.update { it.copy(layerLoadingProgress = 50) }
            try {
                val s = _state.value
                val bounds = cameraToBounds(s.camera.lat, s.camera.lng, s.camera.zoom.toDouble())
                withContext(dispatchers.default) {
                    engine.selectLayers(s.activeLayerIds, s.quality, s.dateRange, bounds, s.camera.zoom)
                }
                updateLayerStateFromEngine(engine, clearLayerOnMissingLegend = true)
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

    private fun refreshLayerDataInPlace() {
        val engine = layerManager ?: return
        applyLayerJob?.cancel()
        applyLayerJob = viewModelScope.launch {
            _state.update { it.copy(layerLoadingProgress = 50) }
            try {
                val s = _state.value
                val bounds = cameraToBounds(s.camera.lat, s.camera.lng, s.camera.zoom.toDouble())
                withContext(dispatchers.default) {
                    engine.refreshLayersInPlace(bounds, s.camera.zoom, s.dateRange)
                }
                updateLayerStateFromEngine(engine, clearLayerOnMissingLegend = false)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(layerLoadingProgress = 0) }
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
        overlayUpdateJob?.cancel()
        try {
            layerManager?.destroy()
        } catch (e: Exception) {
            Reporter.report(e)
        }
    }
}
