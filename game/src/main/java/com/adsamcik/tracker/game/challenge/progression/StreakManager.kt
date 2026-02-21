package com.adsamcik.tracker.game.challenge.progression

import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeStreakEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages challenge completion streaks and freeze tokens.
 *
 * Rules:
 * - Streak increments on each challenge completion
 * - Freeze earned every [COMPLETIONS_PER_FREEZE] completions, max [MAX_FREEZES]
 * - On expiry: if freeze available, consume one (covers entire batch); else break streak
 * - Best streak tracked for personal records
 */
@Singleton
class StreakManager @Inject constructor() {

	/**
	 * Call when a challenge is completed.
	 * Increments streak, potentially earns a freeze.
	 * @return Updated streak state
	 */
	fun onChallengeCompleted(database: ChallengeDatabase, now: Long): ChallengeStreakEntity {
		val dao = database.challengeStreakDao()
		dao.ensureExists()
		val current = dao.get() ?: ChallengeStreakEntity()

		val newCount = current.currentCount + 1
		val newBest = maxOf(newCount, current.bestCount)

		// Earn a freeze every COMPLETIONS_PER_FREEZE completions
		val newFreezes = if (newCount % COMPLETIONS_PER_FREEZE == 0 && current.freezeCount < MAX_FREEZES) {
			current.freezeCount + 1
		} else {
			current.freezeCount
		}

		val updated = current.copy(
			currentCount = newCount,
			bestCount = newBest,
			lastCompletionTime = now,
			freezeCount = newFreezes,
		)
		dao.update(updated)
		return updated
	}

	/**
	 * Call when one or more challenges expire in a batch.
	 * If a freeze is available, consumes one (covers the entire batch).
	 * Otherwise, resets the streak to 0.
	 * @return Pair of (updated streak, wasFreezed)
	 */
	fun onChallengesExpired(database: ChallengeDatabase): Pair<ChallengeStreakEntity, Boolean> {
		val dao = database.challengeStreakDao()
		dao.ensureExists()
		val current = dao.get() ?: ChallengeStreakEntity()

		val (updated, froze) = if (current.freezeCount > 0) {
			current.copy(freezeCount = current.freezeCount - 1) to true
		} else {
			current.copy(currentCount = 0) to false
		}

		dao.update(updated)
		return updated to froze
	}

	/**
	 * Observe current streak state reactively.
	 */
	fun observeStreak(database: ChallengeDatabase): Flow<StreakState> {
		val dao = database.challengeStreakDao()
		dao.ensureExists()
		return dao.observe().map { entity ->
			val e = entity ?: ChallengeStreakEntity()
			StreakState(
				currentCount = e.currentCount,
				bestCount = e.bestCount,
				freezeCount = e.freezeCount,
				milestoneText = getMilestoneText(e.currentCount),
			)
		}
	}

	companion object {
		const val COMPLETIONS_PER_FREEZE = 7
		const val MAX_FREEZES = 3

		fun getMilestoneText(count: Int): String? = when {
			count >= 30 -> "Legend 👑"
			count >= 14 -> "Unstoppable 💪"
			count >= 7 -> "On fire! 🔥"
			count >= 3 -> "Getting warmed up 🔥"
			else -> null
		}
	}
}

/**
 * Snapshot of streak state for UI consumption.
 */
data class StreakState(
	val currentCount: Int,
	val bestCount: Int,
	val freezeCount: Int,
	val milestoneText: String?,
)
