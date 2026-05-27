package com.adsamcik.tracker.stats.api.metric

/**
 * Time constraint for metric collection.
 *
 * [Cumulative] returns all-time totals. [Interval] captures a closed wall-clock range in
 * milliseconds. [Rolling] resolves to a moving range ending at "now".
 */
sealed interface TimeWindow {
	/** All-time cumulative. Equivalent to today's AchievementMetricsProvider behavior. */
	object Cumulative : TimeWindow

	/** Closed interval [startMs, endMs] (inclusive). */
	data class Interval(val startMs: Long, val endMs: Long) : TimeWindow

	/** Rolling window: from (now - durationMs) to now. */
	data class Rolling(val durationMs: Long) : TimeWindow
}
