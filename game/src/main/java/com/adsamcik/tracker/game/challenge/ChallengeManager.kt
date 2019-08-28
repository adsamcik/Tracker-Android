package com.adsamcik.tracker.game.challenge

import android.content.Context
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.misc.NonNullLiveData
import com.adsamcik.tracker.common.misc.NonNullLiveMutableData
import com.adsamcik.tracker.game.challenge.data.ChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.ChallengeInstance
import com.adsamcik.tracker.game.challenge.data.definition.ExplorerChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.definition.StepChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.definition.WalkDistanceChallengeDefinition
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.ChallengeLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Singleton class that manages saving and loading of challenges from cache storage or network
 */
object ChallengeManager {
	private val enabledChallengeList: Array<ChallengeDefinition<*>> = arrayOf(
			ExplorerChallengeDefinition(),
			WalkDistanceChallengeDefinition(), StepChallengeDefinition()
	)

	//todo do not hold this indefinitely
	private val mutableActiveChallengeList_: MutableList<ChallengeInstance<*, *>> = mutableListOf()

	private const val MAX_CHALLENGE_COUNT = 3

	private val mutableActiveChallenges: NonNullLiveMutableData<List<ChallengeInstance<*, *>>> = NonNullLiveMutableData(
			mutableActiveChallengeList_
	)

	/**
	 * Returns immutable list of active challenges
	 */
	val activeChallenges: NonNullLiveData<List<ChallengeInstance<*, *>>> get() = mutableActiveChallenges

	@WorkerThread
	private fun initFromDb(context: Context): List<ChallengeInstance<*, *>> {
		val database = ChallengeDatabase.getDatabase(context)
		val active = database.entryDao.getActiveEntry(Time.nowMillis)
		return active.map { ChallengeLoader.loadChallenge(context, it) }
	}

	@AnyThread
	fun initialize(context: Context) {
		initialize(context, null)
	}

	@AnyThread
	fun initialize(context: Context, onInitialized: (() -> Unit)?) {
		GlobalScope.launch(Dispatchers.Default) {
			val active = initFromDb(context)

			mutableActiveChallengeList_.clear()
			mutableActiveChallengeList_.addAll(active)
			while (mutableActiveChallengeList_.size < MAX_CHALLENGE_COUNT) {
				val newChallenge = activateRandomChallenge(context = context)
				if (newChallenge != null) {
					mutableActiveChallengeList_.add(newChallenge)
				} else {
					break
				}
			}
			mutableActiveChallenges.postValue(mutableActiveChallengeList_)
			onInitialized?.invoke()
		}
	}

	fun processSession(
			context: Context,
			session: TrackerSession,
			onChallengeCompletedListener: (ChallengeInstance<*, *>) -> Unit
	) {
		if (mutableActiveChallengeList_.isEmpty()) {
			initialize(context) {
				if (mutableActiveChallengeList_.isEmpty()) return@initialize

				processSession(context, session, onChallengeCompletedListener)
			}
		} else {
			mutableActiveChallengeList_.forEach {
				it.process(
						context,
						session,
						onChallengeCompletedListener
				)
			}
		}
	}


	private fun activateRandomChallenge(context: Context): ChallengeInstance<*, *>? {
		val possibleChallenges =
				enabledChallengeList.filterNot { definition ->
					mutableActiveChallengeList_.any { definition.type == it.data.type }
				}

		if (possibleChallenges.isEmpty()) return null

		val selectedChallengeIndex = Random.nextInt(possibleChallenges.size)
		val selectedChallenge = possibleChallenges[selectedChallengeIndex]
		return selectedChallenge.newInstance(context, Time.nowMillis)
	}
}

