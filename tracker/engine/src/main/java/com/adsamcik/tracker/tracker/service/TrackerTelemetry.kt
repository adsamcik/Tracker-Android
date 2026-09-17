package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.diagnostics.TrackerDiagnosticLog
import com.adsamcik.tracker.tracker.source.coordinator.TrackingCoordinatorMetrics

/** Records one bounded, payload-free structural summary when a tracking session stops. */
internal fun recordCoordinatorSessionMetrics(metrics: TrackingCoordinatorMetrics) {
	TrackerDiagnosticLog.trackingCoordinatorSessionMetrics(
		projectionDrainCount = metrics.projectionDrainCount,
		projectedEventCount = metrics.projectedEventCount,
		projectionDrainNanos = metrics.projectionDrainNanos,
		planRevisionCount = metrics.planRevisionCount,
		trackingFrameCount = metrics.trackingFrameCount,
		trackingFrameWakeLockNanos = metrics.trackingFrameWakeLockNanos,
		sourceTimerWakeupCount = metrics.sourceTimerWakeupCount,
		sourceTimerRequestCount = metrics.sourceTimerRequestCount,
		motionPolicyChangeCount = metrics.motionPolicyChangeCount,
		stationaryOptimizationCount = metrics.stationaryOptimizationCount,
		fullFidelityRestoreCount = metrics.fullFidelityRestoreCount,
	)
}
