package com.adsamcik.tracker.map.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.map.presentation.bridge.LayerEngine
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.adsamcik.tracker.map.presentation.udf.MapState
import com.adsamcik.tracker.map.presentation.udf.MapEffect
import com.adsamcik.tracker.map.presentation.udf.MapOverlayState
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.map.presentation.udf.SheetVisibility
import com.google.android.gms.maps.model.TileProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

/**
 * MapStore (Phase 1/2): holds central MapState, reduces MapEvent, and bridges to legacy LayerController.
 * Tracks quality/dateRange and legend, ready for camera/follow/search expansion.
 * 
 * Phase 2 Improvements:
 * - Added debouncing for frequent updates
 * - Performance optimizations for overlay updates
 * - Better memory management
 * - LayerEngine can be set after construction to support async GoogleMap initialization
 */
@HiltViewModel
class MapStore @Inject constructor() : ViewModel() {

    private var layerManager: LayerEngine? = null
    
    /** 
     * Updates the layer engine after GoogleMap becomes available.
     * This allows the store to be created before the map is ready.
     */
    fun setLayerEngine(engine: LayerEngine) {
        layerManager = engine
        // Re-apply the current layer selection with the new engine
        if (selectedLayerId != null) {
            applyLayer()
        }
    }
    
    /** Returns true if the layer engine has been set. */
    val isEngineReady: Boolean
        get() = layerManager != null

    private val _state = MutableStateFlow(MapState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<MapEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    // Single-active-layer semantics maintained for now; tracked in state
    private var selectedLayerId: String? = null
    private var lastBearing: Float = 0f
    
    // Performance: Debounce frequent overlay updates
    private var overlayUpdateJob: Job? = null
    private var lastLocationUpdate: Long = 0L
    private val LOCATION_UPDATE_DEBOUNCE_MS = 100L

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
                // Future: if the selected layer provides default bounds, emit effect here
                // layerManager.defaultBoundsFor(event.id)?.let { _effects.tryEmit(MapEffect.CenterCamera(it)) }
            }
            is MapEvent.ToggleFollow -> {
                dispatch(MapEvent.SetFollowing(!_state.value.isFollowing))
            }
            is MapEvent.SetFollowing -> {
                val nowFollowing = event.isFollowing
                _state.update { it.copy(isFollowing = nowFollowing) }
                if (nowFollowing) {
                    // When starting to follow, immediately orient the user marker to last known bearing
                    _state.update { cur ->
                        val updatedOverlays = cur.overlays.map { ov ->
                            if (ov is MapOverlayState.UserMarker) ov.copy(bearing = lastBearing) else ov
                        }
                        cur.copy(overlays = persistentListOf(*updatedOverlays.toTypedArray()))
                    }

                    // If we know the user location, center camera around it
                    val overlays = _state.value.overlays
                    val user = overlays.firstOrNull { it is MapOverlayState.UserMarker } as? MapOverlayState.UserMarker
                    user?.let {
                        val p = com.google.android.gms.maps.model.LatLng(it.latLng.lat, it.latLng.lng)
                        // Use a more reasonable delta based on zoom level or accuracy
                        val delta = 0.001 // ~111m latitude delta; reasonable for user location
                        val sw = com.google.android.gms.maps.model.LatLng(p.latitude - delta, p.longitude - delta)
                        val ne = com.google.android.gms.maps.model.LatLng(p.latitude + delta, p.longitude + delta)
                        val bounds = com.google.android.gms.maps.model.LatLngBounds(sw, ne)
                        _effects.tryEmit(MapEffect.CenterCamera(bounds))
                    }
                    // Also propagate current bearing to camera immediately
                    _effects.tryEmit(MapEffect.SetCameraBearing(lastBearing))
                }
            }
            is MapEvent.FollowCanceled -> {
                // Turn off following and notify UI to show a cue
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
                // Performance: Debounce frequent location updates
                val now = System.currentTimeMillis()
                if (now - lastLocationUpdate < LOCATION_UPDATE_DEBOUNCE_MS) {
                    // Cancel previous job and schedule new one
                    overlayUpdateJob?.cancel()
                    overlayUpdateJob = viewModelScope.launch {
                        delay(LOCATION_UPDATE_DEBOUNCE_MS)
                        updateUserLocation(event.latLng, event.accuracyM)
                    }
                } else {
                    lastLocationUpdate = now
                    updateUserLocation(event.latLng, event.accuracyM)
                }
            }
            is MapEvent.SetBearing -> {
                val normalizedBearing = ((event.bearing % 360f) + 360f) % 360f
                
                // Only update if bearing changed significantly (reduce unnecessary updates)
                if (kotlin.math.abs(normalizedBearing - lastBearing) > 1f) {
                    lastBearing = normalizedBearing
                    
                    val st = _state.value
                    if (st.isFollowing) {
                        _effects.tryEmit(MapEffect.SetCameraBearing(lastBearing))
                    }
                    
                    // Performance: Only update user marker bearing, not all overlays
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
            // Performance: Pre-filter overlays to avoid recreating the entire list
            val userOverlayIndices = mutableListOf<Int>()
            st.overlays.forEachIndexed { index, overlay ->
                if (overlay is MapOverlayState.UserMarker || overlay is MapOverlayState.AccuracyCircle) {
                    userOverlayIndices.add(index)
                }
            }
            
            // Remove old user overlays and add new ones
            val filteredOverlays = st.overlays.filterIndexed { index, _ -> 
                index !in userOverlayIndices 
            }
            
            val newUserOverlays = listOf(
                MapOverlayState.UserMarker(latLng, bearing = if (st.isFollowing) lastBearing else null),
                MapOverlayState.AccuracyCircle(latLng, accuracyM)
            )
            
            st.copy(overlays = persistentListOf(*(filteredOverlays + newUserOverlays).toTypedArray()))
        }
        
        // Handle camera following
        if (_state.value.isFollowing) {
            val p = com.google.android.gms.maps.model.LatLng(latLng.lat, latLng.lng)
            val delta = kotlin.math.max(0.001, accuracyM / 111000.0) // Convert accuracy to degrees
            val sw = com.google.android.gms.maps.model.LatLng(p.latitude - delta, p.longitude - delta)
            val ne = com.google.android.gms.maps.model.LatLng(p.latitude + delta, p.longitude + delta)
            val bounds = com.google.android.gms.maps.model.LatLngBounds(sw, ne)
            _effects.tryEmit(MapEffect.CenterCamera(bounds))
            // Also forward latest bearing when following
            _effects.tryEmit(MapEffect.SetCameraBearing(lastBearing))
        }
    }

