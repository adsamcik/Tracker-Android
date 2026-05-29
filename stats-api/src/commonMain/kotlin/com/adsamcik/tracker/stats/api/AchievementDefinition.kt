package com.adsamcik.tracker.stats.api

import com.adsamcik.tracker.stats.api.metric.MetricKey

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
) {
	val titleRes: String get() = nameRes
	val targetValue: Long get() = threshold.toLong()
	fun dependsOnAnyOf(changedMetrics: Set<MetricKey>): Boolean = dependsOn.any { it in changedMetrics }
}
