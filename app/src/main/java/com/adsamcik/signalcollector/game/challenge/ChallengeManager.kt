package com.adsamcik.signalcollector.game.challenge

import android.content.Context
import com.adsamcik.signalcollector.game.challenge.data.ChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.ChallengeInstance
import com.adsamcik.signalcollector.game.challenge.data.definition.ExplorerChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.definition.StepChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.definition.WalkDistanceChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.database.ChallengeDatabase
import com.adsamcik.signalcollector.game.challenge.database.ChallengeLoader
import com.adsamcik.signalcollector.misc.NonNullLiveData
import com.adsamcik.signalcollector.misc.NonNullLiveMutableData
import com.adsamcik.signalcollector.tracker.data.session.TrackerSession
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Singleton class that manages saving and loading of challenges from cache storage or network
 */
object ChallengeManager {
	private val enabledChallengeList: Array<ChallengeDefinition<*>> = arrayOf(ExplorerChallengeDefinition(), WalkDistanceChallengeDefinition(), StepChallengeDefinition())

	//todo do not hold this indefinitely
	private val _mutableActiveChallengeList: MutableList<ChallengeInstance<*>> = mutableListOf()

	private const val MAX_CHALLENGE_COUNT = 3

	private val mutableActiveChallenges: NonNullLiveMutableData<List<ChallengeInstance<*>>> = NonNullLiveMutableData(_mutableActiveChallengeList)

	/**
	 * Returns immutable list of active challenges
	 */
	val activeChallenges: NonNullLiveData<List<ChallengeInstance<*>>> get() = mutableActiveChallenges


	private fun initFromDb(context: Context): List<ChallengeInstance<*>> {
		val resources = context.resources
		val database = ChallengeDatabase.getDatabase(context)
		val active = database.entryDao.getActiveEntry(System.currentTimeMillis())
		return active.map { ChallengeLoader.loadChallenge(context, resources, database, it) }
	}

	fun initialize(context: Context) {
		GlobalScope.launch {

			val active = initFromDb(context)

			_mutableActiveChallengeList.clear()
			_mutableActiveChallengeList.addAll(active)
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

	//todo decide on paralelization
	fun processSession(sessionList: List<TrackerSession>, onChallengeCompletedListener: (ChallengeInstance<*>) -> Unit) {
		val challenges = _mutableActiveChallengeList
		challenges.forEach { it.batchProcess(sessionList, onChallengeCompletedListener) }
	}


	fun activateRandomChallenge(context: Context): ChallengeInstance<*>? {
		val possibleChallenges = enabledChallengeList.filterNot { definition -> _mutableActiveChallengeList.any { definition.type == it.data.type } }

		if (possibleChallenges.isEmpty()) {
			return null
		}

		val selectedChallengeIndex = Random.nextInt(possibleChallenges.size)
		val selectedChallenge = possibleChallenges[selectedChallengeIndex]
		return selectedChallenge.createInstance(context, System.currentTimeMillis())
	}
}
