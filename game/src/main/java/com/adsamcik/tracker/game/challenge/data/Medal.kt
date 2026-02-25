package com.adsamcik.tracker.game.challenge.data

import androidx.annotation.StringRes
import com.adsamcik.tracker.game.R

/**
 * Medal awarded for challenge completion based on time efficiency.
 * - GOLD: Completed in ≤40% of allowed time
 * - SILVER: Completed in ≤70% of allowed time
 * - BRONZE: Completed at all
 * - NONE: Not completed / expired
 */
enum class Medal(
	@StringRes val labelRes: Int,
	val xpMultiplier: Double
) {
	GOLD(R.string.medal_gold, 2.0),
	SILVER(R.string.medal_silver, 1.5),
	BRONZE(R.string.medal_bronze, 1.0),
	NONE(R.string.medal_none, 0.0);

	companion object {
		/**
		 * Determine medal based on completion timing.
		 * @param completedAt timestamp when challenge was completed
		 * @param startTime challenge start timestamp
		 * @param endTime challenge expiry timestamp
		 * @return Medal earned, or NONE if not completed
		 */
		fun fromCompletion(completedAt: Long?, startTime: Long, endTime: Long): Medal {
			if (completedAt == null) return NONE
			val totalDuration = endTime - startTime
			if (totalDuration <= 0) return BRONZE
			val timeUsed = completedAt - startTime
			val ratio = timeUsed.toDouble() / totalDuration
			return when {
				ratio <= 0.4 -> GOLD
				ratio <= 0.7 -> SILVER
				else -> BRONZE
			}
		}
	}
}
