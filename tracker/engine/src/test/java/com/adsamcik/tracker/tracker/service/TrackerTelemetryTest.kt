package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.diagnostics.TrackerDiagnosticLog
import com.adsamcik.tracker.tracker.source.coordinator.TrackingCoordinatorMetrics
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.Test

class TrackerTelemetryTest {
	@Test
	fun `coordinator summary delegates structural metrics through Tracebox-free facade`() {
		mockkObject(TrackerDiagnosticLog)
		try {
			every {
				TrackerDiagnosticLog.trackingCoordinatorSessionMetrics(
					projectionDrainCount = any(),
					projectedEventCount = any(),
					projectionDrainNanos = any(),
					planRevisionCount = any(),
					trackingFrameCount = any(),
					trackingFrameWakeLockNanos = any(),
					sourceTimerWakeupCount = any(),
					sourceTimerRequestCount = any(),
					motionPolicyChangeCount = any(),
					stationaryOptimizationCount = any(),
					fullFidelityRestoreCount = any(),
				)
			} just Runs

			recordCoordinatorSessionMetrics(METRICS)

			verify(exactly = 1) {
				TrackerDiagnosticLog.trackingCoordinatorSessionMetrics(
					projectionDrainCount = 1,
					projectedEventCount = 2,
					projectionDrainNanos = 3,
					planRevisionCount = 4,
					trackingFrameCount = 5,
					trackingFrameWakeLockNanos = 6,
					sourceTimerWakeupCount = 10,
					sourceTimerRequestCount = 11,
					motionPolicyChangeCount = 7,
					stationaryOptimizationCount = 8,
					fullFidelityRestoreCount = 9,
				)
			}
		} finally {
			unmockkObject(TrackerDiagnosticLog)
		}
	}

	private companion object {
		val METRICS = TrackingCoordinatorMetrics(
			projectionDrainCount = 1,
			projectedEventCount = 2,
			projectionDrainNanos = 3,
			planRevisionCount = 4,
			trackingFrameCount = 5,
			trackingFrameWakeLockNanos = 6,
			sourceTimerWakeupCount = 10,
			sourceTimerRequestCount = 11,
			motionPolicyChangeCount = 7,
			stationaryOptimizationCount = 8,
			fullFidelityRestoreCount = 9,
		)
	}
}
