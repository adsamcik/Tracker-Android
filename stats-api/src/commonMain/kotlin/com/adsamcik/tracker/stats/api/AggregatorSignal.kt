package com.adsamcik.tracker.stats.api

/**
 * Per-cycle input signal for the StreamingAggregator.
 * Built from CollectionData/CollectionTempData in the tracker module.
 *
 * @param timestampMs Wall clock time for this cycle (epoch millis)
 * @param distanceDeltaM Distance traveled since last cycle (null if no GPS)
 * @param speedMps Current speed in m/s (null if unavailable)
 * @param stepDelta Steps taken since last cycle
 * @param activityType Current detected activity type (null if unavailable)
 * @param activityConfidence Activity recognition confidence 0-100 (null if unavailable)
 */
data class AggregatorSignal(
	val timestampMs: Long,
	val distanceDeltaM: Float? = null,
	val speedMps: Float? = null,
	val stepDelta: Int = 0,
	val activityType: DetectedActivityType? = null,
	val activityConfidence: Int? = null,
)
