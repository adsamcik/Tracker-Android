package com.adsamcik.tracker.tracker.pipeline

import com.adsamcik.tracker.tracker.data.collection.TrackingCycle

/**
 * Shared factory for creating [TrackingCycle] instances in tests.
 */
internal object TestCycleFactory {
	private const val DEFAULT_TIME_MS = 1_700_000_000_000L
	private const val DEFAULT_ELAPSED_NANOS = 5_000_000_000L

	fun minimal(
		timestampMs: Long = DEFAULT_TIME_MS,
		elapsedRealtimeNanos: Long = DEFAULT_ELAPSED_NANOS,
	): TrackingCycle = TrackingCycle(
		timestampMs = timestampMs,
		elapsedRealtimeNanos = elapsedRealtimeNanos,
	)
}
