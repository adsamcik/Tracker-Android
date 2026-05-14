package com.adsamcik.tracker.app.onboarding.data

import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionMode
import com.adsamcik.tracker.app.settings.data.TrackingPolicyPreset

sealed class AutoTrackingMode(val ordinal: Int) {
    object Disabled : AutoTrackingMode(0)
    object OnFoot : AutoTrackingMode(1)
    object InMotion : AutoTrackingMode(2)

    companion object {
        fun fromOrdinal(i: Int) = when (i) {
            0 -> Disabled
            1 -> OnFoot
            2 -> InMotion
            else -> Disabled
        }
    }
}

/**
 * Immutable UI state for the first-time setup wizard.
 */
data class SetupUiState(
    val currentStep: SetupStep = SetupStep.Welcome,

    // Step 2 – How to Track
    val autoTrackingMode: AutoTrackingMode = AutoTrackingMode.Disabled,
    val trackingPreset: TrackingPolicyPreset = TrackingPolicyPreset.BATTERY_SAVER,

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
    val locationPermissionDenied: Boolean = false,
    val locationPermissionPermanentlyDenied: Boolean = false,
    val backgroundLocationPermissionDenied: Boolean = false,
    val backgroundLocationPermissionPermanentlyDenied: Boolean = false,
    val activityPermissionDenied: Boolean = false,
    val activityPermissionPermanentlyDenied: Boolean = false,
) {
    /** Progress fraction 0..1 based on current step. */
    val progress: Float
        get() = (currentStep.index + 1).toFloat() / SetupStep.totalSteps

    /** True when auto-tracking requires activity recognition permission. */
    val needsActivityPermission: Boolean
        get() = autoTrackingMode != AutoTrackingMode.Disabled || activityEnabled

    /** True when location tracking is enabled and needs foreground permission. */
    val needsLocationPermission: Boolean
        get() = locationEnabled

    /** True when auto-tracking is enabled and needs background location. */
    val needsBackgroundLocationPermission: Boolean
        get() = autoTrackingMode == AutoTrackingMode.InMotion && locationEnabled
}

data class SetupPermissionState(
    val fineLocationGranted: Boolean,
    val coarseLocationGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val activityRecognitionGranted: Boolean,
    val notificationGranted: Boolean,
    val locationPermanentlyDenied: Boolean = false,
    val backgroundLocationPermanentlyDenied: Boolean = false,
    val activityRecognitionPermanentlyDenied: Boolean = false,
) {
    val foregroundLocationGranted: Boolean
        get() = fineLocationGranted || coarseLocationGranted
}
