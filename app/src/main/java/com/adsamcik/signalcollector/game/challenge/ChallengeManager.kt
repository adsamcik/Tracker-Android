package com.adsamcik.signalcollector.game.challenge

import android.content.Context
import com.adsamcik.signalcollector.game.challenge.data.Challenge
import com.adsamcik.signalcollector.game.challenge.definition.ChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.definition.ExplorerChallengeDefinition
import kotlin.random.Random

/**
 * Singleton class that manages saving and loading of challenges from cache storage or network
 */
object ChallengeManager {
	private val enabledChallenges: Array<ChallengeDefinition> = arrayOf(ExplorerChallengeDefinition())
	private val mutableActiveChallenges: MutableList<Challenge> = mutableListOf()

	/**
	 * Returns immutable list of active challenges
	 */
	val activeChallenges: List<Challenge> = mutableActiveChallenges



	fun selectChallenge(context: Context): Challenge {
		val selectedChallengeIndex = Random.nextInt(enabledChallenges.size)
		val difficultyIndex = Random.nextBits(ChallengeDifficulty.values().size)

		val difficulty = ChallengeDifficulty.values()[difficultyIndex]
		val selectedChallenge = enabledChallenges[selectedChallengeIndex]
		return selectedChallenge.createInstance(context, difficulty)
	}
}
