package com.adsamcik.tracker.app.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.settings.data.TrackingPolicyPreset
import com.adsamcik.tracker.app.settings.data.TrackingPresetSettings
import com.adsamcik.tracker.shared.base.extension.hasPreciseLocationPermission
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Consolidated UI state for the tracking settings screen. */
data class TrackingSettingsUiState(
    val currentPreset: TrackingPolicyPreset? = TrackingPolicyPreset.DEFAULT,
    val currentBatteryImpact: BatteryImpact = BatteryImpact.MODERATE,
    val locationEnabled: Boolean = true,
    val activityEnabled: Boolean = true,
    val stepsEnabled: Boolean = true,
    val wifiEnabled: Boolean = true,
    val cellEnabled: Boolean = false,
    val wifiNetworkEnabled: Boolean = false,
    val wifiLocationCountEnabled: Boolean = false,
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
                val preset = TrackingPolicyPreset.values()
                    .firstOrNull { it.name == params.presetName }
                    .takeIf { params.presetName != "CUSTOM" }

                _uiState.value = _uiState.value.copy(
                    currentPreset = preset,
                    locationEnabled = params.locationEnabled,
                    activityEnabled = params.activityEnabled,
                    stepsEnabled = params.stepsEnabled,
                    wifiEnabled = params.wifiEnabled,
                    cellEnabled = params.cellEnabled,
                    wifiNetworkEnabled = params.wifiNetworkEnabled,
                    wifiLocationCountEnabled = params.wifiLocationCountEnabled,
                    autoTrackingEnabled = params.autoTrackingMode > 0,
                    transitionDetectionEnabled = params.transitionDetectionEnabled,
                    notificationStyled = params.notificationStyled,
                    minDistance = params.minDistanceMeters,
                    minTime = params.minTimeSeconds,
                    requiredAccuracy = params.requiredAccuracyMeters,
                    hasValidSources = params.locationEnabled || params.activityEnabled ||
                            params.stepsEnabled || params.wifiEnabled || params.cellEnabled,
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
            trackingParamsRepository.setWifiEnabled(enabled)
            markCustomPreset()
        }
    }

    fun setCellEnabled(enabled: Boolean) {
        viewModelScope.launch {
            trackingParamsRepository.setCellEnabled(enabled)
            markCustomPreset()
        }
    }

    fun setWifiNetworkEnabled(enabled: Boolean) {
        viewModelScope.launch {
            trackingParamsRepository.setWifiNetworkEnabled(enabled)
            markCustomPreset()
        }
    }

    fun setWifiLocationCountEnabled(enabled: Boolean) {
        viewModelScope.launch {
            trackingParamsRepository.setWifiLocationCountEnabled(enabled)
            markCustomPreset()
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

    fun applyPreset(preset: TrackingPolicyPreset) {
        viewModelScope.launch {
            val config = preset.settings
            trackingParamsRepository.update {
                copy(
                    locationEnabled = config.locationEnabled,
                    activityEnabled = config.activityEnabled,
                    stepsEnabled = config.stepsEnabled,
                    wifiEnabled = config.wifiEnabled,
                    wifiNetworkEnabled = config.wifiEnabled,
                    wifiLocationCountEnabled = config.wifiLocationCountEnabled,
                    cellEnabled = config.cellEnabled,
                    transitionDetectionEnabled = config.useTransitionDetection,
                    minDistanceMeters = config.minDistanceMeters,
                    minTimeSeconds = config.minTimeSeconds,
                    requiredAccuracyMeters = config.requiredAccuracyMeters,
                    presetName = preset.name,
                )
            }
        }
    }

    private suspend fun markCustomPreset() {
        if (_uiState.value.currentPreset != null) {
            trackingParamsRepository.setPresetName("CUSTOM")
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
