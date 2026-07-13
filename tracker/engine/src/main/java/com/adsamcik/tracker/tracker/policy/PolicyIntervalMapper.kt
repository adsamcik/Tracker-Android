package com.adsamcik.tracker.tracker.policy

import com.adsamcik.tracker.shared.base.Time

import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
/**
 * Maps tracking policy levels to collection intervals.
 *
 * Interval Strategy:
 * - PASSIVE_LOW: 5 minutes (300s) - Minimal battery impact, detect long-term patterns
 * - MOVEMENT_SUSPECTED: 2 minutes (120s) - Moderate sampling, confirm movement
 * - GPS-capable policies: the user's selected cadence. Policy chooses whether GPS runs; it must
 *   not silently replace an explicit fidelity setting once GPS is active.
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
	fun getIntervalMs(
		policy: TrackingPolicy,
		params: TrackingParamsState = TrackingParamsState(),
	): Long {
		return when (policy) {
			TrackingPolicy.PASSIVE_LOW -> 5 * Time.MINUTE_IN_MILLISECONDS
			TrackingPolicy.MOVEMENT_SUSPECTED -> 2 * Time.MINUTE_IN_MILLISECONDS
			TrackingPolicy.ACTIVE_MODERATE,
			TrackingPolicy.ACTIVE_ELEVATED,
			TrackingPolicy.USER_INITIATED,
			-> params.minTimeSeconds.coerceAtLeast(1) * Time.SECOND_IN_MILLISECONDS
		}
	}

	/**
	 * Get tracking interval in seconds for the given policy level.
	 *
	 * @param policy Current tracking policy
	 * @return Interval in seconds
	 */
	fun getIntervalSeconds(
		policy: TrackingPolicy,
		params: TrackingParamsState = TrackingParamsState(),
	): Int {
		return (getIntervalMs(policy, params) / Time.SECOND_IN_MILLISECONDS).toInt()
	}

	/**
	 * Get minimum distance threshold for location updates based on policy.
	 * More frequent updates use smaller distance thresholds.
	 *
	 * @param policy Current tracking policy
	 * @return Minimum distance in meters
	 */
	fun getMinDistanceMeters(
		policy: TrackingPolicy,
		params: TrackingParamsState = TrackingParamsState(),
	): Int {
		return when (policy) {
			TrackingPolicy.PASSIVE_LOW -> 50 // Large threshold for passive tracking
			TrackingPolicy.MOVEMENT_SUSPECTED -> 30 // Moderate threshold
			TrackingPolicy.ACTIVE_MODERATE,
			TrackingPolicy.ACTIVE_ELEVATED,
			TrackingPolicy.USER_INITIATED,
			-> params.minDistanceMeters.coerceAtLeast(1)
		}
	}
}
