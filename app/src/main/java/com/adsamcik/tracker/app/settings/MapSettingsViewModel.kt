package com.adsamcik.tracker.app.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.activity.ski.SkiInfrastructureManager
import com.adsamcik.tracker.map.basemap.BasemapManager
import com.adsamcik.tracker.shared.preferences.Preferences
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
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val prefs = Preferences.getPref(context)
    val basemapManager = BasemapManager(context)
    val skiInfrastructureManager = SkiInfrastructureManager(context)

    // Resolved preference keys
    private val qualityKey = context.getString(com.adsamcik.tracker.map.R.string.settings_map_quality_key)
    private val qualityDefault = context.getString(com.adsamcik.tracker.map.R.string.settings_map_quality_default).toFloat()
    private val heatKey = context.getString(com.adsamcik.tracker.map.R.string.settings_map_max_heat_key)
    private val heatDefault = context.getString(com.adsamcik.tracker.map.R.string.settings_map_max_heat_default).toInt()
    private val visitKey = context.getString(com.adsamcik.tracker.map.R.string.settings_map_visit_threshold_key)
    private val visitDefault = context.getString(com.adsamcik.tracker.map.R.string.settings_map_visit_threshold_default).toInt()
    private val basemapPathKey = context.getString(com.adsamcik.tracker.map.R.string.settings_map_basemap_path_key)

    // Basemap state
    private val _basemapPath = MutableStateFlow(basemapManager.customBasemapPath())
    val basemapPath: StateFlow<String?> = _basemapPath.asStateFlow()

    // Ski infrastructure state
    private val _skiInfraLoaded = MutableStateFlow(skiInfrastructureManager.isAvailable())
    val skiInfraLoaded: StateFlow<Boolean> = _skiInfraLoaded.asStateFlow()

    // Map quality
    private val _quality = MutableStateFlow(qualityDefault)
    val quality: StateFlow<Float> = _quality.asStateFlow()

    // Max heat points
    private val _maxHeat = MutableStateFlow(heatDefault)
    val maxHeat: StateFlow<Int> = _maxHeat.asStateFlow()

    // Visit threshold
    private val _visitThreshold = MutableStateFlow(visitDefault)
    val visitThreshold: StateFlow<Int> = _visitThreshold.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.observeFloat(qualityKey, qualityDefault).collect { _quality.value = it }
        }
        viewModelScope.launch {
            prefs.observeInt(heatKey, heatDefault).collect { _maxHeat.value = it }
        }
        viewModelScope.launch {
            prefs.observeInt(visitKey, visitDefault).collect { _visitThreshold.value = it }
        }
    }

    fun importBasemap(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = basemapManager.importBasemap(uri)
            prefs.edit { setString(basemapPathKey, path) }
            _basemapPath.value = path
        }
    }

    fun clearBasemap() {
        basemapManager.clearCustomBasemap()
        prefs.edit { setString(basemapPathKey, "") }
        _basemapPath.value = null
    }

    fun importSkiInfrastructure(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                skiInfrastructureManager.importDatabase(uri)
                _skiInfraLoaded.value = true
            } catch (_: Exception) {
                // Import failed — already cleaned up by manager
            }
        }
    }

    fun clearSkiInfrastructure() {
        skiInfrastructureManager.clearDatabase()
        _skiInfraLoaded.value = false
    }

    fun setQuality(value: Float) {
        viewModelScope.launch {
            prefs.edit { setFloat(qualityKey, value) }
            _quality.value = value
        }
    }

    fun setMaxHeat(value: Int) {
        viewModelScope.launch {
            prefs.edit { setInt(heatKey, value) }
            _maxHeat.value = value
        }
    }

    fun setVisitThreshold(value: Int) {
        viewModelScope.launch {
            prefs.edit { setInt(visitKey, value) }
            _visitThreshold.value = value
        }
    }
}
