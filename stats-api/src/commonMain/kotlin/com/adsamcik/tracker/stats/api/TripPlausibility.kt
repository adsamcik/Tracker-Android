package com.adsamcik.tracker.stats.api

/**
 * Result of evaluating a trip's distance plausibility.
 * Used to flag GPS glitch artifacts without deleting data.
 */
sealed class PlausibilityResult {
	/** The trip's average speed is within reasonable bounds for its activity type. */
	data object Plausible : PlausibilityResult()

	/**
	 * The trip's average speed exceeds the maximum plausible threshold,
	 * indicating likely GPS glitch artifacts.
	 *
	 * @param averageSpeedMps Computed average speed in meters per second
	 * @param thresholdMps The maximum plausible speed for the detected activity type
	 */
	data class Implausible(
		val averageSpeedMps: Float,
		val thresholdMps: Float,
	) : PlausibilityResult()
}

/**
 * Evaluates whether a trip's recorded distance is physically plausible
 * given its duration and activity type.
 *
 * This does NOT discard data — it flags sessions that likely contain
 * GPS glitch artifacts so the UI can visually indicate them.
 *
 * Speed thresholds per activity type (generous upper bounds):
 * - Walking/OnFoot: 15 km/h (4.17 m/s)
 * - Running: 45 km/h (12.5 m/s)
 * - Cycling: 120 km/h (33.3 m/s)
 * - Vehicle: 350 km/h (97.2 m/s)
 * - Unknown/null: 350 km/h (vehicle default — most generous)
 */
object TripPlausibility {

	// Max plausible speeds in m/s per activity type
	private const val MAX_WALKING_MPS = 15f / 3.6f    // 15 km/h
	private const val MAX_RUNNING_MPS = 45f / 3.6f    // 45 km/h
	private const val MAX_CYCLING_MPS = 120f / 3.6f   // 120 km/h
	private const val MAX_VEHICLE_MPS = 350f / 3.6f   // 350 km/h
	private const val MAX_DEFAULT_MPS = MAX_VEHICLE_MPS

	/**
	 * Returns the maximum plausible speed in m/s for the given activity type.
	 */
	fun maxSpeedMpsFor(activityType: DetectedActivityType?): Float = when (activityType) {
		DetectedActivityType.WALKING, DetectedActivityType.ON_FOOT -> MAX_WALKING_MPS
		DetectedActivityType.RUNNING -> MAX_RUNNING_MPS
		DetectedActivityType.ON_BICYCLE -> MAX_CYCLING_MPS
		DetectedActivityType.IN_VEHICLE -> MAX_VEHICLE_MPS
		else -> MAX_DEFAULT_MPS
	}

	/**
	 * Evaluate whether a trip's distance is physically plausible.
	 *
	 * @param distanceM Total distance in meters
	 * @param durationMs Total duration in milliseconds
	 * @param activityType Detected activity type (null = unknown)
	 * @return [PlausibilityResult.Plausible] or [PlausibilityResult.Implausible]
	 */
	fun evaluate(
		distanceM: Float,
		durationMs: Long,
		activityType: DetectedActivityType?,
	): PlausibilityResult {
		// No movement or invalid distance → plausible (nothing to flag)
		if (distanceM <= 0f || distanceM.isNaN()) return PlausibilityResult.Plausible

		// Infinite distance is always implausible
		if (distanceM.isInfinite()) {
			return PlausibilityResult.Implausible(
				averageSpeedMps = Float.POSITIVE_INFINITY,
				thresholdMps = maxSpeedMpsFor(activityType),
			)
		}

		// Zero or negative duration with positive distance → implausible
		if (durationMs <= 0L) {
			return PlausibilityResult.Implausible(
				averageSpeedMps = Float.POSITIVE_INFINITY,
				thresholdMps = maxSpeedMpsFor(activityType),
			)
		}

		val durationSeconds = durationMs / 1000f
		val averageSpeedMps = distanceM / durationSeconds
		val threshold = maxSpeedMpsFor(activityType)

		return if (averageSpeedMps > threshold) {
			PlausibilityResult.Implausible(
				averageSpeedMps = averageSpeedMps,
				thresholdMps = threshold,
			)
		} else {
			PlausibilityResult.Plausible
		}
	}
}
