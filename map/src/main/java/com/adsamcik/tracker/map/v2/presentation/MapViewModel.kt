package com.adsamcik.tracker.map.v2.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Immutable UI state for the v2 map architecture scaffold (task 2.1). */
data class MapState(
    val selectedLayerId: String? = null,
    val dateRange: LongRange = LongRange.EMPTY,
    val quality: Float = 1f,
    val followMode: FollowMode = FollowMode.NONE,
    val userLocation: GeoPoint? = null,
    val searchQuery: String = "",
    val searchResults: List<SearchResult> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val bottomSheetState: BottomSheetState = BottomSheetState.COLLAPSED,
    val layerParameters: Map<String, LayerParamValue> = emptyMap(),
    val tileGenerationProgress: TileGenerationProgress = TileGenerationProgress.Empty,
    val pendingRefresh: Boolean = false
)

/** Simple geographic point placeholder (avoid bringing in Maps LatLng for pure state). */
data class GeoPoint(val lat: Double, val lon: Double)

enum class FollowMode { NONE, LOCATION, BEARING }

enum class BottomSheetState { HIDDEN, COLLAPSED, HALF_EXPANDED, EXPANDED }

/** Represents progress of tile generation (count of currently generating tiles). */
data class TileGenerationProgress(val generating: Int) {
    companion object { val Empty = TileGenerationProgress(0) }
}

/** Minimal search result placeholder. */
data class SearchResult(val id: String, val title: String, val subtitle: String? = null)

/** Parameter value sealed hierarchy for future strong typing. */
sealed interface LayerParamValue {
    @JvmInline value class Num(val value: Double): LayerParamValue
    @JvmInline value class Bool(val value: Boolean): LayerParamValue
    @JvmInline value class Text(val value: String): LayerParamValue
}

/**
 * MapViewModel exposes a cold StateFlow<MapState> and provides a debounced refresh
 * mechanism for expensive operations (layer rebuild / data reload) when parameters change.
 */
@OptIn(FlowPreview::class)
class MapViewModel(
    private val refreshDebounce: Duration = 300.milliseconds
) : ViewModel() {

    private val _state = MutableStateFlow(MapState())
    val state: StateFlow<MapState> = _state.asStateFlow()

    // Emits when a refresh should be performed (after debounce)
    private val refreshRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Public flow clients can collect to know a debounced refresh should run. */
    private val _refreshSignals = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val refreshSignals = _refreshSignals

    // Debounced refresh collection will be started lazily when first scheduled to avoid Main dispatcher in plain unit tests.
    private var refreshCollectorStarted = false

    // region State mutation helpers
    fun setSelectedLayer(id: String?) = update { it.copy(selectedLayerId = id) }
    fun setDateRange(range: LongRange) = updateAndRefresh { it.copy(dateRange = range) }
    fun setQuality(quality: Float) = updateAndRefresh { it.copy(quality = quality) }
    fun setFollowMode(mode: FollowMode) = update { it.copy(followMode = mode) }
    fun setUserLocation(point: GeoPoint?) = update { it.copy(userLocation = point) }
    fun setSearchQuery(query: String) = update { it.copy(searchQuery = query) }
    fun setSearchResults(results: List<SearchResult>) = update { it.copy(searchResults = results) }
    fun setLoading(loading: Boolean) = update { it.copy(isLoading = loading) }
    fun setError(err: String?) = update { it.copy(error = err) }
    fun setBottomSheetState(bs: BottomSheetState) = update { it.copy(bottomSheetState = bs) }
    fun setLayerParameters(params: Map<String, LayerParamValue>) = updateAndRefresh { it.copy(layerParameters = params) }
    fun setTileGenerationProgress(generating: Int) = update { it.copy(tileGenerationProgress = TileGenerationProgress(generating)) }
    // endregion

    /** Schedules a debounced refresh (marks pendingRefresh immediately). */
    private fun scheduleRefresh() {
        if (!refreshCollectorStarted) {
            refreshCollectorStarted = true
            // Use a lightweight thread without requiring Android Main
            kotlinx.coroutines.GlobalScope.launch {
                refreshRequests
                    .debounce(refreshDebounce)
                    .collect {
                        _state.value = _state.value.copy(pendingRefresh = false)
                        _refreshSignals.emit(Unit)
                    }
            }
        }
        if (!_state.value.pendingRefresh) {
            _state.value = _state.value.copy(pendingRefresh = true)
        }
        refreshRequests.tryEmit(Unit)
    }

    private inline fun update(block: (MapState) -> MapState) {
        _state.value = block(_state.value)
    }

    private inline fun updateAndRefresh(block: (MapState) -> MapState) {
        update(block)
        scheduleRefresh()
    }
}
