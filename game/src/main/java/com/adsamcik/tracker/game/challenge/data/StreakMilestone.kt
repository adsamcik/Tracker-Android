package com.adsamcik.tracker.game.challenge.data

import androidx.annotation.StringRes
import com.adsamcik.tracker.game.R

/**
 * Streak milestone flavor text shown when a streak reaches specific thresholds.
 */
enum class StreakMilestone(
	val threshold: Int,
	@StringRes val messageRes: Int,
) {
	LEGEND(30, R.string.game_streak_milestone_30),
	UNSTOPPABLE(14, R.string.game_streak_milestone_14),
	ON_FIRE(7, R.string.game_streak_milestone_7),
	WARMING_UP(3, R.string.game_streak_milestone_3);

	companion object {
		/** Get milestone for given streak count, or null if no milestone reached. */
		fun forStreak(count: Int): StreakMilestone? =
			entries.firstOrNull { count >= it.threshold }
	}
}
