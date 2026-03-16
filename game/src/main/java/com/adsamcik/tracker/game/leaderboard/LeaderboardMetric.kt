package com.adsamcik.tracker.game.leaderboard

import com.adsamcik.tracker.game.R

/**
 * Metrics available for ghost leaderboard comparison.
 * Each metric maps to a field in DailySummaryEntity.
 */
enum class LeaderboardMetric(val labelRes: Int, val unitRes: Int) {
	DISTANCE(R.string.leaderboard_metric_distance, R.string.leaderboard_unit_km),
	STEPS(R.string.leaderboard_metric_steps, R.string.leaderboard_unit_steps),
	ACTIVE_TIME(R.string.leaderboard_metric_active_time, R.string.leaderboard_unit_hours),
	SESSIONS(R.string.leaderboard_metric_sessions, R.string.leaderboard_unit_sessions),
}
