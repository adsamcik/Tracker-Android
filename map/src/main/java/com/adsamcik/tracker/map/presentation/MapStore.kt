package com.adsamcik.tracker.map.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.map.presentation.bridge.LayerManager
import com.adsamcik.tracker.map.presentation.udf.MapEvent
import com.adsamcik.tracker.map.presentation.udf.MapState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Phase 1 MapStore: minimal reducer and bridge calls. */
class MapStore(
    private val layerManager: LayerManager,
) : ViewModel() {

    private val _state = MutableStateFlow(MapState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    // Simplified: only supports single active layer for Phase 1
    private var selectedLayerId: String? = null
    private var quality: Float = 1f
    private var dateRange: LongRange = 0L..Long.MAX_VALUE

    fun dispatch(event: MapEvent) {
        when (event) {
            is MapEvent.SelectLayer -> {
                selectedLayerId = event.id
                applyLayer()
                _state.update { it.copy() }
            }
            is MapEvent.ToggleFollow -> {
                _state.update { it.copy(isFollowing = !it.isFollowing) }
            }
        }
    }

    fun setQuality(value: Float) {
        quality = value
        applyLayer()
    }

    fun setDateRange(range: LongRange) {
        dateRange = range
        applyLayer()
    }

    private fun applyLayer() {
        viewModelScope.launch {
            layerManager.selectSingleLayer(selectedLayerId, quality, dateRange)
        }
    }
}
