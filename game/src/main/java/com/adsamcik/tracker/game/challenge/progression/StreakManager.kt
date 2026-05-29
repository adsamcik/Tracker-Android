package com.adsamcik.tracker.game.challenge.progression

import androidx.annotation.StringRes
import androidx.room.withTransaction
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ChallengeStreakEntity
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
	suspend fun onChallengeCompleted(database: AppDatabase, now: Long): ChallengeStreakEntity {
		var result: ChallengeStreakEntity? = null
		database.withTransaction {
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
			result = updated
		}
		return result!!
	}

	/**
	 * Call when one or more challenges expire in a batch.
	 * If a freeze is available, consumes one (covers the entire batch).
	 * Otherwise, resets the streak to 0.
	 *
	 * Read-modify-write is wrapped in [database.withTransaction] so a racing
	 * [onChallengeCompleted] can't slip an increment between this read and write
	 * and lose the user's completion bump. If a caller (e.g. `ProgressionRepository`)
	 * has already opened an outer transaction, Room nests reentrantly and this
	 * inner transaction joins it.
	 *
	 * @return Pair of (updated streak, wasFreezed)
	 */
	suspend fun onChallengesExpired(database: AppDatabase): Pair<ChallengeStreakEntity, Boolean> {
		var result: Pair<ChallengeStreakEntity, Boolean>? = null
		database.withTransaction {
			val dao = database.challengeStreakDao()
			dao.ensureExists()
			val current = dao.get() ?: ChallengeStreakEntity()

			val (updated, froze) = if (current.freezeCount > 0) {
				current.copy(freezeCount = current.freezeCount - 1) to true
			} else {
				current.copy(currentCount = 0) to false
			}

			dao.update(updated)
			result = updated to froze
		}
		return result!!
	}

	/**
	 * Observe current streak state reactively.
	 *
	 * Safe to call from any thread (including main). The `.map` handles the row-missing
	 * case by emitting a fresh [ChallengeStreakEntity]; the actual `INSERT OR IGNORE`
	 * happens lazily inside the suspending [onChallengeCompleted]/[onChallengesExpired]
	 * paths, so this observer never triggers a synchronous Room write.
	 */
	fun observeStreak(database: AppDatabase): Flow<StreakState> {
		val dao = database.challengeStreakDao()
		return dao.observe().map { entity ->
			val e = entity ?: ChallengeStreakEntity()
			StreakState(
				currentCount = e.currentCount,
				bestCount = e.bestCount,
				freezeCount = e.freezeCount,
				milestoneResId = getMilestoneResId(e.currentCount),
			)
		}
	}

	companion object {
		const val COMPLETIONS_PER_FREEZE = 7
		const val MAX_FREEZES = 3

		@StringRes
		fun getMilestoneResId(count: Int): Int? = when {
			count >= 30 -> R.string.game_streak_milestone_30
			count >= 14 -> R.string.game_streak_milestone_14
			count >= 7 -> R.string.game_streak_milestone_7
			count >= 3 -> R.string.game_streak_milestone_3
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
	@StringRes val milestoneResId: Int?,
)
