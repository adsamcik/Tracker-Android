package com.adsamcik.tracker.game.challenge.progression

import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.data.Medal
import com.adsamcik.tracker.shared.base.data.TrackerSession
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Calculates XP awards for various game events.
 * All formulas documented in plan.md.
 */
@Singleton
class XpCalculator @Inject constructor() {

	/**
	 * XP from a passive tracking session.
	 * Formula: (distanceM × 0.05 + steps × 0.005 + min(durationMin,120) × 0.2) × activityMult
	 * Capped at [SESSION_CAP] per session.
	 */
	fun calculateSessionXp(session: TrackerSession, isVehicleOrStill: Boolean = false): XpAward {
		if (isVehicleOrStill) return XpAward(0, "SESSION")

		val durationMin = ((session.end - session.start) / 60_000.0).coerceAtMost(120.0)
		val rawXp = session.distanceOnFootInM * 0.05 +
			session.steps * 0.005 +
			durationMin * 0.2

		val amount = min(rawXp.roundToInt(), SESSION_CAP)
		return XpAward(amount, "SESSION")
	}

	/**
	 * XP from challenge completion.
	 * Base XP by difficulty × medal multiplier × streak multiplier.
	 */
	fun calculateChallengeXp(
		difficulty: ChallengeDifficulty,
		medal: Medal,
		streakCount: Int,
	): XpAward {
		val baseXp = when (difficulty) {
			ChallengeDifficulty.VERY_EASY -> 50
			ChallengeDifficulty.EASY -> 100
			ChallengeDifficulty.MEDIUM -> 200
			ChallengeDifficulty.HARD -> 400
			ChallengeDifficulty.VERY_HARD -> 750
		}
		val streakMult = 1.0 + (streakCount.coerceAtMost(MAX_STREAK_BONUS) * STREAK_MULT_PER_STEP)
		val amount = (baseXp * medal.xpMultiplier * streakMult).roundToInt()
		return XpAward(amount, "CHALLENGE")
	}

	/**
	 * XP from goal completion (flat rates).
	 */
	fun calculateGoalXp(isWeekly: Boolean): XpAward {
		val amount = if (isWeekly) WEEKLY_GOAL_XP else DAILY_GOAL_XP
		return XpAward(amount, "GOAL")
	}

	companion object {
		const val SESSION_CAP = 300
		const val DAILY_CAP = 500
		const val DAILY_GOAL_XP = 75
		const val WEEKLY_GOAL_XP = 200
		private const val MAX_STREAK_BONUS = 20
		private const val STREAK_MULT_PER_STEP = 0.05
	}
}

/**
 * Result of an XP calculation.
 */
data class XpAward(
	val amount: Int,
	val source: String,
)
