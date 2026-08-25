package com.adsamcik.tracker.dashboard.ui.compose

import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.api.TrackingCaptureSource

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
	/** Compatibility default; production capability discovery always supplies the real value. */
	val playServicesAvailable: Boolean = true,
)

internal enum class DashboardManualStartDecision {
	START,
	REQUEST_PRECISE_LOCATION_PERMISSION,
	TRACKING_UNAVAILABLE,
	NO_AVAILABLE_CAPTURE_SOURCE,
}

internal enum class DashboardManualStartEnqueueDecision {
	ENQUEUED,
	TRACKING_UNAVAILABLE,
}

internal fun resolveDashboardManualStartEnqueueDecision(
	enqueued: Boolean,
): DashboardManualStartEnqueueDecision = if (enqueued) {
	DashboardManualStartEnqueueDecision.ENQUEUED
} else {
	DashboardManualStartEnqueueDecision.TRACKING_UNAVAILABLE
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
	reachableSources: Set<TrackingCaptureSource> = TrackingCaptureSource.entries.toSet(),
): DashboardManualStartDecision {
	val hasReadyCaptureSource = params.hasAnyCaptureSource(
		locationAvailable = TrackingCaptureSource.LOCATION in reachableSources &&
			capabilities.locationHardwareAvailable &&
			capabilities.anyLocationPermissionGranted,
		activityAvailable = TrackingCaptureSource.ACTIVITY in reachableSources &&
			capabilities.activityPermissionGranted &&
			capabilities.playServicesAvailable,
		stepsAvailable = TrackingCaptureSource.STEPS in reachableSources &&
			capabilities.activityPermissionGranted &&
			capabilities.stepCounterAvailable,
		wifiAvailable = TrackingCaptureSource.WIFI in reachableSources &&
			capabilities.wifiHardwareAvailable &&
			capabilities.preciseLocationPermissionGranted,
		cellAvailable = TrackingCaptureSource.CELL in reachableSources &&
			capabilities.cellHardwareAvailable &&
			capabilities.preciseLocationPermissionGranted &&
			capabilities.readPhoneStatePermissionGranted,
		barometerAvailable = TrackingCaptureSource.PRESSURE in reachableSources &&
			capabilities.pressureSensorAvailable,
	)
	if (hasReadyCaptureSource) return DashboardManualStartDecision.START

	val preciseLocationWouldEnableCapture =
		(params.locationEnabled &&
			TrackingCaptureSource.LOCATION in reachableSources &&
			capabilities.locationHardwareAvailable &&
			!capabilities.anyLocationPermissionGranted) ||
			(params.wifiEnabled &&
				TrackingCaptureSource.WIFI in reachableSources &&
				capabilities.wifiHardwareAvailable &&
				!capabilities.preciseLocationPermissionGranted) ||
			(params.cellEnabled &&
				TrackingCaptureSource.CELL in reachableSources &&
				capabilities.cellHardwareAvailable &&
				capabilities.readPhoneStatePermissionGranted &&
				!capabilities.preciseLocationPermissionGranted)

	val enabledSources = params.enabledCaptureSources()
	return when {
		preciseLocationWouldEnableCapture ->
			DashboardManualStartDecision.REQUEST_PRECISE_LOCATION_PERMISSION
		enabledSources.isNotEmpty() && enabledSources.none(reachableSources::contains) ->
			DashboardManualStartDecision.TRACKING_UNAVAILABLE
		else -> DashboardManualStartDecision.NO_AVAILABLE_CAPTURE_SOURCE
	}
}

private fun TrackingParamsState.enabledCaptureSources(): Set<TrackingCaptureSource> = buildSet {
	if (locationEnabled) add(TrackingCaptureSource.LOCATION)
	if (wifiEnabled) add(TrackingCaptureSource.WIFI)
	if (cellEnabled) add(TrackingCaptureSource.CELL)
	if (activityEnabled) add(TrackingCaptureSource.ACTIVITY)
	if (stepsEnabled) add(TrackingCaptureSource.STEPS)
	if (barometerEnabled) add(TrackingCaptureSource.PRESSURE)
}
