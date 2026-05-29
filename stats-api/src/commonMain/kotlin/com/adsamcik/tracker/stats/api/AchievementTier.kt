package com.adsamcik.tracker.stats.api

enum class AchievementTier(val label: String, val pointBonus: Int) {
	BRONZE("Bronze", 50),
	SILVER("Silver", 150),
	GOLD("Gold", 500),
	DIAMOND("Diamond", 2_000),
	MYTHIC("Mythic", 5_000),
}
