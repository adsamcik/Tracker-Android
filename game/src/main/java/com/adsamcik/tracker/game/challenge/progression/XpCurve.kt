package com.adsamcik.tracker.game.challenge.progression

import kotlin.math.floor
import kotlin.math.pow

/**
 * XP leveling curve: `xpForLevel(n) = floor(30 × n^1.5)`
 *
 * | Level | XP to Next | Cumulative |
 * |-------|-----------|------------|
 * | 2     | 30        | 30         |
 * | 5     | 335       | 844        |
 * | 10    | 948       | 4,275      |
 * | 15    | 1,742     | 11,335     |
 * | 20    | 2,683     | 22,815     |
 */
object XpCurve {
	/**
	 * XP required to advance FROM level [level] TO level+1.
	 * Level 1 requires [xpForLevel(1)] = 30 XP to reach level 2.
	 */
	fun xpForLevel(level: Int): Long {
		require(level >= 1) { "Level must be >= 1, was $level" }
		return floor(30.0 * level.toDouble().pow(1.5)).toLong()
	}

	/**
	 * Cumulative XP required to reach [targetLevel] from level 1.
	 */
	fun cumulativeXpForLevel(targetLevel: Int): Long {
		require(targetLevel >= 1) { "Level must be >= 1, was $targetLevel" }
		var total = 0L
		for (i in 1 until targetLevel) {
			total += xpForLevel(i)
		}
		return total
	}

	/**
	 * Determine current level from total accumulated XP.
	 */
	fun levelForXp(totalXp: Long): Int {
		var level = 1
		var remaining = totalXp
		while (remaining >= xpForLevel(level)) {
			remaining -= xpForLevel(level)
			level++
		}
		return level
	}

	/**
	 * XP progress into the current level.
	 */
	fun xpIntoCurrentLevel(totalXp: Long): Long {
		var remaining = totalXp
		var level = 1
		while (remaining >= xpForLevel(level)) {
			remaining -= xpForLevel(level)
			level++
		}
		return remaining
	}

	/**
	 * Build a snapshot of level info from total XP.
	 */
	fun computeProfile(totalXp: Long): ProfileSnapshot {
		var remaining = totalXp
		var level = 1
		while (remaining >= xpForLevel(level)) {
			remaining -= xpForLevel(level)
			level++
		}
		return ProfileSnapshot(
			level = level,
			xpIntoCurrentLevel = remaining,
			xpForNextLevel = xpForLevel(level),
			totalXp = totalXp,
		)
	}

	data class ProfileSnapshot(
		val level: Int,
		val xpIntoCurrentLevel: Long,
		val xpForNextLevel: Long,
		val totalXp: Long,
	)
}
