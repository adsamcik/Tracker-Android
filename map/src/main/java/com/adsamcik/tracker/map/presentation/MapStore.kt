package com.adsamcik.tracker.map.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.map.presentation.bridge.LayerEngine
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.adsamcik.tracker.map.presentation.udf.MapState
import com.adsamcik.tracker.map.presentation.udf.MapEffect
import com.adsamcik.tracker.map.presentation.udf.MapOverlayState
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.google.android.gms.maps.model.TileProvider
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * MapStore (Phase 1/2): holds central MapState, reduces MapEvent, and bridges to legacy LayerController.
 * Tracks quality/dateRange and legend, ready for camera/follow/search expansion.
 */
class MapStore(
    private val layerManager: LayerEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(MapState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<MapEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    // Single-active-layer semantics maintained for now; tracked in state
    private var selectedLayerId: String? = null
    private var lastBearing: Float = 0f

    fun dispatch(event: MapEvent) {
        when (event) {
            is MapEvent.SelectLayer -> {
                selectedLayerId = event.id
                _state.update { it.copy(activeLayerIds = persistentSetOf(event.id)) }
                applyLayer()
                // Future: if the selected layer provides default bounds, emit effect here
                // layerManager.defaultBoundsFor(event.id)?.let { _effects.tryEmit(MapEffect.CenterCamera(it)) }
            }
            is MapEvent.ToggleFollow -> {
                val nowFollowing = !_state.value.isFollowing
                _state.update { it.copy(isFollowing = nowFollowing) }
                if (nowFollowing) {
                    // If we know the user location, center camera around it
                    val overlays = _state.value.overlays
                    val user = overlays.firstOrNull { it is MapOverlayState.UserMarker } as? MapOverlayState.UserMarker
                    user?.let {
                        val p = com.google.android.gms.maps.model.LatLng(it.latLng.lat, it.latLng.lng)
                        val delta = 0.0007 // ~78m latitude delta; enough to create non-zero bounds
                        val builder = com.google.android.gms.maps.model.LatLngBounds.Builder()
                            .include(com.google.android.gms.maps.model.LatLng(p.latitude - delta, p.longitude - delta))
                            .include(com.google.android.gms.maps.model.LatLng(p.latitude + delta, p.longitude + delta))
                        _effects.tryEmit(MapEffect.CenterCamera(builder.build()))
                    }
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
            is MapEvent.CameraMoved -> {
                _state.update { it.copy(camera = event.position) }
            }
            is MapEvent.SetUserLocation -> {
                _state.update { st ->
                    // Keep all non-user overlays; replace any existing user overlays with the new ones
                    val others = st.overlays.filterNot {
                        it is MapOverlayState.UserMarker || it is MapOverlayState.AccuracyCircle
                    }
                    val user = listOf(
                        MapOverlayState.UserMarker(event.latLng, bearing = if (st.isFollowing) lastBearing else null),
                        MapOverlayState.AccuracyCircle(event.latLng, event.accuracyM)
                    )
                    st.copy(overlays = persistentListOf(*(others + user).toTypedArray()))
                }
                // If following is enabled, center camera around the updated user location
                if (_state.value.isFollowing) {
                    val p = com.google.android.gms.maps.model.LatLng(event.latLng.lat, event.latLng.lng)
                    val delta = 0.0007
                    val builder = com.google.android.gms.maps.model.LatLngBounds.Builder()
                        .include(com.google.android.gms.maps.model.LatLng(p.latitude - delta, p.longitude - delta))
                        .include(com.google.android.gms.maps.model.LatLng(p.latitude + delta, p.longitude + delta))
                    _effects.tryEmit(MapEffect.CenterCamera(builder.build()))
                    // Also forward latest bearing when following
                    _effects.tryEmit(MapEffect.SetCameraBearing(lastBearing))
                }
            }
            is MapEvent.SetBearing -> {
                lastBearing = ((event.bearing % 360f) + 360f) % 360f
                val st = _state.value
                if (st.isFollowing) {
                    _effects.tryEmit(MapEffect.SetCameraBearing(lastBearing))
                }
                // Update user marker rotation in overlays
                _state.update { cur ->
                    val updated = cur.overlays.map { ov ->
                        if (ov is MapOverlayState.UserMarker) ov.copy(bearing = if (cur.isFollowing) lastBearing else null) else ov
                    }
                    cur.copy(overlays = persistentListOf(*updated.toTypedArray()))
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

    private fun applyLayer() {
        viewModelScope.launch {
            val s = _state.value
            layerManager.selectSingleLayer(selectedLayerId, s.quality, s.dateRange)
            // Update legend if available in bridge
            val legend = layerManager.activeLegend()
            val provider = layerManager.activeTileProvider()
        val overlays = layerManager.overlays()
            if (legend != null) {
                _state.update {
                    it.copy(
                        legend = persistentListOf(*legend.legend.valueList.map { v ->
                            com.adsamcik.tracker.map.presentation.udf.LegendItem(
                                label = "", // name is a @StringRes; keep empty for now in Compose sheet
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
        }
    }
}
