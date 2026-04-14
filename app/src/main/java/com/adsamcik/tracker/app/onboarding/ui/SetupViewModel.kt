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
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.onboarding.OnboardingRepository
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
    private fun applyPreferences(s: SetupUiState) {
        val preferences = Preferences(appContext)
        val preset = s.trackingPreset.settings

        preferences.edit {
            // Location precision
            setString(
                appContext.getString(PrefR.string.settings_location_precision_key),
                s.locationPrecision.name,
            )

            // Data source toggles
            setBoolean(PreferenceKeys.LOCATION_ENABLED, s.locationEnabled)
            setBoolean(PreferenceKeys.ACTIVITY_ENABLED, s.activityEnabled)
            setBoolean(PreferenceKeys.STEPS_ENABLED, s.stepsEnabled)
            setBoolean(PreferenceKeys.WIFI_ENABLED, s.wifiEnabled)
            setBoolean(PreferenceKeys.WIFI_LOCATION_COUNT_ENABLED, preset.wifiLocationCountEnabled)
            setBoolean(PreferenceKeys.CELL_ENABLED, s.cellEnabled)

            // Tracking preset parameters
            setInt(PreferenceKeys.TRACKING_MIN_DISTANCE, preset.minDistanceMeters)
            setInt(PreferenceKeys.TRACKING_MIN_TIME, preset.minTimeSeconds)
            setInt(PreferenceKeys.TRACKING_REQUIRED_ACCURACY, preset.requiredAccuracyMeters)

            // Auto-tracking
            setBoolean(PreferenceKeys.AUTO_TRACKING_TRANSITION_ENABLED, preset.useTransitionDetection)
            setBoolean(
                appContext.getString(ActivityR.string.settings_activity_watcher_key),
                s.autoTrackingMode > 0,
            )

            // Notification styling on
            setBoolean(PreferenceKeys.NOTIFICATION_STYLED, true)

            // Auto-tracking mode
            val effectiveMode = if (s.activityPermissionGranted) s.autoTrackingMode else 0
            setInt(PreferenceKeys.TRACKING_ACTIVITY_MODE, effectiveMode)
        }

        // Poke activity watcher so it picks up the new settings immediately
        val effectiveMode = if (s.activityPermissionGranted) s.autoTrackingMode else 0
        activityWatcherController.poke(autoTracking = effectiveMode)
        if (effectiveMode > 0) {
            activityWatcherController.poke()
        }
    }

    // endregion
}
