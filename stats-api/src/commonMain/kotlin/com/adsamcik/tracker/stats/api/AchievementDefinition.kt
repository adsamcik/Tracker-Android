package com.adsamcik.tracker.stats.api

/**
 * Static definition of an achievement, describing its category, display info,
 * metric binding, and tier thresholds.
 *
 * Definitions are immutable and stored in [com.adsamcik.tracker.stats.api.achievement.AchievementCatalog].
 * Runtime progress is tracked separately via [AchievementSnapshot].
 *
 * @property id Unique stable identifier, e.g. "explorer_cells". Used as DB key.
 * @property category Grouping category for UI organization.
 * @property titleRes String resource name for the achievement title.
 * @property descriptionRes String resource name for the achievement description.
 * @property metric Metric key this achievement tracks, e.g. "cells_discovered".
 *   Multiple achievements may share the same metric.
 * @property tiers Map from tier to target value. Must contain at least [AchievementTier.BRONZE].
 *   Values must be strictly increasing from BRONZE to DIAMOND.
 */
data class AchievementDefinition(
	val id: String,
	val category: AchievementCategory,
	val titleRes: String,
	val descriptionRes: String,
	val metric: String,
	val tiers: Map<AchievementTier, Long>,
)
