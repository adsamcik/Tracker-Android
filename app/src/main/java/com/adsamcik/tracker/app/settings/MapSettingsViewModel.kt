package com.adsamcik.tracker.app.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.activity.ski.SkiInfrastructureImportResult
import com.adsamcik.tracker.activity.ski.SkiInfrastructureManager
import com.adsamcik.tracker.map.basemap.BasemapImportResult
import com.adsamcik.tracker.map.basemap.BasemapManager
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.map.MapSettingsRepository
import com.adsamcik.tracker.shared.preferences.map.MapSettingsState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MapSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mapSettingsRepository: MapSettingsRepository,
) : ViewModel() {

    val basemapManager = BasemapManager(context)
    val skiInfrastructureManager = SkiInfrastructureManager(context)

    // Basemap path key for legacy preference (basemap path not in proto — it's file-system state)
    private val basemapPathKey = context.getString(com.adsamcik.tracker.map.R.string.settings_map_basemap_path_key)
    private val prefs = Preferences.getPref(context)

    // Basemap state (file-system, not in proto)
    private val _basemapPath = MutableStateFlow(basemapManager.customBasemapPath())
    val basemapPath: StateFlow<String?> = _basemapPath.asStateFlow()

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

    init {
        viewModelScope.launch {
            mapSettingsRepository.data.collect { state ->
                _quality.value = state.quality
                _maxHeat.value = state.maxHeatPoints
                _visitThreshold.value = state.visitThresholdSeconds
            }
        }
    }

    fun importBasemap(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = basemapManager.importBasemap(uri)) {
                is BasemapImportResult.Success -> {
                    prefs.edit { setString(basemapPathKey, result.path) }
                    _basemapPath.value = result.path
                }
                is BasemapImportResult.SourceOpenFailed,
                is BasemapImportResult.CopyFailed -> Unit
            }
        }
    }

    fun clearBasemap() {
        basemapManager.clearCustomBasemap()
        prefs.edit { setString(basemapPathKey, "") }
        _basemapPath.value = null
    }

    fun importSkiInfrastructure(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
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
}
