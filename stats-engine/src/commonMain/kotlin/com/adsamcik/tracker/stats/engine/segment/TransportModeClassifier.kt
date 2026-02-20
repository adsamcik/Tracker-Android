package com.adsamcik.tracker.stats.engine.segment

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.TransportMode

/**
 * Rule-based transport mode classifier.
 *
 * Classifies a completed trip segment into a [TransportMode] based on
 * aggregate speed, step rate, distance, and activity recognition data.
 */
object TransportModeClassifier {

	private const val SECONDS_PER_MINUTE = 60.0f
	private const val MILLIS_PER_MINUTE = 60_000.0f

	// Step rate thresholds (steps/min)
	private const val WALK_STEP_RATE_MIN = 80f
	private const val RUN_STEP_RATE_MIN = 120f

	// Speed thresholds (m/s)
	private const val WALK_SPEED_MAX = 2.5f
	private const val RUN_SPEED_MAX = 6.0f
	private const val CYCLE_SPEED_MIN = 3.0f
	private const val CYCLE_SPEED_MAX = 12.0f
	private const val DRIVE_SPEED_MIN = 5.0f
	private const val HIGH_SPEED_RAIL_THRESHOLD = 50.0f

	/**
	 * Classify a trip segment into a transport mode.
	 *
	 * @param avgSpeedMps Average speed during the trip (m/s)
	 * @param maxSpeedMps Maximum speed observed during the trip (m/s)
	 * @param totalSteps Total step count during the trip
	 * @param durationMs Trip duration in milliseconds
	 * @param primaryActivity Most common detected activity type
	 * @param totalDistanceM Total distance traveled in meters
	 * @return Classified transport mode
	 */
	fun classify(
		avgSpeedMps: Float,
		maxSpeedMps: Float,
		totalSteps: Int,
		durationMs: Long,
		primaryActivity: DetectedActivityType?,
		totalDistanceM: Float,
	): TransportMode {
		if (durationMs <= 0) return TransportMode.UNKNOWN

		val durationMinutes = durationMs / MILLIS_PER_MINUTE
		val stepRate = if (durationMinutes > 0f) totalSteps / durationMinutes else 0f

		// Very high speed: rail or air
		if (maxSpeedMps > HIGH_SPEED_RAIL_THRESHOLD) {
			return TransportMode.HIGH_SPEED_RAIL
		}

		// Step-based classification: walking or running
		if (stepRate >= RUN_STEP_RATE_MIN && avgSpeedMps <= RUN_SPEED_MAX) {
			return TransportMode.RUN
		}
		if (stepRate >= WALK_STEP_RATE_MIN && avgSpeedMps <= WALK_SPEED_MAX) {
			return TransportMode.WALK
		}

		// Activity-based bicycle detection
		if (primaryActivity == DetectedActivityType.ON_BICYCLE &&
			avgSpeedMps in CYCLE_SPEED_MIN..CYCLE_SPEED_MAX
		) {
			return TransportMode.CYCLE
		}

		// Vehicle detection
		if (primaryActivity == DetectedActivityType.IN_VEHICLE) {
			return TransportMode.DRIVE
		}

		// Cycle speed range without steps (e.g., e-scooter, bike without step sensor)
		if (avgSpeedMps in CYCLE_SPEED_MIN..CYCLE_SPEED_MAX && totalSteps == 0) {
			return TransportMode.CYCLE
		}

		// Speed-based fallback for vehicle (beyond cycle range, or high speed with few steps)
		if (avgSpeedMps >= DRIVE_SPEED_MIN && totalSteps < (durationMinutes * WALK_STEP_RATE_MIN / 2).toInt()) {
			return TransportMode.DRIVE
		}

		// Moderate step rate with slow speed -> walking
		if (stepRate >= WALK_STEP_RATE_MIN / 2 && avgSpeedMps <= WALK_SPEED_MAX) {
			return TransportMode.WALK
		}

		return TransportMode.UNKNOWN
	}
}
