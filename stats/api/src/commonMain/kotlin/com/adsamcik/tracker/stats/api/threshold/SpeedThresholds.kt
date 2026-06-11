package com.adsamcik.tracker.stats.api.threshold

import com.adsamcik.tracker.stats.api.value.SpeedMps

/**
 * Single source of truth for speed-based classification thresholds.
 * Used by both the tracker pipeline and stats-engine classifiers.
 */
object SpeedThresholds {
	/** Maximum speed considered walking. */
	val MAX_WALK = SpeedMps(2.5f)

	/** Maximum speed considered running. */
	val MAX_RUN = SpeedMps(6.0f)

	/** Minimum speed for cycling classification. */
	val MIN_CYCLE = SpeedMps(3.0f)

	/** Maximum speed for cycling classification. */
	val MAX_CYCLE = SpeedMps(12.0f)

	/** Minimum speed that strongly indicates a vehicle. */
	val MIN_DEFINITE_VEHICLE = SpeedMps(15.0f)

	/** Maximum speed for on-foot activities (walk, run, hike). */
	val MAX_ON_FOOT = SpeedMps(4.5f)

	/** Speed below which the user is considered stationary. */
	val STILLNESS = SpeedMps(0.3f)

	/** High-speed rail threshold. */
	val MIN_HIGH_SPEED_RAIL = SpeedMps(50.0f)
}
