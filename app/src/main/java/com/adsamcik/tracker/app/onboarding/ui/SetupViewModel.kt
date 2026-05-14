package com.adsamcik.tracker.app.onboarding.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.onboarding.data.AutoTrackingMode
import com.adsamcik.tracker.app.onboarding.data.SetupPermissionState
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
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    init {
        replaceState(restoreState())
    }

    // region Step navigation

    fun goToNextStep() {
        updateState { current ->
            val nextIndex = current.currentStep.index + 1
            val next = SetupStep.fromIndex(nextIndex) ?: return@updateState current
            current.copy(currentStep = next)
        }
    }

    fun goToPreviousStep() {
        updateState { current ->
            val prevIndex = current.currentStep.index - 1
            val prev = SetupStep.fromIndex(prevIndex) ?: return@updateState current
            current.copy(currentStep = prev)
        }
    }

    // endregion

    // region Step 2 – How to Track

    fun setAutoTrackingMode(mode: AutoTrackingMode) {
        updateState { it.copy(autoTrackingMode = mode) }
    }

    fun setTrackingPreset(preset: TrackingPolicyPreset) {
        updateState { it.copy(trackingPreset = preset) }
    }

    // endregion

    // region Step 3 – What to Collect

    fun setLocationEnabled(enabled: Boolean) {
        updateState { it.copy(locationEnabled = enabled) }
    }

    fun setLocationPrecision(mode: LocationPrecisionMode) {
        updateState { it.copy(locationPrecision = mode) }
    }

    fun setActivityEnabled(enabled: Boolean) {
        updateState { it.copy(activityEnabled = enabled) }
    }

    fun setStepsEnabled(enabled: Boolean) {
        updateState { it.copy(stepsEnabled = enabled) }
    }

    fun setWifiEnabled(enabled: Boolean) {
        updateState { it.copy(wifiEnabled = enabled) }
    }

    fun setCellEnabled(enabled: Boolean) {
        updateState { it.copy(cellEnabled = enabled) }
    }

    // endregion

    // region Permissions

    fun onPermissionStateHydrated(permissionState: SetupPermissionState) {
        updateState { current ->
            current.copy(
                locationPermissionGranted = permissionState.foregroundLocationGranted,
                backgroundLocationGranted = permissionState.backgroundLocationGranted,
                activityPermissionGranted = permissionState.activityRecognitionGranted,
                notificationPermissionGranted = permissionState.notificationGranted,
                locationPrecision = when {
                    permissionState.foregroundLocationGranted && !permissionState.fineLocationGranted ->
                        LocationPrecisionMode.APPROXIMATE

                    else -> current.locationPrecision
                },
                locationPermissionDenied = if (permissionState.foregroundLocationGranted) {
                    false
                } else {
                    current.locationPermissionDenied
                },
                locationPermissionPermanentlyDenied = permissionState.locationPermanentlyDenied,
                backgroundLocationPermissionDenied = if (permissionState.backgroundLocationGranted) {
                    false
                } else {
                    current.backgroundLocationPermissionDenied
                },
                backgroundLocationPermissionPermanentlyDenied =
                    permissionState.backgroundLocationPermanentlyDenied,
                activityPermissionDenied = if (permissionState.activityRecognitionGranted) {
                    false
                } else {
                    current.activityPermissionDenied
                },
                activityPermissionPermanentlyDenied =
                    permissionState.activityRecognitionPermanentlyDenied,
            )
        }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        onLocationPermissionResult(
            fineGranted = granted,
            coarseGranted = granted,
            permanentlyDenied = false,
        )
    }

    fun onLocationPermissionResult(
        fineGranted: Boolean,
        coarseGranted: Boolean,
        permanentlyDenied: Boolean,
    ) {
        val foregroundGranted = fineGranted || coarseGranted
        updateState { current ->
            current.copy(
                locationPermissionGranted = foregroundGranted,
                locationEnabled = if (foregroundGranted) current.locationEnabled else false,
                locationPrecision = if (foregroundGranted && !fineGranted) {
                    LocationPrecisionMode.APPROXIMATE
                } else {
                    current.locationPrecision
                },
                locationPermissionDenied = !foregroundGranted,
                locationPermissionPermanentlyDenied = !foregroundGranted && permanentlyDenied,
            )
        }
    }

    fun onBackgroundLocationResult(granted: Boolean) {
        onBackgroundLocationResult(granted, permanentlyDenied = false)
    }

    fun onBackgroundLocationResult(granted: Boolean, permanentlyDenied: Boolean) {
        updateState {
            it.copy(
                backgroundLocationGranted = granted,
                backgroundLocationPermissionDenied = !granted,
                backgroundLocationPermissionPermanentlyDenied = !granted && permanentlyDenied,
            )
        }
    }

    fun onActivityPermissionResult(granted: Boolean) {
        onActivityPermissionResult(granted, permanentlyDenied = false)
    }

    fun onActivityPermissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        updateState {
            it.copy(
                activityPermissionGranted = granted,
                activityEnabled = if (granted) it.activityEnabled else false,
                autoTrackingMode = if (granted) it.autoTrackingMode else AutoTrackingMode.Disabled,
                activityPermissionDenied = !granted,
                activityPermissionPermanentlyDenied = !granted && permanentlyDenied,
            )
        }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        updateState { it.copy(notificationPermissionGranted = granted) }
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
                val requestedState = _state.value
                val runtimePermissions = readRuntimePermissionState()
                val effectiveState = requestedState.withRuntimePermissions(runtimePermissions)

                if (requestedState.locationEnabled && !effectiveState.locationEnabled) {
                    showPermissionDeniedToast(R.string.setup_source_location)
                }
                if (
                    (requestedState.activityEnabled ||
                        requestedState.autoTrackingMode != AutoTrackingMode.Disabled) &&
                    !runtimePermissions.activityRecognitionGranted
                ) {
                    showPermissionDeniedToast(R.string.setup_source_activity)
                }

                replaceState(effectiveState)
                withContext(dispatchers.io) {
                    applyPreferences(effectiveState, runtimePermissions)
                    onboardingRepository.markCompleted()
                }
                dataRetentionScheduler.initialize()
                onDone()
            } catch (e: Exception) {
                Reporter.report(e)
                showSetupFailedToast(e)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyPreferences(
        s: SetupUiState,
        runtimePermissions: RuntimePermissionState,
    ) {
        val preferences = Preferences(appContext)
        val preset = s.trackingPreset.settings
        val effectiveLocationEnabled = s.locationEnabled && runtimePermissions.locationGranted
        val effectiveLocationPrecision = when {
            !effectiveLocationEnabled -> null
            runtimePermissions.fineLocationGranted -> s.locationPrecision
            else -> LocationPrecisionMode.APPROXIMATE
        }
        val effectiveActivityEnabled = s.activityEnabled && runtimePermissions.activityRecognitionGranted
        val effectiveMode = if (runtimePermissions.activityRecognitionGranted) {
            s.autoTrackingMode.ordinal
        } else {
            AutoTrackingMode.Disabled.ordinal
        }

        preferences.edit {
            // Location precision
            if (effectiveLocationPrecision != null) {
                setString(
                    appContext.getString(PrefR.string.settings_location_precision_key),
                    effectiveLocationPrecision.name,
                )
            }

            // Data source toggles
            setBoolean(PreferenceKeys.LOCATION_ENABLED, effectiveLocationEnabled)
            setBoolean(PreferenceKeys.ACTIVITY_ENABLED, effectiveActivityEnabled)
            setBoolean(PreferenceKeys.STEPS_ENABLED, s.stepsEnabled)
            setBoolean(PreferenceKeys.WIFI_ENABLED, s.wifiEnabled)
            setBoolean(PreferenceKeys.WIFI_LOCATION_COUNT_ENABLED, preset.wifiLocationCountEnabled)
            setBoolean(PreferenceKeys.CELL_ENABLED, s.cellEnabled)

            // Tracking preset parameters
            setInt(PreferenceKeys.TRACKING_MIN_DISTANCE, preset.minDistanceMeters)
            setInt(PreferenceKeys.TRACKING_MIN_TIME, preset.minTimeSeconds)
            setInt(PreferenceKeys.TRACKING_REQUIRED_ACCURACY, preset.requiredAccuracyMeters)

            // Auto-tracking
            setBoolean(
                PreferenceKeys.AUTO_TRACKING_TRANSITION_ENABLED,
                preset.useTransitionDetection && effectiveMode != AutoTrackingMode.Disabled.ordinal,
            )
            setBoolean(
                appContext.getString(ActivityR.string.settings_activity_watcher_key),
                effectiveMode != AutoTrackingMode.Disabled.ordinal,
            )

            // Notification styling on
            setBoolean(PreferenceKeys.NOTIFICATION_STYLED, true)

            // Auto-tracking mode
            setInt(PreferenceKeys.TRACKING_ACTIVITY_MODE, effectiveMode)
        }

        // Poke activity watcher so it picks up the new settings immediately
        activityWatcherController.poke(autoTracking = effectiveMode)
        if (effectiveMode != AutoTrackingMode.Disabled.ordinal) {
            activityWatcherController.poke()
        }
    }

    private fun SetupUiState.withRuntimePermissions(
        runtimePermissions: RuntimePermissionState,
    ): SetupUiState {
        val effectiveLocationEnabled = locationEnabled && runtimePermissions.locationGranted
        val effectiveActivityEnabled = activityEnabled && runtimePermissions.activityRecognitionGranted
        return copy(
            locationEnabled = effectiveLocationEnabled,
            activityEnabled = effectiveActivityEnabled,
            locationPermissionGranted = runtimePermissions.locationGranted,
            activityPermissionGranted = runtimePermissions.activityRecognitionGranted,
            autoTrackingMode = if (runtimePermissions.activityRecognitionGranted) {
                autoTrackingMode
            } else {
                AutoTrackingMode.Disabled
            },
            locationPrecision = if (effectiveLocationEnabled && !runtimePermissions.fineLocationGranted) {
                LocationPrecisionMode.APPROXIMATE
            } else {
                locationPrecision
            },
        )
    }

    private fun readRuntimePermissionState(): RuntimePermissionState {
        return RuntimePermissionState(
            fineLocationGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
            coarseLocationGranted = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
            activityRecognitionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                hasPermission(Manifest.permission.ACTIVITY_RECOGNITION),
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionDeniedToast(@StringRes sourceNameRes: Int) {
        val sourceName = appContext.getString(sourceNameRes)
        Toast.makeText(
            appContext,
            appContext.getString(R.string.permission_disabled_due_to_denial, sourceName),
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun showSetupFailedToast(error: Exception) {
        val message = error.localizedMessage?.takeIf { it.isNotBlank() }
            ?: error.javaClass.simpleName
        Toast.makeText(
            appContext,
            appContext.getString(R.string.setup_failed_message, message),
            Toast.LENGTH_LONG,
        ).show()
    }

    // endregion

    // region Saved state

    private fun updateState(transform: (SetupUiState) -> SetupUiState) {
        _state.update { current ->
            transform(current).also(::persistRestorableState)
        }
    }

    private fun replaceState(newState: SetupUiState) {
        persistRestorableState(newState)
        _state.value = newState
    }

    private fun restoreState(): SetupUiState {
        val defaults = SetupUiState()
        return defaults.copy(
            currentStep = savedStateHandle.get<String>(KEY_CURRENT_STEP)
                ?.let { name -> runCatching { SetupStep.valueOf(name) }.getOrNull() }
                ?: defaults.currentStep,
            autoTrackingMode = savedStateHandle.get<Int>(KEY_AUTO_TRACKING_MODE)
                ?.let(AutoTrackingMode::fromOrdinal)
                ?: defaults.autoTrackingMode,
            trackingPreset = savedStateHandle.get<String>(KEY_TRACKING_PRESET)
                ?.let { name -> runCatching { TrackingPolicyPreset.valueOf(name) }.getOrNull() }
                ?: defaults.trackingPreset,
            locationEnabled = savedStateHandle.get<Boolean>(KEY_LOCATION_ENABLED)
                ?: defaults.locationEnabled,
            locationPrecision = savedStateHandle.get<String>(KEY_LOCATION_PRECISION)
                ?.let { name -> runCatching { LocationPrecisionMode.valueOf(name) }.getOrNull() }
                ?: defaults.locationPrecision,
            activityEnabled = savedStateHandle.get<Boolean>(KEY_ACTIVITY_ENABLED)
                ?: defaults.activityEnabled,
        )
    }

    private fun persistRestorableState(s: SetupUiState) {
        savedStateHandle[KEY_CURRENT_STEP] = s.currentStep.name
        savedStateHandle[KEY_AUTO_TRACKING_MODE] = s.autoTrackingMode.ordinal
        savedStateHandle[KEY_TRACKING_PRESET] = s.trackingPreset.name
        savedStateHandle[KEY_LOCATION_ENABLED] = s.locationEnabled
        savedStateHandle[KEY_LOCATION_PRECISION] = s.locationPrecision.name
        savedStateHandle[KEY_ACTIVITY_ENABLED] = s.activityEnabled
    }

    // endregion

    private data class RuntimePermissionState(
        val fineLocationGranted: Boolean,
        val coarseLocationGranted: Boolean,
        val activityRecognitionGranted: Boolean,
    ) {
        val locationGranted: Boolean
            get() = fineLocationGranted || coarseLocationGranted
    }

    private companion object {
        const val KEY_CURRENT_STEP = "setup_current_step"
        const val KEY_AUTO_TRACKING_MODE = "setup_auto_tracking_mode"
        const val KEY_TRACKING_PRESET = "setup_tracking_preset"
        const val KEY_LOCATION_ENABLED = "setup_location_enabled"
        const val KEY_LOCATION_PRECISION = "setup_location_precision"
        const val KEY_ACTIVITY_ENABLED = "setup_activity_enabled"
    }
}
