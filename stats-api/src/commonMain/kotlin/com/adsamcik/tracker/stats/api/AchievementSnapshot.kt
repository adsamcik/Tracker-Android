package com.adsamcik.tracker.stats.api

import com.adsamcik.tracker.stats.api.metric.MetricKey

data class AchievementSnapshot(
	val id: String,
	val nameRes: String,
	val descriptionRes: String,
	val category: AchievementCategory,
	val tier: AchievementTier,
	val metric: MetricKey,
	val currentValue: Double,
	val threshold: Double,
	val isUnlocked: Boolean,
	val updatedAt: Long,
) {
	val progress: Float get() = if (threshold <= 0.0) 0f else (currentValue / threshold).toFloat().coerceIn(0f, 1f)
}

data class AchievementSummary(val total: Int, val unlocked: Int, val inProgress: Int)
