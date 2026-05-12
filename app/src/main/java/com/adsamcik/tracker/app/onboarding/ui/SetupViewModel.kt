package com.adsamcik.tracker.app.onboarding.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.app.onboarding.data.SetupStep
import com.adsamcik.tracker.app.onboarding.data.SetupUiState
import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionMode
import com.adsamcik.tracker.app.settings.data.TrackingPolicyPreset
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.maintenance.DataRetentionScheduler
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.extension.hasCellScanPermission
import com.adsamcik.tracker.shared.base.extension.hasWifiScanPermission
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.onboarding.OnboardingRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.tracker.service.ActivityWatcherServiceController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.adsamcik.tracker.activity.R as ActivityR
import com.adsamcik.tracker.shared.preferences.R as PrefR
import com.adsamcik.tracker.tracker.R as TrackerR

/**
 * ViewModel for the first-time setup wizard.
 *
 * Manages step navigation, user selections, and applies all preferences
 * atomically when onboarding completes.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val dispatchers: DispatchersProvider,
    private val onboardingRepository: OnboardingRepository,
    private val activityWatcherController: ActivityWatcherServiceController,
    private val dataRetentionScheduler: DataRetentionScheduler,
    private val trackingParamsRepository: TrackingParamsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    // region Step navigation

    fun goToNextStep() {
        _state.update { current ->
            val nextIndex = current.currentStep.index + 1
            val next = SetupStep.fromIndex(nextIndex) ?: return@update current
            current.copy(currentStep = next)
        }
    }

    fun goToPreviousStep() {
        _state.update { current ->
            val prevIndex = current.currentStep.index - 1
            val prev = SetupStep.fromIndex(prevIndex) ?: return@update current
            current.copy(currentStep = prev)
        }
    }

    // endregion

    // region Step 2 – How to Track

    fun setAutoTrackingMode(mode: Int) {
        _state.update { it.copy(autoTrackingMode = mode.coerceIn(0, 2)) }
    }

    fun setTrackingPreset(preset: TrackingPolicyPreset) {
        _state.update { it.copy(trackingPreset = preset) }
    }

    // endregion

    // region Step 3 – What to Collect

    fun setLocationEnabled(enabled: Boolean) {
        _state.update { it.copy(locationEnabled = enabled) }
    }

    fun setLocationPrecision(mode: LocationPrecisionMode) {
        _state.update { it.copy(locationPrecision = mode) }
    }

    fun setActivityEnabled(enabled: Boolean) {
        _state.update { it.copy(activityEnabled = enabled) }
    }

    fun setStepsEnabled(enabled: Boolean) {
        _state.update { it.copy(stepsEnabled = enabled) }
    }

    fun setWifiEnabled(enabled: Boolean) {
        _state.update { it.copy(wifiEnabled = enabled) }
    }

    fun setCellEnabled(enabled: Boolean) {
        _state.update { it.copy(cellEnabled = enabled) }
    }

    // endregion

    // region Permissions

    fun onLocationPermissionResult(granted: Boolean) {
        _state.update { it.copy(locationPermissionGranted = granted) }
    }

    fun onBackgroundLocationResult(granted: Boolean) {
        _state.update { it.copy(backgroundLocationGranted = granted) }
    }

    fun onActivityPermissionResult(granted: Boolean) {
        _state.update { it.copy(activityPermissionGranted = granted) }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        _state.update { it.copy(notificationPermissionGranted = granted) }
    }

    fun onWifiPermissionResult(granted: Boolean) {
        _state.update { it.copy(wifiPermissionGranted = granted) }
    }

    fun onCellPermissionResult(granted: Boolean) {
        _state.update { it.copy(cellPermissionGranted = granted) }
    }

    // endregion

    // region Completion

    /**
     * Persist all user selections and mark onboarding completed.
     * Call this when the user taps the final "Continue" / "Start Exploring" button.
     *
     * @param onDone called on the main thread after preferences are written.
     */
    fun completeSetup(onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                val s = _state.value
                withContext(dispatchers.io) {
                    applyPreferences(s)
                    onboardingRepository.markCompleted()
                }
                dataRetentionScheduler.initialize()
                onDone()
            } catch (e: Exception) {
                Reporter.report(e)
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun applyPreferences(s: SetupUiState) {
        val preferences = Preferences(appContext)
        val preset = s.trackingPreset.settings
        val wifiAllowed = s.wifiEnabled && appContext.hasWifiScanPermission
        val cellAllowed = s.cellEnabled && appContext.hasCellScanPermission
        val effectiveMode = if (s.activityPermissionGranted) s.autoTrackingMode else 0

        preferences.edit {
            // Location precision
            setString(
                appContext.getString(PrefR.string.settings_location_precision_key),
                s.locationPrecision.name,
            )

            // Activity watcher service flag is still a legacy preference bridge.
            setBoolean(
                appContext.getString(ActivityR.string.settings_activity_watcher_key),
                s.autoTrackingMode > 0,
            )
        }

        trackingParamsRepository.update {
            copy(
                locationEnabled = s.locationEnabled,
                activityEnabled = s.activityEnabled,
                stepsEnabled = s.stepsEnabled,
                wifiEnabled = wifiAllowed,
                wifiNetworkEnabled = preset.wifiEnabled && wifiAllowed,
                wifiLocationCountEnabled = preset.wifiLocationCountEnabled && wifiAllowed,
                cellEnabled = cellAllowed,
                autoTrackingMode = effectiveMode,
                transitionDetectionEnabled = preset.useTransitionDetection,
                notificationStyled = true,
                minDistanceMeters = preset.minDistanceMeters,
                minTimeSeconds = preset.minTimeSeconds,
                requiredAccuracyMeters = preset.requiredAccuracyMeters,
                presetName = s.trackingPreset.toTrackingPreset().name,
            )
        }

        // Poke activity watcher so it picks up the new settings immediately
        activityWatcherController.poke(autoTracking = effectiveMode)
        if (effectiveMode > 0) {
            activityWatcherController.poke()
        }
    }

    // endregion

    private fun TrackingPolicyPreset.toTrackingPreset(): TrackingPreset = when (this) {
        TrackingPolicyPreset.BATTERY_SAVER -> TrackingPreset.POWER_SAVE
        TrackingPolicyPreset.BALANCED -> TrackingPreset.BALANCED
        TrackingPolicyPreset.HIGH_PRECISION -> TrackingPreset.HIGH_ACCURACY
    }
}
