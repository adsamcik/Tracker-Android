package com.adsamcik.tracker.app.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.settings.data.TrackingPresetSettings
import com.adsamcik.tracker.shared.base.extension.hasCellScanPermission
import com.adsamcik.tracker.shared.base.extension.hasPreciseLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasWifiScanPermission
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Consolidated UI state for the tracking settings screen. */
data class TrackingSettingsUiState(
    val isLoaded: Boolean = false,
    val currentPreset: TrackingPreset = TrackingPreset.DEFAULT,
    val currentBatteryImpact: BatteryImpact = BatteryImpact.MODERATE,
    val locationEnabled: Boolean = true,
    val activityEnabled: Boolean = true,
    val stepsEnabled: Boolean = true,
    val wifiEnabled: Boolean = true,
    val cellEnabled: Boolean = false,
    val wifiNetworkEnabled: Boolean = false,
    val wifiLocationCountEnabled: Boolean = false,
    val wifiPermissionGranted: Boolean = false,
    val cellPermissionGranted: Boolean = false,
    val autoTrackingEnabled: Boolean = false,
    val transitionDetectionEnabled: Boolean = true,
    val notificationStyled: Boolean = true,
    val minDistance: Int = 10,
    val minTime: Int = 2,
    val requiredAccuracy: Int = 50,
    val hasValidSources: Boolean = true,
    val skiDetectionEnabled: Boolean = false,
)

