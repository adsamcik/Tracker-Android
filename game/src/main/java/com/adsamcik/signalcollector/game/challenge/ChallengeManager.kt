package com.adsamcik.signalcollector.game.challenge

import android.content.Context
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.misc.NonNullLiveData
import com.adsamcik.signalcollector.common.misc.NonNullLiveMutableData
import com.adsamcik.signalcollector.game.challenge.data.ChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.ChallengeInstance
import com.adsamcik.signalcollector.game.challenge.data.definition.ExplorerChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.definition.StepChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.definition.WalkDistanceChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.database.ChallengeDatabase
import com.adsamcik.signalcollector.game.challenge.database.ChallengeLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Singleton class that manages saving and loading of challenges from cache storage or network
 */
object ChallengeManager {
	private val enabledChallengeList: Array<ChallengeDefinition<*>> = arrayOf(ExplorerChallengeDefinition(), WalkDistanceChallengeDefinition(), StepChallengeDefinition())

	//todo do not hold this indefinitely
	private val mutableActiveChallengeList_: MutableList<ChallengeInstance<*>> = mutableListOf()

	private const val MAX_CHALLENGE_COUNT = 3

	private val mutableActiveChallenges: NonNullLiveMutableData<List<ChallengeInstance<*>>> = NonNullLiveMutableData(mutableActiveChallengeList_)

	/**
	 * Returns immutable list of active challenges
	 */
	val activeChallenges: NonNullLiveData<List<ChallengeInstance<*>>> get() = mutableActiveChallenges

	@WorkerThread
	private fun initFromDb(context: Context): List<ChallengeInstance<*>> {
		val database = ChallengeDatabase.getDatabase(context)
		val active = database.entryDao.getActiveEntry(System.currentTimeMillis())
		return active.map { ChallengeLoader.loadChallenge(context, it) }
	}

	private fun persistChallenges(context: Context) {
		ChallengeDatabase.getDatabase(context).entryDao.update(mutableActiveChallengeList_.map { it.data })

	}

	@AnyThread
	fun initialize(context: Context) {
		GlobalScope.launch(Dispatchers.Default) {
			val active = initFromDb(context)

			mutableActiveChallengeList_.clear()
			mutableActiveChallengeList_.addAll(active)
			while (mutableActiveChallengeList_.size < MAX_CHALLENGE_COUNT) {
				val newChallenge = activateRandomChallenge(context = context)
				if (newChallenge != null)
					mutableActiveChallengeList_.add(newChallenge)
				else
					break
			}
			mutableActiveChallenges.postValue(mutableActiveChallengeList_)
		}
	}

	fun processSession(session: TrackerSession, onChallengeCompletedListener: (ChallengeInstance<*>) -> Unit) {
		mutableActiveChallengeList_.forEach { it.process(session, onChallengeCompletedListener) }
	}


	private fun activateRandomChallenge(context: Context): ChallengeInstance<*>? {
		val possibleChallenges = enabledChallengeList.filterNot { definition -> mutableActiveChallengeList_.any { definition.type == it.data.type } }

		if (possibleChallenges.isEmpty()) {
			return null
		}

		val selectedChallengeIndex = Random.nextInt(possibleChallenges.size)
		val selectedChallenge = possibleChallenges[selectedChallengeIndex]
		return selectedChallenge.newInstance(context, System.currentTimeMillis())
	}
}
