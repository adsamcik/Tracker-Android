package com.adsamcik.tracker.tracker.policy

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier

/**
 * Maps between [PolicyTier] and the legacy [TrackingPolicy] enum.
 *
 * Bridge mapper — will be removed once all consumers use [PolicyTier] directly.
 *
 * Mapping:
 * - [PolicyTier.OFF] -> not applicable (service not running)
 * - [PolicyTier.AMBIENT] -> [TrackingPolicy.PASSIVE_LOW] (no GPS)
 * - [PolicyTier.ACTIVE] -> [TrackingPolicy.ACTIVE_MODERATE] (GPS with adaptive intervals)
 * - [PolicyTier.PRECISION] -> [TrackingPolicy.ACTIVE_ELEVATED] (max-rate GPS)
 */
internal object PolicyTierMapper {

	fun toTrackingPolicy(tier: PolicyTier): TrackingPolicy = when (tier) {
		PolicyTier.OFF -> TrackingPolicy.PASSIVE_LOW
		PolicyTier.AMBIENT -> TrackingPolicy.PASSIVE_LOW
		PolicyTier.ACTIVE -> TrackingPolicy.ACTIVE_MODERATE
		PolicyTier.PRECISION -> TrackingPolicy.ACTIVE_ELEVATED
	}

	fun toTier(policy: TrackingPolicy): PolicyTier = when (policy) {
		TrackingPolicy.PASSIVE_LOW -> PolicyTier.AMBIENT
		TrackingPolicy.MOVEMENT_SUSPECTED -> PolicyTier.AMBIENT
		TrackingPolicy.ACTIVE_MODERATE -> PolicyTier.ACTIVE
		TrackingPolicy.ACTIVE_ELEVATED -> PolicyTier.PRECISION
		TrackingPolicy.USER_INITIATED -> PolicyTier.PRECISION
	}

	/**
	 * Map Play Services activity type int to [DetectedActivityType].
	 *
	 * Activity type codes from com.google.android.gms.location.DetectedActivity:
	 * - 0 = IN_VEHICLE, 1 = ON_BICYCLE, 2 = ON_FOOT
	 * - 3 = STILL, 4 = UNKNOWN, 5 = TILTING
	 * - 7 = WALKING, 8 = RUNNING
	 */
	fun toDetectedActivityType(activityTypeCode: Int): DetectedActivityType = when (activityTypeCode) {
		0 -> DetectedActivityType.IN_VEHICLE
		1 -> DetectedActivityType.ON_BICYCLE
		2 -> DetectedActivityType.ON_FOOT
		3 -> DetectedActivityType.STILL
		5 -> DetectedActivityType.TILTING
		7 -> DetectedActivityType.WALKING
		8 -> DetectedActivityType.RUNNING
		else -> DetectedActivityType.UNKNOWN
	}
}