@HiltViewModel
class TrackingSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackingParamsRepository: TrackingParamsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrackingSettingsUiState())
    val uiState: StateFlow<TrackingSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            trackingParamsRepository.data.collect { params ->
                val preset = params.preset
                val wifiPermissionGranted = context.hasWifiScanPermission
                val cellPermissionGranted = context.hasCellScanPermission
                val effectiveWifiEnabled = params.wifiEnabled && wifiPermissionGranted
                val effectiveCellEnabled = params.cellEnabled && cellPermissionGranted

                _uiState.value = _uiState.value.copy(
                    isLoaded = true,
                    currentPreset = preset,
                    locationEnabled = params.locationEnabled,
                    activityEnabled = params.activityEnabled,
                    stepsEnabled = params.stepsEnabled,
                    wifiEnabled = effectiveWifiEnabled,
                    cellEnabled = effectiveCellEnabled,
                    wifiNetworkEnabled = params.wifiNetworkEnabled && wifiPermissionGranted,
                    wifiLocationCountEnabled = params.wifiLocationCountEnabled && wifiPermissionGranted,
                    wifiPermissionGranted = wifiPermissionGranted,
                    cellPermissionGranted = cellPermissionGranted,
                    autoTrackingEnabled = params.autoTrackingMode > 0,
                    transitionDetectionEnabled = params.transitionDetectionEnabled,
                    notificationStyled = params.notificationStyled,
                    minDistance = params.minDistanceMeters,
                    minTime = params.minTimeSeconds,
                    requiredAccuracy = params.requiredAccuracyMeters,
                    hasValidSources = params.locationEnabled || params.activityEnabled ||
                            params.stepsEnabled || effectiveWifiEnabled || effectiveCellEnabled,
                    skiDetectionEnabled = params.skiDetectionEnabled,
                )
                recalculateBatteryImpact()
            }
        }
    }

    fun setLocationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            trackingParamsRepository.setLocationEnabled(enabled)
            markCustomPreset()
        }
    }

    fun setActivityEnabled(enabled: Boolean) {
        viewModelScope.launch {
            trackingParamsRepository.setActivityEnabled(enabled)
            markCustomPreset()
        }
    }

    fun setStepsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            trackingParamsRepository.setStepsEnabled(enabled)
            markCustomPreset()
        }
    }

    fun setWifiEnabled(enabled: Boolean) {
        viewModelScope.launch {
            trackingParamsRepository.setWifiEnabled(enabled && context.hasWifiScanPermission)
            markCustomPreset()
        }
    }

    fun setCellEnabled(enabled: Boolean) {
        viewModelScope.launch {
            trackingParamsRepository.setCellEnabled(enabled && context.hasCellScanPermission)
            markCustomPreset()
        }
    }

    fun setWifiNetworkEnabled(enabled: Boolean) {
        viewModelScope.launch {
            trackingParamsRepository.setWifiNetworkEnabled(enabled && context.hasWifiScanPermission)
            markCustomPreset()
        }
    }

    fun setWifiLocationCountEnabled(enabled: Boolean) {
        viewModelScope.launch {
            trackingParamsRepository.setWifiLocationCountEnabled(enabled && context.hasWifiScanPermission)
            markCustomPreset()
        }
    }

    fun onWifiPermissionResult(granted: Boolean) {
        _uiState.value = _uiState.value.copy(wifiPermissionGranted = granted)
        if (granted) {
            setWifiEnabled(true)
        }
    }

    fun onCellPermissionResult(granted: Boolean) {
        _uiState.value = _uiState.value.copy(cellPermissionGranted = granted)
        if (granted) {
            setCellEnabled(true)
        }
    }

    fun setTransitionDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            trackingParamsRepository.setTransitionDetectionEnabled(enabled)
        }
    }

    fun setNotificationStyled(enabled: Boolean) {
        viewModelScope.launch {
            trackingParamsRepository.setNotificationStyled(enabled)
        }
    }

    fun setSkiDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            trackingParamsRepository.setSkiDetectionEnabled(enabled)
        }
    }

    fun setMinDistance(distance: Int) {
        viewModelScope.launch {
            trackingParamsRepository.setMinDistanceMeters(distance)
            markCustomPreset()
        }
    }

    fun setMinTime(time: Int) {
        viewModelScope.launch {
            trackingParamsRepository.setMinTimeSeconds(time)
            markCustomPreset()
        }
    }

    fun setRequiredAccuracy(accuracy: Int) {
        viewModelScope.launch {
            trackingParamsRepository.setRequiredAccuracyMeters(accuracy)
            markCustomPreset()
        }
    }

    fun applyPreset(preset: TrackingPreset) {
        viewModelScope.launch {
            val config = preset
            val wifiAllowed = context.hasWifiScanPermission
            val cellAllowed = context.hasCellScanPermission
            trackingParamsRepository.update {
                copy(
                    locationEnabled = config.locationEnabled,
                    activityEnabled = config.activityEnabled,
                    stepsEnabled = config.stepsEnabled,
                    wifiEnabled = config.wifiEnabled && wifiAllowed,
                    wifiNetworkEnabled = config.wifiEnabled && wifiAllowed,
                    wifiLocationCountEnabled = preset == TrackingPreset.HIGH_ACCURACY && wifiAllowed,
                    cellEnabled = config.cellEnabled && cellAllowed,
                    transitionDetectionEnabled = transitionDetectionEnabled,
                    minDistanceMeters = config.minDistanceMeters,
                    minTimeSeconds = config.minTimeSeconds,
                    requiredAccuracyMeters = config.requiredAccuracyMeters,
                    presetName = preset.name,
                )
            }
        }
    }

    private suspend fun markCustomPreset() {
        if (_uiState.value.currentPreset != TrackingPreset.CUSTOM) {
            trackingParamsRepository.setPreset(TrackingPreset.CUSTOM)
        }
    }

    private fun recalculateBatteryImpact() {
        val state = _uiState.value
        val currentSettings = TrackingPresetSettings(
            locationEnabled = state.locationEnabled,
            requirePreciseLocation = context.hasPreciseLocationPermission,
            minDistanceMeters = state.minDistance,
            minTimeSeconds = state.minTime,
            requiredAccuracyMeters = state.requiredAccuracy,
            activityEnabled = state.activityEnabled,
            stepsEnabled = state.stepsEnabled,
            wifiEnabled = state.wifiEnabled,
            wifiLocationCountEnabled = state.wifiLocationCountEnabled,
            cellEnabled = state.cellEnabled,
            useTransitionDetection = state.transitionDetectionEnabled
        )
        _uiState.value = state.copy(currentBatteryImpact = currentSettings.calculateBatteryImpact())
    }
}
