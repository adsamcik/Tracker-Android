package com.adsamcik.tracker.stats.api

import com.adsamcik.tracker.stats.api.metric.MetricKey

/**
 * Catalog entry for one achievement tier (e.g. "10 km on foot bronze").
 *
 * Note on [nameRes] / [descriptionRes]:
 *
 * These fields are **string-resource KEY names** (e.g. "achievement_distance_total_bronze_title"),
 * not Android `@StringRes Int` IDs. They are stable identifiers that survive across modules and
 * are resolved at the UI edge — typically by [com.adsamcik.tracker.game.ui.achievement.AchievementFormatting]
 * which derives final titles/descriptions programmatically from [metric] + [threshold] rather than
 * looking up each key as an Android string resource. Tests assert the naming convention
 * (see `AchievementCatalogTest.resourceNamesAreStable`).
 *
 * The fields keep the `*Res` suffix for historical / persistence-schema reasons even though
 * they are not `Int` resource IDs.
 */
data class AchievementDefinition(
	val id: String,
	val category: AchievementCategory,
	val nameRes: String,
	val descriptionRes: String,
	val metric: MetricKey,
	val threshold: Double,
	val tier: AchievementTier,
	val tierIndex: Int,
	val isCompound: Boolean = false,
	val dependsOn: Set<MetricKey> = setOf(metric),
	/** Minimum distinct tracking days required before this tier can unlock. */
	val minimumActiveDays: Int = 1,
	/** Day counter used by [minimumActiveDays]; total days by default, mode days when appropriate. */
	val pacingMetric: MetricKey = MetricKey.ACTIVE_DAYS_TOTAL,
) {
	val targetValue: Long get() = threshold.toLong()
	fun dependsOnAnyOf(changedMetrics: Set<MetricKey>): Boolean = dependsOn.any { it in changedMetrics }
	fun isEligible(activeDays: Long): Boolean = activeDays >= minimumActiveDays
}
