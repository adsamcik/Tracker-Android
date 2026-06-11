package com.adsamcik.tracker.app.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.activity.ski.SkiInfrastructureImportResult
import com.adsamcik.tracker.activity.ski.SkiInfrastructureManager
import com.adsamcik.tracker.map.basemap.BasemapImportResult
import com.adsamcik.tracker.map.basemap.BasemapManager
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.map.MapPreferenceKeys
import com.adsamcik.tracker.shared.preferences.map.MapSettingsRepository
import com.adsamcik.tracker.shared.preferences.map.MapSettingsState
import com.adsamcik.tracker.shared.preferences.map.OnlineMapTilesRepository
import com.adsamcik.tracker.shared.preferences.map.OnlineMapTilesState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MapSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mapSettingsRepository: MapSettingsRepository,
    private val onlineMapTilesRepository: OnlineMapTilesRepository,
    private val dispatchers: DispatchersProvider,
    private val preferences: Preferences,
    val skiInfrastructureManager: SkiInfrastructureManager,
) : ViewModel() {

    val basemapManager = BasemapManager(context, dispatchers)

    // Basemap path key for legacy preference (basemap path not in proto — it's file-system state)
    private val basemapPathKey = MapPreferenceKeys.BASEMAP_PATH
    private val prefs get() = preferences

    // Basemap state (file-system, not in proto)
    private val _basemapPath = MutableStateFlow(basemapManager.customBasemapPath())
    val basemapPath: StateFlow<String?> = _basemapPath.asStateFlow()

    // One-shot import errors surfaced to the UI as toasts.
    private val _basemapImportError = MutableSharedFlow<BasemapImportFailure>(extraBufferCapacity = 1)
    val basemapImportError: SharedFlow<BasemapImportFailure> = _basemapImportError.asSharedFlow()

    // Ski infrastructure state (file-system, not in proto)
    private val _skiInfraLoaded = MutableStateFlow(skiInfrastructureManager.isAvailable())
    val skiInfraLoaded: StateFlow<Boolean> = _skiInfraLoaded.asStateFlow()

    // Map quality
    private val _quality = MutableStateFlow(MapSettingsState.DEFAULT_QUALITY)
    val quality: StateFlow<Float> = _quality.asStateFlow()

    // Max heat points
    private val _maxHeat = MutableStateFlow(MapSettingsState.DEFAULT_MAX_HEAT)
    val maxHeat: StateFlow<Int> = _maxHeat.asStateFlow()

    // Visit threshold
    private val _visitThreshold = MutableStateFlow(MapSettingsState.DEFAULT_VISIT_THRESHOLD)
    val visitThreshold: StateFlow<Int> = _visitThreshold.asStateFlow()

    // Online map tiles state
    private val _onlineTiles = MutableStateFlow(OnlineMapTilesState())
    val onlineTiles: StateFlow<OnlineMapTilesState> = _onlineTiles.asStateFlow()

    init {
        viewModelScope.launch {
            mapSettingsRepository.data.collect { state ->
                _quality.value = state.quality
                _maxHeat.value = state.maxHeatPoints
                _visitThreshold.value = state.visitThresholdSeconds
            }
        }
        viewModelScope.launch {
            onlineMapTilesRepository.data.collect { state ->
                _onlineTiles.value = state
            }
        }
    }

    fun importBasemap(uri: Uri) {
        viewModelScope.launch(dispatchers.io) {
            when (val result = basemapManager.importBasemap(uri)) {
                is BasemapImportResult.Success -> {
                    prefs.edit { setString(basemapPathKey, result.path) }
                    _basemapPath.value = result.path
                }
                is BasemapImportResult.SourceOpenFailed ->
                    _basemapImportError.tryEmit(BasemapImportFailure.SourceOpenFailed)
                is BasemapImportResult.CopyFailed ->
                    _basemapImportError.tryEmit(BasemapImportFailure.CopyFailed)
                is BasemapImportResult.InvalidFormat ->
                    _basemapImportError.tryEmit(BasemapImportFailure.InvalidFormat(result.reason))
            }
        }
    }

    fun clearBasemap() {
        basemapManager.clearCustomBasemap()
        prefs.edit { setString(basemapPathKey, "") }
        _basemapPath.value = null
    }

    fun importSkiInfrastructure(uri: Uri) {
        viewModelScope.launch(dispatchers.io) {
            when (skiInfrastructureManager.importDatabase(uri)) {
                is SkiInfrastructureImportResult.Success -> {
                    _skiInfraLoaded.value = true
                }
                is SkiInfrastructureImportResult.SourceOpenFailed,
                is SkiInfrastructureImportResult.CopyFailed,
                is SkiInfrastructureImportResult.InvalidDatabase -> {
                    // Import failed — already cleaned up by manager
                }
            }
        }
    }

    fun clearSkiInfrastructure() {
        skiInfrastructureManager.clearDatabase()
        _skiInfraLoaded.value = false
    }

    fun setQuality(value: Float) {
        viewModelScope.launch {
            mapSettingsRepository.setQuality(value)
        }
    }

    fun setMaxHeat(value: Int) {
        viewModelScope.launch {
            mapSettingsRepository.setMaxHeatPoints(value)
        }
    }

    fun setVisitThreshold(value: Int) {
        viewModelScope.launch {
            mapSettingsRepository.setVisitThresholdSeconds(value)
        }
    }

    fun setOnlineTilesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            onlineMapTilesRepository.setEnabled(enabled)
        }
    }

    fun setOnlineProviderId(providerId: String) {
        viewModelScope.launch {
            onlineMapTilesRepository.setProviderId(providerId)
        }
    }

    fun setOnlineCustomUrl(url: String) {
        viewModelScope.launch {
            onlineMapTilesRepository.setCustomUrl(url)
        }
    }
}
