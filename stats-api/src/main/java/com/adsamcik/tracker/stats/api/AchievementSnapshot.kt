package com.adsamcik.tracker.stats.api

/**
 * Runtime snapshot of a user's progress toward an achievement.
 *
 * Computed by evaluating the current metric value against the
 * [AchievementDefinition.tiers] thresholds.
 *
 * @property definition The achievement definition this snapshot tracks.
 * @property currentValue Current metric value (e.g. total cells discovered).
 * @property currentTier Highest tier the user has unlocked, or null if no tier reached yet.
 * @property nextTier Next tier to unlock, or null if the achievement is fully completed.
 * @property nextTierTarget Target value for the next tier, or null if maxed out.
 * @property progress Fractional progress toward the next tier, in range [0.0, 1.0].
 *   Returns 1.0 if the achievement is fully completed (no next tier).
 *   Returns progress from 0 (or previous tier target) to next tier target otherwise.
 */
data class AchievementSnapshot(
	val definition: AchievementDefinition,
	val currentValue: Long,
	val currentTier: AchievementTier?,
	val nextTier: AchievementTier?,
	val nextTierTarget: Long?,
	val progress: Float,
)
