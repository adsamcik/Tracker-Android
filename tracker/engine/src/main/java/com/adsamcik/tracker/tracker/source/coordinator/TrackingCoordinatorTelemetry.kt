package com.adsamcik.tracker.tracker.source.coordinator

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Low-overhead counters for the event-owned tracking pipeline and device gates. */
@Singleton
class TrackingCoordinatorTelemetry @Inject constructor() {
	private val projectionDrainCount = AtomicLong()
	private val projectedEventCount = AtomicLong()
	private val projectionDrainNanos = AtomicLong()
	private val planRevisionCount = AtomicLong()
	private val trackingFrameCount = AtomicLong()
	private val trackingFrameWakeLockNanos = AtomicLong()
	private val sourceTimerWakeupCount = AtomicLong()
	private val sourceTimerRequestCount = AtomicLong()
	private val motionPolicyChangeCount = AtomicLong()
	private val stationaryOptimizationCount = AtomicLong()
	private val fullFidelityRestoreCount = AtomicLong()
	private val mutableMetrics = MutableStateFlow(TrackingCoordinatorMetrics.ZERO)
	val metrics: StateFlow<TrackingCoordinatorMetrics> = mutableMetrics.asStateFlow()

	@Synchronized
	fun recordProjectionDrain(events: Int, durationNanos: Long) {
		projectionDrainCount.incrementAndGet()
		projectedEventCount.addAndGet(events.toLong())
		projectionDrainNanos.addAndGet(durationNanos.coerceAtLeast(0L))
		publish()
	}

	@Synchronized
	fun recordPlanRevision() {
		planRevisionCount.incrementAndGet()
		publish()
	}

	@Synchronized
	fun recordTrackingFrame(wakeLockNanos: Long) {
		trackingFrameCount.incrementAndGet()
		trackingFrameWakeLockNanos.addAndGet(wakeLockNanos.coerceAtLeast(0L))
		publish()
	}

	/** Records one fired in-process source timer and the requests coalesced into that firing. */
	@Synchronized
	fun recordSourceTimerWakeup(requests: Int) {
		require(requests > 0)
		sourceTimerWakeupCount.incrementAndGet()
		sourceTimerRequestCount.addAndGet(requests.toLong())
		publish()
	}

	@Synchronized
	fun recordMotionPolicyChange(stationaryOptimized: Boolean, fullFidelity: Boolean) {
		motionPolicyChangeCount.incrementAndGet()
		if (stationaryOptimized) stationaryOptimizationCount.incrementAndGet()
		if (fullFidelity) fullFidelityRestoreCount.incrementAndGet()
		publish()
	}

	fun snapshot(): TrackingCoordinatorMetrics = TrackingCoordinatorMetrics(
		projectionDrainCount = projectionDrainCount.get(),
		projectedEventCount = projectedEventCount.get(),
		projectionDrainNanos = projectionDrainNanos.get(),
		planRevisionCount = planRevisionCount.get(),
		trackingFrameCount = trackingFrameCount.get(),
		trackingFrameWakeLockNanos = trackingFrameWakeLockNanos.get(),
		sourceTimerWakeupCount = sourceTimerWakeupCount.get(),
		sourceTimerRequestCount = sourceTimerRequestCount.get(),
		motionPolicyChangeCount = motionPolicyChangeCount.get(),
		stationaryOptimizationCount = stationaryOptimizationCount.get(),
		fullFidelityRestoreCount = fullFidelityRestoreCount.get(),
	)

	private fun publish() {
		mutableMetrics.value = snapshot()
	}
}

data class TrackingCoordinatorMetrics(
	val projectionDrainCount: Long,
	val projectedEventCount: Long,
	val projectionDrainNanos: Long,
	val planRevisionCount: Long,
	val trackingFrameCount: Long,
	val trackingFrameWakeLockNanos: Long,
	val sourceTimerWakeupCount: Long = 0L,
	val sourceTimerRequestCount: Long = 0L,
	val motionPolicyChangeCount: Long = 0L,
	val stationaryOptimizationCount: Long = 0L,
	val fullFidelityRestoreCount: Long = 0L,
) {
	operator fun minus(baseline: TrackingCoordinatorMetrics) = TrackingCoordinatorMetrics(
		projectionDrainCount = projectionDrainCount - baseline.projectionDrainCount,
		projectedEventCount = projectedEventCount - baseline.projectedEventCount,
		projectionDrainNanos = projectionDrainNanos - baseline.projectionDrainNanos,
		planRevisionCount = planRevisionCount - baseline.planRevisionCount,
		trackingFrameCount = trackingFrameCount - baseline.trackingFrameCount,
		trackingFrameWakeLockNanos = trackingFrameWakeLockNanos - baseline.trackingFrameWakeLockNanos,
		sourceTimerWakeupCount = sourceTimerWakeupCount - baseline.sourceTimerWakeupCount,
		sourceTimerRequestCount = sourceTimerRequestCount - baseline.sourceTimerRequestCount,
		motionPolicyChangeCount = motionPolicyChangeCount - baseline.motionPolicyChangeCount,
		stationaryOptimizationCount = stationaryOptimizationCount - baseline.stationaryOptimizationCount,
		fullFidelityRestoreCount = fullFidelityRestoreCount - baseline.fullFidelityRestoreCount,
	)

	companion object {
		val ZERO = TrackingCoordinatorMetrics(
			projectionDrainCount = 0L,
			projectedEventCount = 0L,
			projectionDrainNanos = 0L,
			planRevisionCount = 0L,
			trackingFrameCount = 0L,
			trackingFrameWakeLockNanos = 0L,
		)
	}
}
