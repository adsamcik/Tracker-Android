package com.adsamcik.signalcollector.game.challenge

import android.content.Context
import com.adsamcik.signalcollector.game.challenge.data.definition.ChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.definition.ExplorerChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.definition.WalkDistanceChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.instance.ChallengeInstance
import com.adsamcik.signalcollector.misc.NonNullLiveData
import com.adsamcik.signalcollector.misc.NonNullLiveMutableData
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Singleton class that manages saving and loading of challenges from cache storage or network
 */
object ChallengeManager {
	private val enabledChallengeList: Array<ChallengeDefinition<*>> = arrayOf(ExplorerChallengeDefinition(), WalkDistanceChallengeDefinition())
	private val _mutableActiveChallengeList: MutableList<ChallengeInstance<*>> = mutableListOf()

	private const val MAX_CHALLENGE_COUNT = 3


	private val mutableActiveChallenges: NonNullLiveMutableData<List<ChallengeInstance<*>>> = NonNullLiveMutableData(_mutableActiveChallengeList)

	/**
	 * Returns immutable list of active challenges
	 */
	val activeChallenges: NonNullLiveData<List<ChallengeInstance<*>>> get() = mutableActiveChallenges

	fun initialize(context: Context) {
		GlobalScope.launch {
			_mutableActiveChallengeList.clear()
			while (_mutableActiveChallengeList.size < MAX_CHALLENGE_COUNT) {
				val newChallenge = activateRandomChallenge(context = context)
				if (newChallenge != null)
					_mutableActiveChallengeList.add(newChallenge)
				else
					break
			}
			mutableActiveChallenges.postValue(_mutableActiveChallengeList)
		}
	}


	fun activateRandomChallenge(context: Context): ChallengeInstance<*>? {
		val possibleChallenges = enabledChallengeList.filterNot { definition -> _mutableActiveChallengeList.any { definition.name == it.data.name } }

		if (possibleChallenges.isEmpty()) {
			return null
		}

		val selectedChallengeIndex = Random.nextInt(possibleChallenges.size)
		val selectedChallenge = possibleChallenges[selectedChallengeIndex]
		return selectedChallenge.createInstance(context, System.currentTimeMillis())
	}
}
