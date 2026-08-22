package com.adsamcik.tracker.dashboard.ui.compose

import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState

/**
 * Runtime capabilities relevant to the Dashboard's manual-start decision.
 *
 * Source enablement comes from [TrackingParamsState], which is the compatibility projection of
 * the authoritative SourcePolicy. Capabilities stay separate so a permission required by one
 * source never becomes a hidden prerequisite for another source.
 */
internal data class DashboardCaptureCapabilities(
	val locationHardwareAvailable: Boolean,
	val anyLocationPermissionGranted: Boolean,
	val preciseLocationPermissionGranted: Boolean,
	val activityPermissionGranted: Boolean,
	val stepCounterAvailable: Boolean,
	val wifiHardwareAvailable: Boolean,
	val cellHardwareAvailable: Boolean,
	val readPhoneStatePermissionGranted: Boolean,
	val pressureSensorAvailable: Boolean,
)

internal enum class DashboardManualStartDecision {
	START,
	REQUEST_PRECISE_LOCATION_PERMISSION,
	NO_AVAILABLE_CAPTURE_SOURCE,
}

/**
 * Resolves the one action a manual start tap may take.
 *
 * Any ready requested source is enough to start. Missing capabilities for another requested
 * source are handled as source-specific degradation by the tracking runtime; they never block a
 * usable source. A precise-location prompt is returned only when it can make an otherwise
 * unavailable Location, Wi-Fi, or Cell-only request viable.
 */
internal fun resolveDashboardManualStartDecision(
	params: TrackingParamsState,
	capabilities: DashboardCaptureCapabilities,
): DashboardManualStartDecision {
	val hasReadyCaptureSource = params.hasAnyCaptureSource(
		locationAvailable = capabilities.locationHardwareAvailable &&
			capabilities.anyLocationPermissionGranted,
		activityAvailable = capabilities.activityPermissionGranted,
		stepsAvailable = capabilities.activityPermissionGranted &&
			capabilities.stepCounterAvailable,
		wifiAvailable = capabilities.wifiHardwareAvailable &&
			capabilities.preciseLocationPermissionGranted,
		cellAvailable = capabilities.cellHardwareAvailable &&
			capabilities.preciseLocationPermissionGranted &&
			capabilities.readPhoneStatePermissionGranted,
		barometerAvailable = capabilities.pressureSensorAvailable,
	)
	if (hasReadyCaptureSource) return DashboardManualStartDecision.START

	val preciseLocationWouldEnableCapture =
		(params.locationEnabled &&
			capabilities.locationHardwareAvailable &&
			!capabilities.anyLocationPermissionGranted) ||
			(params.wifiEnabled &&
				capabilities.wifiHardwareAvailable &&
				!capabilities.preciseLocationPermissionGranted) ||
			(params.cellEnabled &&
				capabilities.cellHardwareAvailable &&
				capabilities.readPhoneStatePermissionGranted &&
				!capabilities.preciseLocationPermissionGranted)

	return if (preciseLocationWouldEnableCapture) {
		DashboardManualStartDecision.REQUEST_PRECISE_LOCATION_PERMISSION
	} else {
		DashboardManualStartDecision.NO_AVAILABLE_CAPTURE_SOURCE
	}
}
