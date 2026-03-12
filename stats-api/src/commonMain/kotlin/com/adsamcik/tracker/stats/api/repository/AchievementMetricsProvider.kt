package com.adsamcik.tracker.stats.api.repository

/**
 * Collects pre-aggregated metrics for achievement evaluation.
 *
 * Each metric key corresponds to an [com.adsamcik.tracker.stats.api.AchievementDefinition.metric].
 * Implementations MUST only query pre-aggregated tables (daily_summary, exploration_cell, etc.)
 * and NEVER scan raw location_sample data.
 */
interface AchievementMetricsProvider {
	/**
	 * Collect all current metric values.
	 *
	 * @return Map of metric key to current cumulative value.
	 *   Missing keys are treated as 0 by the evaluator.
	 */
	suspend fun collect(): Map<String, Long>
}
