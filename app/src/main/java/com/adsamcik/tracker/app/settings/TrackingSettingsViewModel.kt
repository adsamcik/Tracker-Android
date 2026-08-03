package com.adsamcik.tracker.app.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.settings.data.TrackingPresetSettings
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.base.extension.hasCellScanPermission
import com.adsamcik.tracker.shared.base.extension.hasPreciseLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasPressureSensor
import com.adsamcik.tracker.shared.base.extension.hasStepCounterSensor
import com.adsamcik.tracker.shared.base.extension.hasWifiScanPermission
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val barometerEnabled: Boolean = true,
    val barometerAvailable: Boolean = true,
    val activityPermissionGranted: Boolean = false,
    val stepCounterAvailable: Boolean = true,
    val wifiPermissionGranted: Boolean = false,
    val cellPermissionGranted: Boolean = false,
    val autoTrackingEnabled: Boolean = false,
    val transitionDetectionEnabled: Boolean = true,
    val notificationStyled: Boolean = true,
    val minDistance: Int = 10,
    val minTime: Int = 2,
    val requiredAccuracy: Int = 50,
    val hasValidSources: Boolean = true,
    val vehicleSpeedLimitKmh: Int = 50,
)

@HiltViewModel
class TrackingSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackingParamsRepository: TrackingParamsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrackingSettingsUiState())
    val uiState: StateFlow<TrackingSettingsUiState> = _uiState.asStateFlow()
    private var latestParams: TrackingParamsState? = null

    init {
        viewModelScope.launch {
            trackingParamsRepository.data.collect { params ->
                latestParams = params
                updateUiState(params)
            }
        }
    }

    /** Re-evaluates permission- and hardware-gated state after returning to this screen. */
    fun refreshPermissionState() {
        latestParams?.let(::updateUiState)
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
            // Persist the user's intent. Actual scanning is guarded at runtime by the Wi-Fi
            // producer (which re-checks the permission every cycle and nudges the user to grant
            // it), and the UI's effective state still gates display on the permission. Coercing
            // to false here previously discarded the intent if the permission check raced the
            // grant callback, leaving the toggle stuck off.
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

    fun setBarometerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val effectiveEnabled = enabled && context.hasPressureSensor
            trackingParamsRepository.setBarometerEnabled(effectiveEnabled)
            markCustomPreset()
        }
    }

    fun onActivityPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(activityPermissionGranted = granted) }
        if (granted) setActivityEnabled(true)
    }

    fun onStepsPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(activityPermissionGranted = granted) }
        if (granted) setStepsEnabled(true)
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

    fun setVehicleSpeedLimitKmh(kmh: Int) {
        viewModelScope.launch {
            val mps = kmh / KMH_PER_MPS
            trackingParamsRepository.setVehicleSpeedLimitBaselineMps(mps)
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
            trackingParamsRepository.update {
                copy(
                    locationEnabled = config.locationEnabled,
                    activityEnabled = config.activityEnabled,
                    stepsEnabled = config.stepsEnabled,
                    wifiEnabled = config.wifiEnabled,
                    cellEnabled = config.cellEnabled,
                    barometerEnabled = config.barometerEnabled,
                    transitionDetectionEnabled = transitionDetectionEnabled,
                    minDistanceMeters = config.minDistanceMeters,
                    minTimeSeconds = config.minTimeSeconds,
                    requiredAccuracyMeters = config.requiredAccuracyMeters,
                    presetName = preset.name,
                )
            }
        }
    }

    private fun updateUiState(params: TrackingParamsState) {
        val wifiPermissionGranted = context.hasWifiScanPermission
        val cellPermissionGranted = context.hasCellScanPermission
        val barometerAvailable = context.hasPressureSensor
        val activityPermissionGranted = context.hasActivityPermission
        val stepCounterAvailable = context.hasStepCounterSensor
        val effectiveActivityEnabled = params.activityEnabled && activityPermissionGranted
        val effectiveStepsEnabled = params.stepsEnabled &&
            activityPermissionGranted && stepCounterAvailable
        val effectiveWifiEnabled = params.wifiEnabled && wifiPermissionGranted
        val effectiveCellEnabled = params.cellEnabled && cellPermissionGranted
        val effectiveBarometerEnabled = params.barometerEnabled && barometerAvailable

        _uiState.update {
            it.copy(
                isLoaded = true,
                currentPreset = params.preset,
                locationEnabled = params.locationEnabled,
                activityEnabled = effectiveActivityEnabled,
                stepsEnabled = effectiveStepsEnabled,
                wifiEnabled = effectiveWifiEnabled,
                cellEnabled = effectiveCellEnabled,
                barometerEnabled = effectiveBarometerEnabled,
                barometerAvailable = barometerAvailable,
                activityPermissionGranted = activityPermissionGranted,
                stepCounterAvailable = stepCounterAvailable,
                wifiPermissionGranted = wifiPermissionGranted,
                cellPermissionGranted = cellPermissionGranted,
                autoTrackingEnabled = params.autoTrackingMode > 0,
                transitionDetectionEnabled = params.transitionDetectionEnabled,
                notificationStyled = params.notificationStyled,
                minDistance = params.minDistanceMeters,
                minTime = params.minTimeSeconds,
                requiredAccuracy = params.requiredAccuracyMeters,
                hasValidSources = params.hasAnyCaptureSource(
                    activityAvailable = activityPermissionGranted,
                    stepsAvailable = activityPermissionGranted && stepCounterAvailable,
                    wifiAvailable = wifiPermissionGranted,
                    cellAvailable = cellPermissionGranted,
                    barometerAvailable = barometerAvailable,
                ),
                vehicleSpeedLimitKmh = mpsToKmh(params.vehicleSpeedLimitBaselineMps),
            )
        }
        recalculateBatteryImpact()
    }

    private suspend fun markCustomPreset() {
        if (_uiState.value.currentPreset != TrackingPreset.CUSTOM) {
            trackingParamsRepository.setPreset(TrackingPreset.CUSTOM)
        }
    }

    private fun recalculateBatteryImpact() {
        _uiState.update { state ->
            val currentSettings = TrackingPresetSettings(
                locationEnabled = state.locationEnabled,
                requirePreciseLocation = context.hasPreciseLocationPermission,
                minDistanceMeters = state.minDistance,
                minTimeSeconds = state.minTime,
                requiredAccuracyMeters = state.requiredAccuracy,
                activityEnabled = state.activityEnabled,
                stepsEnabled = state.stepsEnabled,
                wifiEnabled = state.wifiEnabled,
                cellEnabled = state.cellEnabled,
                barometerEnabled = state.barometerEnabled,
                useTransitionDetection = state.transitionDetectionEnabled
            )
            state.copy(currentBatteryImpact = currentSettings.calculateBatteryImpact())
        }
    }

    private companion object {
        const val KMH_PER_MPS = 3.6

        fun mpsToKmh(mps: Double): Int = (mps * KMH_PER_MPS).toInt().coerceIn(30, 130)
    }
}
