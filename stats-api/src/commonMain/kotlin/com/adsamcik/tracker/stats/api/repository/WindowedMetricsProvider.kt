package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.metric.TimeWindow

/**
 * Collects metric values constrained to a time window.
 *
 * Differs from [AchievementMetricsProvider], which only returns cumulative totals: this
 * interface returns the value of [metric] over [window], suitable for windowed-achievement
 * progress evaluation (where an achievement is bounded by start/end timestamps, e.g.
 * "X distance in the last 7 days").
 *
 * Implementations MUST only query pre-aggregated tables (daily_summary, exploration_cell,
 * session_segment, …) and NEVER scan raw location_sample.
 *
 * Unknown metrics return 0. The caller is responsible for catalog validation.
 */
interface WindowedMetricsProvider {
	suspend fun collect(metric: String, window: TimeWindow): Long
}
