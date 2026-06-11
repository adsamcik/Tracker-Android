package com.adsamcik.tracker.stats.api

/**
 * Represents a single tier unlock event for an achievement.
 *
 * Used when evaluating tier-skips (e.g., jumping from no tier to GOLD)
 * to emit individual unlock events for each crossed tier.
 *
 * @property achievementId The achievement that was unlocked.
 * @property tier The specific tier that was crossed.
 */
data class TierUnlock(
	val achievementId: String,
	val tier: AchievementTier,
)
