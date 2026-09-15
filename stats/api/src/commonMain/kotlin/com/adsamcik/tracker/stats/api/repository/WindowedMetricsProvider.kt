package com.adsamcik.tracker.stats.api.repository

import arrow.core.Either
import com.adsamcik.tracker.stats.api.error.StatsError
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
 * Steps metrics return [StatsError.ValidationError]: legacy aggregates do not establish complete
 * source-qualified Steps. Callers needing Steps must use [StepsNumericSummaryRepository].
 * Other unknown metrics retain the compatibility value 0; callers validate their catalog.
 */
interface WindowedMetricsProvider {
	/** Returns an independent aggregate or a typed rejection when the metric needs source qualification. */
	suspend fun collect(metric: String, window: TimeWindow): Either<StatsError, Long>
}
