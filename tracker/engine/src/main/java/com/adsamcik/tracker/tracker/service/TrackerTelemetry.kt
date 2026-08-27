package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.diagnostics.TrackerTraceboxTemplates
import com.adsamcik.tracker.tracker.source.coordinator.TrackingCoordinatorMetrics
import dev.tracebox.api.TraceboxLogger
import dev.tracebox.api.public

/** Records one bounded, payload-free structural summary when a tracking session stops. */
internal fun TraceboxLogger.recordCoordinatorSessionMetrics(metrics: TrackingCoordinatorMetrics) {
	info(
		TrackerTraceboxTemplates.TRACKING_COORDINATOR_SESSION_COUNTS,
		public(metrics.projectionDrainCount),
		public(metrics.projectedEventCount),
		public(metrics.planRevisionCount),
		public(metrics.trackingFrameCount),
		public(metrics.sourceTimerWakeupCount),
		public(metrics.sourceTimerRequestCount),
		public(metrics.motionPolicyChangeCount),
		public(metrics.stationaryOptimizationCount),
		public(metrics.fullFidelityRestoreCount),
	)
	performanceEvent(
		TrackerTraceboxTemplates.TRACKING_COORDINATOR_SESSION_TIMINGS,
		public(metrics.projectionDrainNanos),
		public(metrics.trackingFrameWakeLockNanos),
	)
}
