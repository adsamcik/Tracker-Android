package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.metric.MetricKey

/**
 * Metrics whose persisted achievement progress is currently safe to consume as product truth.
 *
 * Steps-derived metrics remain unavailable until retained source facts can qualify them. The XP
 * metrics are also unavailable because existing ledger/profile rows do not identify which portion
 * came from historical raw Steps goals or session scoring. Derived meta rows are excluded from
 * persisted presentation because older rows may already include either unavailable family.
 */
internal object AchievementMetricQualification {
	val derivedMetaMetrics = setOf(
		MetricKey.ACHIEVEMENTS_UNLOCKED,
		MetricKey.CATEGORIES_COMPLETED,
	)

	private val sourceUnverifiableMetrics = setOf(
		MetricKey.STEPS_TOTAL,
		MetricKey.BEST_DAILY_STEPS,
		MetricKey.PERFECT_WEEKS,
		MetricKey.GOAL_STREAK_DAYS,
		MetricKey.PLAYER_LEVEL,
		MetricKey.BEST_DAY_XP,
		MetricKey.XP_SOURCES_USED,
	)

	fun isAvailableForEvaluation(metric: MetricKey): Boolean = metric !in sourceUnverifiableMetrics

	fun isTrustedPersistedProgress(metric: MetricKey): Boolean =
		isAvailableForEvaluation(metric) && metric !in derivedMetaMetrics
}
