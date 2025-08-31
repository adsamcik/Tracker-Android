package com.adsamcik.tracker.map.presentation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import java.time.Duration

/**
 * Lightweight MapViewModel used for tests in Phase 6.
 * Holds immutable MapState in a StateFlow and emits debounced refreshes
 * when parameters change.
 */
class MapViewModel(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val refreshDebounce: Duration = Duration.ofMillis(150),
) : ViewModel() {

    data class MapState(
        val selectedLayerId: String? = null,
        val quality: Float = 1f,
        val refreshVersion: Int = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(MapState())
    val state: StateFlow<MapState> = _state.asStateFlow()

    private val refreshSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        scope.launch {
            // Debounce refresh requests coming from parameter changes
            refreshSignals.collectLatest {
                delay(refreshDebounce.toMillis())
                _state.update { it.copy(refreshVersion = it.refreshVersion + 1) }
            }
        }
    }

    fun selectLayer(id: String?) {
        _state.update { it.copy(selectedLayerId = id) }
        requestRefresh()
    }

    fun setQuality(value: Float) {
        _state.update { it.copy(quality = value) }
        requestRefresh()
    }

    fun requestRefresh() {
        refreshSignals.tryEmit(Unit)
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }

    // no-op helpers
}

