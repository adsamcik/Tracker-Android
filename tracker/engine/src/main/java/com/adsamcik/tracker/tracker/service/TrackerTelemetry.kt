package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.tracker.source.coordinator.TrackingCoordinatorMetrics
import dev.tracebox.api.TraceboxLogger

/** Records one bounded, payload-free structural summary when a tracking session stops. */
internal fun TraceboxLogger.recordCoordinatorSessionMetrics(metrics: TrackingCoordinatorMetrics) {
	info(
		"Tracking coordinator session: projection drains {}, projected events {}, " +
			"projection duration ns {}, plan revisions {}, frames {}, wake lock ns {}, " +
			"motion policy changes {}, stationary optimizations {}, fidelity restores {}",
		metrics.projectionDrainCount,
		metrics.projectedEventCount,
		metrics.projectionDrainNanos,
		metrics.planRevisionCount,
		metrics.trackingFrameCount,
		metrics.trackingFrameWakeLockNanos,
		metrics.motionPolicyChangeCount,
		metrics.stationaryOptimizationCount,
		metrics.fullFidelityRestoreCount,
	)
}
