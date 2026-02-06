package com.adsamcik.tracker.stats.api

/**
 * Immutable snapshot of the current policy engine state.
 *
 * @property tier Current conceptual tier (OFF/AMBIENT/ACTIVE/PRECISION)
 * @property transitionReason Why the current tier was entered
 * @property accumulatorValue Current movement confidence score (0-200)
 * @property detectedActivity Most recently detected activity type
 * @property gpsIntervalMs Current GPS polling interval (null if GPS disabled)
 * @property minimumTierLock If set, the tier cannot drop below this level
 *   (used by trip inference to prevent de-escalation during active trips)
 * @property tierEntryTimeMs Epoch millis when the current tier was entered
 */
data class PolicyState(
	val tier: PolicyTier,
	val transitionReason: TransitionReason,
	val accumulatorValue: Double = 0.0,
	val detectedActivity: DetectedActivityType = DetectedActivityType.UNKNOWN,
	val gpsIntervalMs: Long? = null,
	val minimumTierLock: PolicyTier? = null,
	val tierEntryTimeMs: Long = 0L,
) {
	/**
	 * Reasons a tier transition occurred.
	 */
	enum class TransitionReason {
		/** Initial state at service start */
		INITIALIZATION,

		/** Confidence accumulator crossed upward threshold */
		ACCUMULATOR_ESCALATION,

		/** Sustained stillness triggered de-escalation */
		STILLNESS_DE_ESCALATION,

		/** User manually requested a specific tier */
		USER_INITIATED,

		/** Trip inference engine locked a minimum tier */
		TRIP_LOCK,

		/** Trip inference engine released the minimum tier */
		TRIP_UNLOCK,

		/** System constraint (battery saver, doze) forced a change */
		SYSTEM_CONSTRAINT,
	}
}
