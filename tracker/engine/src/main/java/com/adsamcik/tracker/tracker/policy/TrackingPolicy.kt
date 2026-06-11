package com.adsamcik.tracker.tracker.policy

/**
 * Tracking policy states for adaptive location collection.
 * Determines when to request precise GPS vs operating with location-less data.
 */
enum class TrackingPolicy {
	/**
	 * Minimal collection: steps, activity, cell, wifi only.
	 * No GPS requested. Battery-efficient baseline.
	 */
	PASSIVE_LOW,

	/**
	 * Movement suspected based on steps/activity.
	 * Attempting coarse location (network/cell) periodically.
	 */
	MOVEMENT_SUSPECTED,

	/**
	 * Active movement detected with moderate confidence.
	 * GPS requested at reduced interval (e.g., every 30-60s).
	 */
	ACTIVE_MODERATE,

	/**
	 * High-confidence movement or user-initiated session.
	 * GPS requested at full interval (e.g., every 5-15s).
	 */
	ACTIVE_ELEVATED,

	/**
	 * User explicitly started a session.
	 * Always requests GPS regardless of heuristics.
	 */
	USER_INITIATED
}

/**
 * Policy transition trigger reasons (for debugging/analytics).
 */
enum class PolicyTransitionReason {
	/** Step rate exceeded threshold */
	STEP_RATE_THRESHOLD,
	/** Activity transition detected (e.g., STILL → WALKING) */
	ACTIVITY_TRANSITION,
	/** Significant location change */
	LOCATION_CHANGE,
	/** Movement cooldown expired */
	MOVEMENT_COOLDOWN,
	/** User started session explicitly */
	USER_START,
	/** User stopped session explicitly */
	USER_STOP,
	/** Tracking service starting */
	SERVICE_START,
	/** Tracking service stopping */
	SERVICE_STOP,
	/** Fallback to default policy */
	FALLBACK
}
