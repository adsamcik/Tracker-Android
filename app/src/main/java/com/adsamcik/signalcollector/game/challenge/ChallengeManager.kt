package com.adsamcik.signalcollector.game.challenge

import android.content.Context
import com.adsamcik.signalcollector.game.challenge.data.definition.ChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.definition.ExplorerChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.instance.Challenge
import kotlin.random.Random

/**
 * Singleton class that manages saving and loading of challenges from cache storage or network
 */
object ChallengeManager {
	private val enabledChallenges: Array<ChallengeDefinition> = arrayOf(ExplorerChallengeDefinition())
	private val mutableActiveChallenges: MutableList<Challenge> = mutableListOf()

	const val MAX_CHALLENGE_COUNT = 3

	/**
	 * Returns immutable list of active challenges
	 */
	val activeChallenges: List<Challenge> = mutableActiveChallenges

	fun initialize(context: Context) {
		mutableActiveChallenges.clear()
		while (mutableActiveChallenges.size < MAX_CHALLENGE_COUNT) {
			val newChallenge = activateRandomChallenge(context = context)
			if (newChallenge != null)
				mutableActiveChallenges.add(newChallenge)
			else
				break
		}
	}


	fun activateRandomChallenge(context: Context): Challenge? {
		val possibleChallenges = enabledChallenges.filterNot { definition -> activeChallenges.any { definition.type == it::class } }

		if (possibleChallenges.isEmpty())
			return null

		val selectedChallengeIndex = Random.nextInt(possibleChallenges.size)
		val selectedChallenge = enabledChallenges[selectedChallengeIndex]
		return selectedChallenge.createInstance(context, System.currentTimeMillis())
	}
}
