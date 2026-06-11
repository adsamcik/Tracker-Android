package com.adsamcik.tracker.tracker.policy

import com.adsamcik.tracker.shared.base.Time

/**
 * Maps tracking policy levels to collection intervals.
 *
 * Interval Strategy:
 * - PASSIVE_LOW: 5 minutes (300s) - Minimal battery impact, detect long-term patterns
 * - MOVEMENT_SUSPECTED: 2 minutes (120s) - Moderate sampling, confirm movement
 * - ACTIVE_MODERATE: 30 seconds - Frequent updates for walking/cycling
 * - ACTIVE_ELEVATED: 10 seconds - High-frequency for running/active sports
 * - USER_INITIATED: 10 seconds - Manual tracking expects detailed data
 *
 * Rationale:
 * - Passive states use longer intervals to conserve battery while still detecting activity changes
 * - Active states use shorter intervals to capture detailed movement patterns
 * - User-initiated sessions prioritize data quality over battery optimization
 */
internal object PolicyIntervalMapper {
	/**
	 * Get tracking interval in milliseconds for the given policy level.
	 *
	 * @param policy Current tracking policy
	 * @return Interval in milliseconds
	 */
	fun getIntervalMs(policy: TrackingPolicy): Long {
		return when (policy) {
			TrackingPolicy.PASSIVE_LOW -> 5 * Time.MINUTE_IN_MILLISECONDS
			TrackingPolicy.MOVEMENT_SUSPECTED -> 2 * Time.MINUTE_IN_MILLISECONDS
			TrackingPolicy.ACTIVE_MODERATE -> 30 * Time.SECOND_IN_MILLISECONDS
			TrackingPolicy.ACTIVE_ELEVATED -> 10 * Time.SECOND_IN_MILLISECONDS
			TrackingPolicy.USER_INITIATED -> 10 * Time.SECOND_IN_MILLISECONDS
		}
	}

	/**
	 * Get tracking interval in seconds for the given policy level.
	 *
	 * @param policy Current tracking policy
	 * @return Interval in seconds
	 */
	fun getIntervalSeconds(policy: TrackingPolicy): Int {
		return (getIntervalMs(policy) / Time.SECOND_IN_MILLISECONDS).toInt()
	}

	/**
	 * Get minimum distance threshold for location updates based on policy.
	 * More frequent updates use smaller distance thresholds.
	 *
	 * @param policy Current tracking policy
	 * @return Minimum distance in meters
	 */
	fun getMinDistanceMeters(policy: TrackingPolicy): Int {
		return when (policy) {
			TrackingPolicy.PASSIVE_LOW -> 50 // Large threshold for passive tracking
			TrackingPolicy.MOVEMENT_SUSPECTED -> 30 // Moderate threshold
			TrackingPolicy.ACTIVE_MODERATE -> 15 // Smaller threshold for active
			TrackingPolicy.ACTIVE_ELEVATED -> 10 // Precise tracking
			TrackingPolicy.USER_INITIATED -> 10 // Precise tracking
		}
	}
}
