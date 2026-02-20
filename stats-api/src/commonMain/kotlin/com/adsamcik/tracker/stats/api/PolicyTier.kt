package com.adsamcik.tracker.stats.api

/**
 * Conceptual tracking tier that groups TrackingPolicy values into
 * battery/capability levels.
 *
 * Each tier determines which sensors are active and the expected
 * battery impact. The existing TrackingPolicy enum maps into these tiers:
 *
 * - [OFF]: Service not running. No data collection.
 * - [AMBIENT]: Steps + activity recognition only. No GPS. (~1-2%/day)
 * - [ACTIVE]: GPS at adaptive intervals + all sensors. (~5-15%/day)
 * - [PRECISION]: GPS at max rate + all sensors. (~25-30%/day)
 */
enum class PolicyTier {
	OFF,
	AMBIENT,
	ACTIVE,
	PRECISION;

	val isGpsEnabled: Boolean
		get() = this >= ACTIVE

	val isSensorEnabled: Boolean
		get() = this >= AMBIENT
}
