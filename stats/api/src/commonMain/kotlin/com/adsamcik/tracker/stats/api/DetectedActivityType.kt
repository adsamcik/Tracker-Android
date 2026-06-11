package com.adsamcik.tracker.stats.api

/**
 * Activity types detectable by the system. Maps to Google Play Services
 * DetectedActivity types but decoupled for testability.
 */
enum class DetectedActivityType {
	STILL,
	WALKING,
	RUNNING,
	ON_BICYCLE,
	IN_VEHICLE,
	ON_FOOT,
	TILTING,
	UNKNOWN;

	/**
	 * Whether this activity indicates the user is likely in motion.
	 */
	val isMoving: Boolean
		get() = when (this) {
			WALKING, RUNNING, ON_BICYCLE, IN_VEHICLE, ON_FOOT -> true
			else -> false
		}

	/**
	 * Whether this activity indicates locomotion (excludes vehicle).
	 */
	val isLocomotion: Boolean
		get() = when (this) {
			WALKING, RUNNING, ON_BICYCLE, ON_FOOT -> true
			else -> false
		}
}
