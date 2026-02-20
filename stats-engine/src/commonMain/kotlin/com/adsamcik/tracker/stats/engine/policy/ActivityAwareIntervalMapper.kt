package com.adsamcik.tracker.stats.engine.policy

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier

/**
 * Maps detected activity type and speed to optimal GPS polling intervals.
 *
 * Within the ACTIVE tier, GPS intervals adapt to the current movement mode:
 * - Walking: 25s (slow, steady, battery-conscious)
 * - Running: 8-15s (faster movement, need more points)
 * - Cycling: 6-12s (variable speed)
 * - Vehicle: 5-8s (high speed, fewer points needed per km)
 *
 * Speed refinement adjusts intervals within the range for each mode.
 */
object ActivityAwareIntervalMapper {

	/**
	 * Get GPS interval for the given tier and activity.
	 *
	 * @param tier Current policy tier
	 * @param activity Detected activity type
	 * @param speedMps Current speed in meters/second (null if unknown)
	 * @return GPS polling interval in milliseconds
	 */
	fun getInterval(
		tier: PolicyTier,
		activity: DetectedActivityType = DetectedActivityType.UNKNOWN,
		speedMps: Float? = null,
	): Long = when (tier) {
		PolicyTier.OFF -> Long.MAX_VALUE
		PolicyTier.AMBIENT -> Long.MAX_VALUE // No GPS in ambient
		PolicyTier.ACTIVE -> activeInterval(activity, speedMps)
		PolicyTier.PRECISION -> PRECISION_INTERVAL_MS
	}

	private fun activeInterval(
		activity: DetectedActivityType,
		speedMps: Float?,
	): Long {
		val range = intervalRange(activity)

		if (speedMps == null || speedMps <= 0f) {
			return range.default
		}

		// Speed-based refinement within the range
		return refineBySpeed(range, speedMps)
	}

	private fun refineBySpeed(range: IntervalRange, speedMps: Float): Long {
		// Higher speed → shorter interval (more frequent GPS)
		val speedKmh = speedMps * 3.6f
		val fraction = when {
			speedKmh >= range.highSpeedKmh -> 0.0  // Use minimum interval
			speedKmh <= range.lowSpeedKmh -> 1.0    // Use maximum interval
			else -> {
				// Linear interpolation
				val span = range.highSpeedKmh - range.lowSpeedKmh
				1.0 - ((speedKmh - range.lowSpeedKmh) / span)
			}
		}

		val intervalRange = range.maxMs - range.minMs
		return (range.minMs + (intervalRange * fraction)).toLong()
	}

	private fun intervalRange(activity: DetectedActivityType): IntervalRange =
		when (activity) {
			DetectedActivityType.WALKING, DetectedActivityType.ON_FOOT ->
				IntervalRange(
					minMs = 20_000L, maxMs = 25_000L, default = 25_000L,
					lowSpeedKmh = 3f, highSpeedKmh = 7f
				)

			DetectedActivityType.RUNNING ->
				IntervalRange(
					minMs = 8_000L, maxMs = 15_000L, default = 10_000L,
					lowSpeedKmh = 6f, highSpeedKmh = 18f
				)

			DetectedActivityType.ON_BICYCLE ->
				IntervalRange(
					minMs = 6_000L, maxMs = 12_000L, default = 8_000L,
					lowSpeedKmh = 10f, highSpeedKmh = 40f
				)

			DetectedActivityType.IN_VEHICLE ->
				IntervalRange(
					minMs = 5_000L, maxMs = 8_000L, default = 5_000L,
					lowSpeedKmh = 20f, highSpeedKmh = 120f
				)

			else ->
				IntervalRange(
					minMs = 10_000L, maxMs = 20_000L, default = 15_000L,
					lowSpeedKmh = 3f, highSpeedKmh = 50f
				)
		}

	private data class IntervalRange(
		val minMs: Long,
		val maxMs: Long,
		val default: Long,
		val lowSpeedKmh: Float,
		val highSpeedKmh: Float,
	)

	/** PRECISION tier uses the fastest fixed rate. */
	private const val PRECISION_INTERVAL_MS = 3_000L
}
