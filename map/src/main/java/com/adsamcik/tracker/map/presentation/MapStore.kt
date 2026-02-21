package com.adsamcik.tracker.map.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.map.presentation.bridge.LayerEngine
import com.adsamcik.tracker.map.presentation.udf.LegendItem
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.adsamcik.tracker.map.presentation.udf.MapState
import com.adsamcik.tracker.map.presentation.udf.MapEffect
import com.adsamcik.tracker.map.presentation.udf.MapOverlayState
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.map.presentation.udf.SheetVisibility
import com.adsamcik.tracker.shared.map.CoordinateBounds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * MapStore: holds central MapState, reduces MapEvent, and bridges to LayerEngine.
 * Zero Google Maps dependencies -- uses CoordinateBounds and MapLibreLayerConfig.
 */
@HiltViewModel
class MapStore @Inject constructor() : ViewModel() {

    private var layerManager: LayerEngine? = null

    fun setLayerEngine(engine: LayerEngine) {
        layerManager = engine
        if (selectedLayerId != null) {
            applyLayer()
        }
    }

    val isEngineReady: Boolean
        get() = layerManager != null

    private val _state = MutableStateFlow(MapState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<MapEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    private var selectedLayerId: String? = null
    private var lastBearing: Float = 0f

    private var applyLayerJob: Job? = null
    private var overlayUpdateJob: Job? = null
    private var lastLocationUpdate: Long = 0L
    private val locationUpdateDebounceMs = 100L

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
                selectedLayerId = event.id
                _state.update { it.copy(activeLayerIds = persistentSetOf(event.id)) }
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
                    val overlays = _state.value.overlays
                    val user = overlays.firstOrNull { it is MapOverlayState.UserMarker } as? MapOverlayState.UserMarker
                    user?.let {
                        val delta = 0.001
                        val bounds = CoordinateBounds(
                            topBound = it.latLng.lat + delta,
                            rightBound = it.latLng.lng + delta,
                            bottomBound = it.latLng.lat - delta,
                            leftBound = it.latLng.lng - delta
                        )
                        _effects.tryEmit(MapEffect.CenterCamera(bounds))
                    }
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
                _state.update { it.copy(dateRange = event.range) }
                applyLayer()
            }
            is MapEvent.UpdateSearchQuery -> {
                _state.update { it.copy(search = it.search.copy(query = event.query)) }
            }
            is MapEvent.SetSearchFocus -> {
                _state.update { it.copy(search = it.search.copy(hasFocus = event.hasFocus)) }
            }
            is MapEvent.SubmitSearch -> {
                val q = _state.value.search.query.trim()
                if (q.isNotEmpty()) {
                    _effects.tryEmit(MapEffect.PerformGeocode(q))
                }
            }
            is MapEvent.GeocodeResult -> {
                val b = event.bounds
                if (b != null) {
                    _effects.tryEmit(MapEffect.CenterCamera(b))
                }
            }
            is MapEvent.CameraMoved -> {
                _state.update { it.copy(camera = event.position) }
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
        if (_state.value.isFollowing) {
            val delta = kotlin.math.max(0.001, accuracyM / 111000.0)
            val bounds = CoordinateBounds(
                topBound = latLng.lat + delta,
                rightBound = latLng.lng + delta,
                bottomBound = latLng.lat - delta,
                leftBound = latLng.lng - delta
            )
            _effects.tryEmit(MapEffect.CenterCamera(bounds))
            _effects.tryEmit(MapEffect.SetCameraBearing(lastBearing))
        }
    }

    private fun applyLayer() {
        val engine = layerManager ?: return
        applyLayerJob?.cancel()
        applyLayerJob = viewModelScope.launch {
            _state.update { it.copy(layerLoadingProgress = 50) }
            try {
                val s = _state.value
                withContext(Dispatchers.Default) {
                    engine.selectSingleLayer(selectedLayerId, s.quality, s.dateRange)
                }
                val legend = engine.activeLegend()
                val config = engine.activeLayerConfig()
                val overlays = engine.overlays()

                if (legend != null) {
                    _state.update { state ->
                        state.copy(
                            legend = persistentListOf(*legend.legend.valueList.map { v ->
                                LegendItem(
                                    label = selectedLayerId ?: "Unknown",
                                    color = v.color
                                )
                            }.toTypedArray()),
                            layerConfig = config,
                            overlays = overlays,
                            layerLoadingProgress = 0
                        )
                    }
                } else {
                    _state.update { it.copy(legend = persistentListOf(), layerConfig = config, overlays = overlays, layerLoadingProgress = 0) }
                }
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        legend = persistentListOf(),
                        layerConfig = null,
                        overlays = persistentListOf(),
                        layerLoadingProgress = 0
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        applyLayerJob?.cancel()
        overlayUpdateJob?.cancel()
        try {
            layerManager?.destroy()
        } catch (_: Exception) {
            // Ignore cleanup errors
        }
    }
}
