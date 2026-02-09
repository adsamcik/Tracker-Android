package com.adsamcik.tracker.stats.api

/**
 * Achievement unlock tiers with increasing difficulty and reward.
 *
 * Each tier has a display label and a point bonus awarded on unlock.
 * Tiers are ordered from easiest ([BRONZE]) to hardest ([DIAMOND]).
 *
 * @property label Human-readable tier name for UI display.
 * @property pointBonus Points awarded when this tier is first unlocked.
 */
enum class AchievementTier(val label: String, val pointBonus: Int) {
	BRONZE("Bronze", 50),
	SILVER("Silver", 150),
	GOLD("Gold", 500),
	DIAMOND("Diamond", 2000),
}
