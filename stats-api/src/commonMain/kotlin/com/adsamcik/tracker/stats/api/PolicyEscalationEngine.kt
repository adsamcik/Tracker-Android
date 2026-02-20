package com.adsamcik.tracker.stats.api

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for the policy escalation engine that manages transitions
 * between tracking tiers based on sensor signals.
 *
 * Implementations process activity recognition events, step counts,
 * and significant motion signals through a weighted confidence accumulator
 * to determine the appropriate tracking tier.
 */
interface PolicyEscalationEngine {

	/**
	 * Observable current policy state. Emits on every tier transition.
	 */
	val policyState: StateFlow<PolicyState>

	/**
	 * Current tier shortcut.
	 */
	val currentTier: PolicyTier
		get() = policyState.value.tier

	/**
	 * Report a detected activity event.
	 *
	 * @param activity The detected activity type
	 * @param confidence Confidence level 0-100
	 * @param timestampMs Event timestamp (epoch millis)
	 */
	fun onActivityDetected(
		activity: DetectedActivityType,
		confidence: Int,
		timestampMs: Long,
	)

	/**
	 * Report step counter update.
	 *
	 * @param stepCount Cumulative step count since boot
	 * @param timestampMs Event timestamp (epoch millis)
	 */
	fun onStepCount(stepCount: Long, timestampMs: Long)

	/**
	 * Report significant motion sensor trigger.
	 *
	 * @param timestampMs Event timestamp (epoch millis)
	 */
	fun onSignificantMotion(timestampMs: Long)

	/**
	 * Set a minimum tier lock. The engine will not de-escalate below
	 * this tier until [clearMinimumTier] is called.
	 *
	 * Used by trip inference to prevent GPS de-escalation during active trips.
	 *
	 * @param tier Minimum tier to maintain
	 * @param reason Why the lock is being set
	 */
	fun setMinimumTier(tier: PolicyTier, reason: String)

	/**
	 * Clear the minimum tier lock, allowing normal de-escalation.
	 */
	fun clearMinimumTier()

	/**
	 * Force a specific tier (e.g., user manually selects PRECISION).
	 *
	 * @param tier Target tier
	 * @param reason Why the override is being applied
	 */
	fun overrideTier(tier: PolicyTier, reason: String)

	/**
	 * Start the engine. Begins processing signals and managing tiers.
	 *
	 * @param initialTier Starting tier
	 * @param timestampMs Start timestamp
	 */
	fun start(initialTier: PolicyTier, timestampMs: Long)

	/**
	 * Stop the engine. Ceases all processing.
	 */
	fun stop()
}
