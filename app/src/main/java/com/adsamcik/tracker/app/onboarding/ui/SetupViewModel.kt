package com.adsamcik.tracker.app.onboarding.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.app.onboarding.data.SetupStep
import com.adsamcik.tracker.app.onboarding.data.SetupUiState
import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionMode
import com.adsamcik.tracker.app.settings.data.TrackingPolicyPreset
import com.adsamcik.tracker.maintenance.DataRetentionScheduler
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.base.extension.hasCellScanPermission
import com.adsamcik.tracker.shared.base.extension.hasPressureSensor
import com.adsamcik.tracker.shared.base.extension.hasStepCounterSensor
import com.adsamcik.tracker.shared.base.extension.ForegroundLocationCapability
import com.adsamcik.tracker.shared.base.extension.PermissionGrantHistory
import com.adsamcik.tracker.shared.base.extension.TrackingPermissionCapabilities
import com.adsamcik.tracker.shared.base.extension.trackingPermissionCapabilities
import com.adsamcik.tracker.shared.base.result.runCatchingCancellable
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.map.OnlineMapTilesRepository
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

/**
 * ViewModel for the first-time setup wizard.
 *
 * Manages step navigation, user selections, and applies all preferences
 * atomically when onboarding completes.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val savedStateHandle: SavedStateHandle,
    private val dispatchers: DispatchersProvider,
    private val onboardingRepository: OnboardingRepository,
    private val activityWatcherController: ActivityWatcherServiceController,
    private val dataRetentionScheduler: DataRetentionScheduler,
    private val trackingParamsRepository: TrackingParamsRepository,
    private val onlineMapTilesRepository: OnlineMapTilesRepository,
) : ViewModel() {

    private val restoredDraft = savedStateHandle.restoreDraft(
            stepCounterAvailable = appContext.hasStepCounterSensor,
        )
    private val _state = MutableStateFlow(
        restoredDraft.withCapabilities(
            appContext.trackingPermissionCapabilities(restoredDraft.permissionHistory),
        ),
    )
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

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

    fun setAutoTrackingMode(mode: Int) {
        updateState { it.copy(autoTrackingMode = mode.coerceIn(0, 2)) }
    }

    fun setTrackingPreset(preset: TrackingPolicyPreset) {
        updateState { it.copy(trackingPreset = preset) }
    }

    // endregion

    // region Step 3 – What to Collect

    fun setLocationEnabled(enabled: Boolean) {
        updateState { it.copy(locationEnabled = enabled, locationPermissionDenied = false) }
    }

    fun setLocationPrecision(mode: LocationPrecisionMode) {
        updateState { it.copy(locationPrecision = mode) }
    }

    fun setActivityEnabled(enabled: Boolean) {
        updateState { it.copy(activityEnabled = enabled, activityPermissionDenied = false) }
    }

    fun setStepsEnabled(enabled: Boolean) {
        updateState { it.copy(stepsEnabled = enabled && it.stepCounterAvailable) }
    }

    fun setWifiEnabled(enabled: Boolean) {
        updateState { it.copy(wifiEnabled = enabled, wifiPermissionDenied = false) }
    }

    fun setCellEnabled(enabled: Boolean) {
        updateState { it.copy(cellEnabled = enabled, cellPermissionDenied = false) }
    }

    // endregion

    // region Permissions

    fun onLocationPermissionResult(results: Map<String, Boolean>) {
        val current = _state.value
        val capabilities = current.permissionCapabilities.withGrants(
            coarseLocationGranted = results[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true,
            preciseLocationGranted = results[android.Manifest.permission.ACCESS_FINE_LOCATION] == true,
            history = current.permissionHistory,
        )
        updateState {
            if (capabilities.hasForegroundLocation) {
                it.withCapabilities(capabilities).copy(locationPermissionDenied = false)
            } else {
                // Honor the user's intent: if they want to collect a source but deny its
                // permission, disable that collection and alert them rather than silently
                // leaving a toggle on that records nothing.
                it.withCapabilities(capabilities).copy(
                    locationEnabled = false,
                    locationPermissionDenied = true,
                )
            }
        }
    }

    fun onBackgroundLocationResult(granted: Boolean) {
        updateState {
            it.withCapabilities(
                it.permissionCapabilities.withGrants(
                    backgroundLocationGranted = granted,
                    history = it.permissionHistory,
                ),
            ).copy(backgroundLocationDeclined = !granted)
        }
    }

	fun declineBackgroundLocation() {
		updateState { it.copy(backgroundLocationDeclined = true) }
	}

	/** Re-read Settings grants/revocations and precise-to-approximate changes on every resume. */
	fun refreshPermissionCapabilities() {
		updateState { current ->
			val capabilities = appContext.trackingPermissionCapabilities(current.permissionHistory)
			current.withCapabilities(capabilities).copy(
				locationPermissionDenied = current.locationPermissionDenied &&
					!capabilities.hasForegroundLocation,
				wifiPermissionDenied = current.wifiPermissionDenied && !capabilities.hasWifiScanPermissions,
			)
		}
	}

    fun onActivityPermissionResult(granted: Boolean) {
        updateState {
            if (granted) {
                it.copy(activityPermissionGranted = true, activityPermissionDenied = false)
            } else {
                it.copy(
                    activityPermissionGranted = false,
                    activityEnabled = false,
                    stepsEnabled = false,
                    activityPermissionDenied = true,
                )
            }
        }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        updateState { it.copy(notificationPermissionGranted = granted) }
    }

    fun onWifiPermissionResult(results: Map<String, Boolean>) {
        val current = _state.value
        val capabilities = current.permissionCapabilities.withGrants(
            coarseLocationGranted = current.permissionCapabilities.coarseLocationGranted ||
                results[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true,
            preciseLocationGranted = current.permissionCapabilities.preciseLocationGranted ||
                results[android.Manifest.permission.ACCESS_FINE_LOCATION] == true,
            nearbyWifiGranted = current.permissionCapabilities.nearbyWifiGranted ||
                results[android.Manifest.permission.NEARBY_WIFI_DEVICES] == true,
            history = current.permissionHistory,
        )
        updateState {
            if (capabilities.hasWifiScanPermissions) {
                it.withCapabilities(capabilities).copy(wifiPermissionDenied = false)
            } else {
                it.withCapabilities(capabilities).copy(
                    wifiEnabled = false,
                    wifiPermissionDenied = true,
                )
            }
        }
    }

    fun onCellPermissionResult(granted: Boolean) {
        updateState {
            if (granted) {
                it.copy(cellPermissionGranted = true, cellPermissionDenied = false)
            } else {
                it.copy(
                    cellPermissionGranted = false,
                    cellEnabled = false,
                    cellPermissionDenied = true,
                )
            }
        }
    }

    // endregion

    // region Step 4 – Online Map Tiles

    fun setOnlineMapTilesEnabled(enabled: Boolean) {
        updateState { it.copy(onlineMapTilesEnabled = enabled) }
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
            runCatchingCancellable {
                val s = _state.value
                withContext(dispatchers.io) {
                    applyPreferences(s)
                    onboardingRepository.markCompleted()
                }
                dataRetentionScheduler.initialize()
                onDone()
            }.getOrNull()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun applyPreferences(s: SetupUiState) {
        val preferences = Preferences(appContext)
        val preset = s.trackingPreset.settings
        val capabilities = appContext.trackingPermissionCapabilities(s.permissionHistory)
        val locationAllowed = s.locationEnabled && capabilities.hasForegroundLocation
        val wifiAllowed = s.wifiEnabled && capabilities.wifiFeatureAvailable &&
            capabilities.hasWifiScanPermissions
        val cellAllowed = s.cellEnabled && appContext.hasCellScanPermission
        val activityAllowed = s.activityPermissionGranted || appContext.hasActivityPermission
        val automaticLocationAllowed = !locationAllowed || capabilities.hasBackgroundLocation
        val effectiveMode = if (activityAllowed && automaticLocationAllowed) s.autoTrackingMode else 0
        val effectivePrecision = if (capabilities.hasPreciseLocation) {
            LocationPrecisionMode.PRECISE
        } else {
            LocationPrecisionMode.APPROXIMATE
        }

        preferences.edit {
            // Location precision
            setString(
                appContext.getString(PrefR.string.settings_location_precision_key),
                effectivePrecision.name,
            )

            // Activity watcher service flag is still a legacy preference bridge.
            setBoolean(
                appContext.getString(ActivityR.string.settings_activity_watcher_key),
                effectiveMode > 0,
            )
        }

        trackingParamsRepository.update {
            copy(
                locationEnabled = locationAllowed,
                activityEnabled = s.activityEnabled && activityAllowed,
                stepsEnabled = s.stepsEnabled &&
                    s.stepCounterAvailable && activityAllowed,
                wifiEnabled = wifiAllowed,
                cellEnabled = cellAllowed,
                barometerEnabled = preset.barometerEnabled && appContext.hasPressureSensor,
                autoTrackingMode = effectiveMode,
                transitionDetectionEnabled = preset.useTransitionDetection,
                notificationStyled = true,
                minDistanceMeters = preset.minDistanceMeters,
                minTimeSeconds = preset.minTimeSeconds,
                requiredAccuracyMeters = preset.requiredAccuracyMeters,
                presetName = s.trackingPreset.toTrackingPreset().name,
            )
        }

        // Mirror the onboarding choice explicitly. Online is recommended for
        // the lowest-friction experience, while users can still select the
        // bundled offline map before completing setup.
        onlineMapTilesRepository.setEnabled(s.onlineMapTilesEnabled)

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

    private fun updateState(transform: (SetupUiState) -> SetupUiState) {
        _state.update { current ->
            transform(current).also(::saveDraft)
        }
    }

    private fun saveDraft(state: SetupUiState) {
        savedStateHandle[KEY_STEP] = state.currentStep.index
        savedStateHandle[KEY_AUTO_TRACKING_MODE] = state.autoTrackingMode
        savedStateHandle[KEY_TRACKING_PRESET] = state.trackingPreset.name
        savedStateHandle[KEY_LOCATION_ENABLED] = state.locationEnabled
        savedStateHandle[KEY_LOCATION_PRECISION] = state.locationPrecision.name
        savedStateHandle[KEY_ACTIVITY_ENABLED] = state.activityEnabled
        savedStateHandle[KEY_STEPS_ENABLED] = state.stepsEnabled
        savedStateHandle[KEY_WIFI_ENABLED] = state.wifiEnabled
        savedStateHandle[KEY_CELL_ENABLED] = state.cellEnabled
        savedStateHandle[KEY_FOREGROUND_LOCATION_EVER_GRANTED] =
            state.permissionHistory.foregroundLocationGranted
        savedStateHandle[KEY_BACKGROUND_LOCATION_EVER_GRANTED] =
            state.permissionHistory.backgroundLocationGranted
        savedStateHandle[KEY_WIFI_SCAN_EVER_GRANTED] = state.permissionHistory.wifiScanGranted
        savedStateHandle[KEY_ACTIVITY_PERMISSION_GRANTED] = state.activityPermissionGranted
        savedStateHandle[KEY_NOTIFICATION_PERMISSION_GRANTED] = state.notificationPermissionGranted
        savedStateHandle[KEY_CELL_PERMISSION_GRANTED] = state.cellPermissionGranted
        savedStateHandle[KEY_LOCATION_PERMISSION_DENIED] = state.locationPermissionDenied
        savedStateHandle[KEY_BACKGROUND_LOCATION_DECLINED] = state.backgroundLocationDeclined
        savedStateHandle[KEY_ACTIVITY_PERMISSION_DENIED] = state.activityPermissionDenied
        savedStateHandle[KEY_WIFI_PERMISSION_DENIED] = state.wifiPermissionDenied
        savedStateHandle[KEY_CELL_PERMISSION_DENIED] = state.cellPermissionDenied
        savedStateHandle[KEY_ONLINE_MAP_TILES_ENABLED] = state.onlineMapTilesEnabled
    }

    private fun SavedStateHandle.restoreDraft(stepCounterAvailable: Boolean): SetupUiState = SetupUiState(
        currentStep = get<Int>(KEY_STEP)?.let(SetupStep::fromIndex) ?: SetupStep.Welcome,
        autoTrackingMode = get<Int>(KEY_AUTO_TRACKING_MODE) ?: 1,
        trackingPreset = get<String>(KEY_TRACKING_PRESET)
            ?.let { runCatching { TrackingPolicyPreset.valueOf(it) }.getOrNull() }
            ?: TrackingPolicyPreset.BALANCED,
        locationEnabled = get<Boolean>(KEY_LOCATION_ENABLED) ?: true,
        locationPrecision = get<String>(KEY_LOCATION_PRECISION)
            ?.let { runCatching { LocationPrecisionMode.valueOf(it) }.getOrNull() }
            ?: LocationPrecisionMode.PRECISE,
        activityEnabled = get<Boolean>(KEY_ACTIVITY_ENABLED) ?: true,
        stepsEnabled = get<Boolean>(KEY_STEPS_ENABLED) ?: true,
        stepCounterAvailable = stepCounterAvailable,
        wifiEnabled = get<Boolean>(KEY_WIFI_ENABLED) ?: false,
        cellEnabled = get<Boolean>(KEY_CELL_ENABLED) ?: false,
        permissionHistory = PermissionGrantHistory(
            foregroundLocationGranted = get<Boolean>(KEY_FOREGROUND_LOCATION_EVER_GRANTED) ?: false,
            backgroundLocationGranted = get<Boolean>(KEY_BACKGROUND_LOCATION_EVER_GRANTED) ?: false,
            wifiScanGranted = get<Boolean>(KEY_WIFI_SCAN_EVER_GRANTED) ?: false,
        ),
        activityPermissionGranted = get<Boolean>(KEY_ACTIVITY_PERMISSION_GRANTED) ?: false,
        notificationPermissionGranted = get<Boolean>(KEY_NOTIFICATION_PERMISSION_GRANTED) ?: false,
        cellPermissionGranted = get<Boolean>(KEY_CELL_PERMISSION_GRANTED) ?: false,
        locationPermissionDenied = get<Boolean>(KEY_LOCATION_PERMISSION_DENIED) ?: false,
        backgroundLocationDeclined = get<Boolean>(KEY_BACKGROUND_LOCATION_DECLINED) ?: false,
        activityPermissionDenied = get<Boolean>(KEY_ACTIVITY_PERMISSION_DENIED) ?: false,
        wifiPermissionDenied = get<Boolean>(KEY_WIFI_PERMISSION_DENIED) ?: false,
        cellPermissionDenied = get<Boolean>(KEY_CELL_PERMISSION_DENIED) ?: false,
        onlineMapTilesEnabled = get<Boolean>(KEY_ONLINE_MAP_TILES_ENABLED) ?: true,
    )

    private companion object {
        const val KEY_STEP = "setup_step"
        const val KEY_AUTO_TRACKING_MODE = "setup_auto_tracking_mode"
        const val KEY_TRACKING_PRESET = "setup_tracking_preset"
        const val KEY_LOCATION_ENABLED = "setup_location_enabled"
        const val KEY_LOCATION_PRECISION = "setup_location_precision"
        const val KEY_ACTIVITY_ENABLED = "setup_activity_enabled"
        const val KEY_STEPS_ENABLED = "setup_steps_enabled"
        const val KEY_WIFI_ENABLED = "setup_wifi_enabled"
        const val KEY_CELL_ENABLED = "setup_cell_enabled"
        const val KEY_FOREGROUND_LOCATION_EVER_GRANTED = "setup_foreground_location_ever_granted"
        const val KEY_BACKGROUND_LOCATION_EVER_GRANTED = "setup_background_location_ever_granted"
        const val KEY_WIFI_SCAN_EVER_GRANTED = "setup_wifi_scan_ever_granted"
        const val KEY_ACTIVITY_PERMISSION_GRANTED = "setup_activity_permission_granted"
        const val KEY_NOTIFICATION_PERMISSION_GRANTED = "setup_notification_permission_granted"
        const val KEY_CELL_PERMISSION_GRANTED = "setup_cell_permission_granted"
        const val KEY_LOCATION_PERMISSION_DENIED = "setup_location_permission_denied"
        const val KEY_BACKGROUND_LOCATION_DECLINED = "setup_background_location_declined"
        const val KEY_ACTIVITY_PERMISSION_DENIED = "setup_activity_permission_denied"
        const val KEY_WIFI_PERMISSION_DENIED = "setup_wifi_permission_denied"
        const val KEY_CELL_PERMISSION_DENIED = "setup_cell_permission_denied"
        const val KEY_ONLINE_MAP_TILES_ENABLED = "setup_online_map_tiles_enabled"
    }
}

private fun SetupUiState.withCapabilities(
    capabilities: TrackingPermissionCapabilities,
): SetupUiState = copy(
    permissionCapabilities = capabilities,
    permissionHistory = capabilities.recordGrants(permissionHistory),
    locationPrecision = when (capabilities.foregroundLocation) {
        ForegroundLocationCapability.PRECISE -> LocationPrecisionMode.PRECISE
        ForegroundLocationCapability.APPROXIMATE -> LocationPrecisionMode.APPROXIMATE
        else -> locationPrecision
    },
    backgroundLocationDeclined = backgroundLocationDeclined && !capabilities.hasBackgroundLocation,
)

private fun TrackingPermissionCapabilities.withGrants(
    coarseLocationGranted: Boolean = this.coarseLocationGranted,
    preciseLocationGranted: Boolean = this.preciseLocationGranted,
    backgroundLocationGranted: Boolean = this.backgroundLocationGranted,
    nearbyWifiGranted: Boolean = this.nearbyWifiGranted,
    history: PermissionGrantHistory = PermissionGrantHistory(),
): TrackingPermissionCapabilities = TrackingPermissionCapabilities.evaluate(
    apiLevel = apiLevel,
    locationFeatureAvailable = locationFeatureAvailable,
    wifiFeatureAvailable = wifiFeatureAvailable,
    locationServicesEnabled = locationServicesEnabled,
    coarseLocationGranted = coarseLocationGranted,
    preciseLocationGranted = preciseLocationGranted,
    backgroundLocationGranted = backgroundLocationGranted,
    nearbyWifiGranted = nearbyWifiGranted,
    history = history,
)
