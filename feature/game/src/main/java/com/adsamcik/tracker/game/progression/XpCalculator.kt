package com.adsamcik.tracker.game.progression

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure XP-award math for the events that drive player leveling.
 *
 * Two leveling sources are currently wired:
 *  - [sessionXp]: passive XP from a completed tracking session (the primary
 *    way players climb the early levels that unlock the first mini-games).
 *  - [miniGameXp]: a mini-game run credits its points 1:1 as XP, so playing
 *    unlocked games keeps the level rising.
 */
internal object XpCalculator {
	/** Maximum XP a single tracking session can grant. */
	const val SESSION_CAP = 300

	/** Maximum XP that may be credited in a single calendar day (per source ceilings). */
	const val DAILY_CAP = 500L

	/**
	 * XP from a passive tracking session.
	 *
	 * `distanceM × 0.05 + min(durationMin, 120) × 0.2`, clamped to
	 * `[0, SESSION_CAP]`.
	 *
	 * Steps are deliberately absent. The legacy session/trip counters do not carry source-qualified
	 * evidence and therefore cannot authorize an irreversible XP award.
	 */
	fun sessionXp(distanceM: Float, durationMs: Long): Int {
		val durationMin = (durationMs / MILLIS_PER_MINUTE).coerceAtMost(MAX_DURATION_MIN)
		val raw = distanceM * DISTANCE_WEIGHT +
			durationMin * DURATION_WEIGHT
		return min(raw.roundToInt(), SESSION_CAP).coerceAtLeast(0)
	}

	/** A mini-game run credits its points as XP 1:1. */
	fun miniGameXp(points: Int): Int = points.coerceAtLeast(0)

	/** Fixed XP granted for meeting a daily goal. */
	fun goalXp(): Int = GOAL_XP

	private const val GOAL_XP = 50

	private const val MILLIS_PER_MINUTE = 60_000.0
	private const val MAX_DURATION_MIN = 120.0
	private const val DISTANCE_WEIGHT = 0.05
	private const val DURATION_WEIGHT = 0.2
}
