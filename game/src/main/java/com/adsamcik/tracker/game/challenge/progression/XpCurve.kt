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
	private val cumulativeXpByLevel = mutableListOf(0L) // index 0 => level 1

	@Synchronized
	private fun cumulativeXpAtLevel(level: Int): Long {
		while (cumulativeXpByLevel.size < level) {
			val currentLevel = cumulativeXpByLevel.size
			val next = cumulativeXpByLevel.last() + xpForLevel(currentLevel)
			cumulativeXpByLevel.add(next)
		}
		return cumulativeXpByLevel[level - 1]
	}

	private fun profileForXp(totalXp: Long): ProfileSnapshot {
		if (totalXp < 0L) {
			return ProfileSnapshot(
				level = 1,
				xpIntoCurrentLevel = totalXp,
				xpForNextLevel = xpForLevel(1),
				totalXp = totalXp,
			)
		}

		var low = 1
		var high = 2
		while (cumulativeXpAtLevel(high + 1) <= totalXp) {
			high *= 2
		}
		while (low < high) {
			val mid = low + (high - low + 1) / 2
			if (cumulativeXpAtLevel(mid) <= totalXp) {
				low = mid
			} else {
				high = mid - 1
			}
		}
		val level = low
		val xpIntoCurrentLevel = totalXp - cumulativeXpAtLevel(level)
		return ProfileSnapshot(
			level = level,
			xpIntoCurrentLevel = xpIntoCurrentLevel,
			xpForNextLevel = xpForLevel(level),
			totalXp = totalXp,
		)
	}

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
		return cumulativeXpAtLevel(targetLevel)
	}

	/**
	 * Determine current level from total accumulated XP.
	 */
	fun levelForXp(totalXp: Long): Int {
		return profileForXp(totalXp).level
	}

	/**
	 * XP progress into the current level.
	 */
	fun xpIntoCurrentLevel(totalXp: Long): Long {
		return profileForXp(totalXp).xpIntoCurrentLevel
	}

	/**
	 * Build a snapshot of level info from total XP.
	 */
	fun computeProfile(totalXp: Long): ProfileSnapshot {
		return profileForXp(totalXp)
	}

	data class ProfileSnapshot(
		val level: Int,
		val xpIntoCurrentLevel: Long,
		val xpForNextLevel: Long,
		val totalXp: Long,
	)
}
