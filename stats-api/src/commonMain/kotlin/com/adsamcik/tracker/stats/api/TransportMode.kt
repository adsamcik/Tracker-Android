package com.adsamcik.tracker.stats.api

/**
 * Transport modes for trip inference. Classified by rule-based scoring
 * using speed, step count, activity recognition, and stop patterns.
 */
enum class TransportMode {
	WALK,
	RUN,
	CYCLE,
	DRIVE,
	TRANSIT,
	HIGH_SPEED_RAIL,
	AIR,
	UNKNOWN;

	/**
	 * Typical GPS interval for this mode in the ACTIVE tier (milliseconds).
	 */
	val typicalGpsIntervalMs: Long
		get() = when (this) {
			WALK -> 25_000L
			RUN -> 10_000L
			CYCLE -> 8_000L
			DRIVE -> 5_000L
			TRANSIT -> 10_000L
			HIGH_SPEED_RAIL -> 8_000L
			AIR -> 30_000L
			UNKNOWN -> 15_000L
		}

	/**
	 * Whether this is a human-powered mode.
	 */
	val isHumanPowered: Boolean
		get() = when (this) {
			WALK, RUN, CYCLE -> true
			else -> false
		}
}
