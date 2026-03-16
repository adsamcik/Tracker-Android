package com.adsamcik.tracker.game.leaderboard

import androidx.compose.runtime.Immutable

/**
 * Full state for the weekly ghost leaderboard section.
 *
 * @param metric Currently selected comparison metric
 * @param currentWeekValue User's value for the current (potentially incomplete) week
 * @param competitors All competitors (ghosts + current user), sorted descending by value
 * @param currentRank 1-based rank of the current user among all competitors
 * @param weekProgressFraction Fraction of the current week elapsed (0.0 Mon 00:00 → ~1.0 Sun 23:59)
 */
@Immutable
data class LeaderboardState(
	val metric: LeaderboardMetric,
	val currentWeekValue: Double,
	val competitors: List<GhostCompetitor>,
	val currentRank: Int,
	val weekProgressFraction: Float,
) {
	/** Ghost-only entries (excluding the current user). */
	val ghosts: List<GhostCompetitor>
		get() = competitors.filter { !it.isCurrentUser }

	/** The top ghost value, or 0 if no ghosts exist. */
	val topGhostValue: Double
		get() = ghosts.firstOrNull()?.value ?: 0.0

	/** Progress fraction of the current week value against the top ghost (capped at 1.0). */
	val progressAgainstTop: Float
		get() = if (topGhostValue > 0.0) {
			(currentWeekValue / topGhostValue).coerceAtMost(1.0).toFloat()
		} else {
			0f
		}
}
