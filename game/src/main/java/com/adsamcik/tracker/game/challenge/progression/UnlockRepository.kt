package com.adsamcik.tracker.game.challenge.progression

import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.entity.PlayerProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides reactive unlock state for game features based on player level.
 * Features are defined in [UnlockableFeature] with their required levels.
 */
@Singleton
class UnlockRepository @Inject constructor() {

	/**
	 * Observe whether a feature is unlocked.
	 */
	fun isUnlocked(database: ChallengeDatabase, feature: UnlockableFeature): Flow<Boolean> {
		return database.playerProfileDao().observe()
			.map { profile ->
				(profile?.level ?: 1) >= feature.requiredLevel
			}
			.distinctUntilChanged()
	}

	/**
	 * Synchronous check if a feature is unlocked.
	 */
	fun isUnlockedSync(database: ChallengeDatabase, feature: UnlockableFeature): Boolean {
		val profile = database.playerProfileDao().get()
		return (profile?.level ?: 1) >= feature.requiredLevel
	}

	/**
	 * Get all features unlocked at the current level.
	 */
	fun getUnlockedFeatures(database: ChallengeDatabase): List<UnlockableFeature> {
		val level = database.playerProfileDao().get()?.level ?: 1
		return UnlockableFeature.entries.filter { it.requiredLevel <= level }
	}

	/**
	 * Get features that would be newly unlocked by reaching [newLevel].
	 * Useful for level-up celebration dialogs.
	 */
	fun getNewUnlocksAtLevel(newLevel: Int): List<UnlockableFeature> {
		return UnlockableFeature.entries.filter { it.requiredLevel == newLevel }
	}

	/**
	 * Get the next unlock and the level needed for it.
	 */
	fun getNextUnlock(database: ChallengeDatabase): Pair<UnlockableFeature, Int>? {
		val level = database.playerProfileDao().get()?.level ?: 1
		return UnlockableFeature.entries
			.filter { it.requiredLevel > level }
			.minByOrNull { it.requiredLevel }
			?.let { it to it.requiredLevel }
	}
}