    private fun applyLayer() {
        val engine = layerManager ?: return // No-op if engine not yet set
        
        // Launch on ViewModel scope (Main), do heavy work on Default via withContext, then update state on Main
        viewModelScope.launch {
            try {
                val s = _state.value
                // Heavy selection off the main thread
                kotlinx.coroutines.withContext(Dispatchers.Default) {
                    engine.selectSingleLayer(selectedLayerId, s.quality, s.dateRange)
                }

                // Update legend and overlays (still on Main)
                val legend = engine.activeLegend()
                val provider = engine.activeTileProvider()
                val overlays = engine.overlays()

                if (legend != null) {
                    _state.update { state ->
                        state.copy(
                            legend = persistentListOf(*legend.legend.valueList.map { v ->
                                com.adsamcik.tracker.map.presentation.udf.LegendItem(
                                    label = try {
                                        // We need context to resolve string resources
                                        // For now, use layer ID as fallback
                                        selectedLayerId ?: "Unknown"
                                    } catch (e: Exception) {
                                        "Legend Item"
                                    },
                                    color = v.color
                                )
                            }.toTypedArray()),
                            tileProvider = provider,
                            overlays = overlays
                        )
                    }
                } else {
                    _state.update { it.copy(legend = persistentListOf(), tileProvider = provider, overlays = overlays) }
                }

                // Wire tile generation progress if provider supports it
                val opt = provider as? com.adsamcik.tracker.map.tiles.OptimizedTileProvider
                opt?.tileRequestCountListener = { count ->
                    _state.update { st -> st.copy(tileGenerationInProgress = count.coerceAtLeast(0)) }
                }
            } catch (e: Exception) {
                // Handle layer application errors gracefully on Main
                _state.update { it.copy(
                    legend = persistentListOf(),
                    tileProvider = null,
                    overlays = persistentListOf(),
                    tileGenerationInProgress = 0
                )}
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        // Cancel any pending overlay updates
        overlayUpdateJob?.cancel()
        // Destroy the layer manager (also cancels internal coroutine scopes)
        try {
            layerManager?.destroy()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }
}
