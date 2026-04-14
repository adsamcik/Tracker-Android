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
}
