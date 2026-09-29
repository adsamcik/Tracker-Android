package com.adsamcik.tracker.app.onboarding.data

import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionMode
import com.adsamcik.tracker.app.settings.data.TrackingPolicyPreset
import com.adsamcik.tracker.shared.base.extension.PermissionGrantHistory
import com.adsamcik.tracker.shared.base.extension.TrackingPermissionCapabilities
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsCapability
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsPermission
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProvider

enum class SetupCompletionError { SETTINGS, RETENTION }

/**
 * Immutable UI state for the first-time setup wizard.
 */
data class SetupUiState(
    val currentStep: SetupStep = SetupStep.Welcome,

    // Step 2 – How to Track
    /** 0 = disabled, 1 = on foot, 2 = in motion. */
    val autoTrackingMode: Int = 1,
    val trackingPreset: TrackingPolicyPreset = TrackingPolicyPreset.BALANCED,
	val balanceLevel: Int = 3,

    // Step 3 – What to Collect
    val locationEnabled: Boolean = true,
    val locationPrecision: LocationPrecisionMode = LocationPrecisionMode.PRECISE,
    val activityEnabled: Boolean = true,
    val stepsEnabled: Boolean = true,
    val stepCounterAvailable: Boolean = true,
	val stepsProviderCapability: AmbientStepsCapability? = null,
	val stepsProviderRepairFailed: Boolean = false,
	val pressureEnabled: Boolean = false,
	val pressureSensorAvailable: Boolean = true,
    val wifiEnabled: Boolean = false,
    val cellEnabled: Boolean = false,

    // Permissions (tracked so the UI can show granted/pending status)
    val permissionCapabilities: TrackingPermissionCapabilities = TrackingPermissionCapabilities.denied(),
    val permissionHistory: PermissionGrantHistory = PermissionGrantHistory(),
    val activityPermissionGranted: Boolean = false,
    val notificationPermissionGranted: Boolean = false,
    val cellPermissionGranted: Boolean = false,

    // Denial alerts keep selected sources visible while offering a permission repair.
    val locationPermissionDenied: Boolean = false,
    val backgroundLocationDeclined: Boolean = false,
    val activityPermissionDenied: Boolean = false,
    val wifiPermissionDenied: Boolean = false,
    val cellPermissionDenied: Boolean = false,

    // Step 4 – Online Map Tiles (recommended for the easiest map experience)
    val onlineMapTilesEnabled: Boolean = true,
	val completionInProgress: Boolean = false,
	val completionError: SetupCompletionError? = null,
) {
	init { require(balanceLevel in 1..5) }
	val locationPermissionGranted: Boolean
		get() = permissionCapabilities.hasForegroundLocation

	val preciseLocationPermissionGranted: Boolean
		get() = permissionCapabilities.hasPreciseLocation

	val backgroundLocationGranted: Boolean
		get() = permissionCapabilities.hasBackgroundLocation

	val wifiPermissionGranted: Boolean
		get() = permissionCapabilities.hasWifiScanPermissions

	val manualLocationOnly: Boolean
		get() = locationEnabled && permissionCapabilities.isManualLocationOnly

    /** Progress fraction 0..1 based on current step. */
    val progress: Float
        get() = (currentStep.index + 1).toFloat() / SetupStep.totalSteps

    /** Activity and direct/local-provider Steps share Android's motion permission. */
    val needsActivityPermission: Boolean
        get() = autoTrackingMode > 0 || activityEnabled || stepsNeedsMotionPermission

	val stepsNeedsMotionPermission: Boolean
		get() = stepsEnabled && (stepCounterAvailable ||
			(stepsProviderCapability as? AmbientStepsCapability.PermissionRequired)?.let {
				it.provider == AmbientStepsProvider.LOCAL_RECORDING_STEPS &&
					AmbientStepsPermission.ACTIVITY_RECOGNITION in it.requiredPermissions
			} == true)

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
