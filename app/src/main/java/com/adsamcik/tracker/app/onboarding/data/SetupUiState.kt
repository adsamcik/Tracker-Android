package com.adsamcik.tracker.app.onboarding.data

import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionMode
import com.adsamcik.tracker.app.settings.data.TrackingPolicyPreset

/**
 * Immutable UI state for the first-time setup wizard.
 */
data class SetupUiState(
    val currentStep: SetupStep = SetupStep.Welcome,

    // Step 2 – How to Track
    /** 0 = disabled, 1 = on foot, 2 = in motion. */
    val autoTrackingMode: Int = 1,
    val trackingPreset: TrackingPolicyPreset = TrackingPolicyPreset.DEFAULT,

    // Step 3 – What to Collect
    val locationEnabled: Boolean = true,
    val locationPrecision: LocationPrecisionMode = LocationPrecisionMode.PRECISE,
    val activityEnabled: Boolean = true,
    val stepsEnabled: Boolean = true,
    val wifiEnabled: Boolean = false,
    val cellEnabled: Boolean = false,

    // Permissions (tracked so the UI can show granted/pending status)
    val locationPermissionGranted: Boolean = false,
    val backgroundLocationGranted: Boolean = false,
    val activityPermissionGranted: Boolean = false,
    val notificationPermissionGranted: Boolean = false,
    val wifiPermissionGranted: Boolean = false,
    val cellPermissionGranted: Boolean = false,

    // Denial alerts — set when the user asked to collect a source but denied its
    // runtime permission. The source is auto-disabled and the UI surfaces a
    // recoverable inline alert until the user re-toggles it.
    val locationPermissionDenied: Boolean = false,
    val activityPermissionDenied: Boolean = false,
    val wifiPermissionDenied: Boolean = false,
    val cellPermissionDenied: Boolean = false,

    // Step 4 – Online Map Tiles (opt-in, off by default)
    val onlineMapTilesEnabled: Boolean = false,
) {
    /** Progress fraction 0..1 based on current step. */
    val progress: Float
        get() = (currentStep.index + 1).toFloat() / SetupStep.totalSteps

    /** True when auto-tracking requires activity recognition permission. */
    val needsActivityPermission: Boolean
        get() = autoTrackingMode > 0 || activityEnabled

    /** True when location tracking is enabled and needs foreground permission. */
    val needsLocationPermission: Boolean
        get() = locationEnabled

    /** True when auto-tracking is enabled and needs background location. */
    val needsBackgroundLocationPermission: Boolean
        get() = autoTrackingMode > 0 && locationEnabled

    /** True when Wi-Fi collection is enabled and still needs runtime permission. */
    val needsWifiPermission: Boolean
        get() = wifiEnabled && !wifiPermissionGranted

    /** True when cell/radio collection is enabled and still needs runtime permission. */
    val needsCellPermission: Boolean
        get() = cellEnabled && !cellPermissionGranted
}
