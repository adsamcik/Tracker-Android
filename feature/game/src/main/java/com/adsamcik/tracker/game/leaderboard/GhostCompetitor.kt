package com.adsamcik.tracker.game.leaderboard

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.adsamcik.tracker.game.R

/**
 * Type of ghost competitor derived from the user's own historical data.
 * [CURRENT_USER] represents the live current-week entry.
 */
enum class GhostType(@StringRes val labelRes: Int) {
	CURRENT_USER(R.string.leaderboard_you),
	BEST_WEEK(R.string.leaderboard_ghost_best_week),
	LAST_4_WEEKS_AVG(R.string.leaderboard_ghost_last_4_weeks),
	SAME_WEEK_LAST_YEAR(R.string.leaderboard_ghost_same_week_last_year),
	AVERAGE_WEEK(R.string.leaderboard_ghost_average_week),
}

/**
 * A competitor entry in the weekly ghost leaderboard.
 * Privacy-preserving: derived entirely from local DailySummary data.
 *
 * @param id Stable identifier for this competitor (e.g. "current_user", "best_week").
 * @param type The type of competitor (ghost or current user).
 * @param nameRes String resource for the localized display name.
 * @param value Aggregated metric value for this competitor.
 * @param period Optional human-readable period label (e.g. "This Week", "W27 2024").
 */
@Immutable
data class GhostCompetitor(
	val id: String,
	val type: GhostType,
	@StringRes val nameRes: Int,
	val value: Double,
	val period: String? = null,
) {
	val isCurrentUser: Boolean get() = type == GhostType.CURRENT_USER
}
