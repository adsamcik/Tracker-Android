package com.adsamcik.tracker.game.challenge

import android.content.Context
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.debug.LogData
import com.adsamcik.tracker.common.extension.formatAsDateTime
import com.adsamcik.tracker.common.extension.tryWithResultAndReport
import com.adsamcik.tracker.common.misc.NonNullLiveData
import com.adsamcik.tracker.common.misc.NonNullLiveMutableData
import com.adsamcik.tracker.game.challenge.data.ChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.ChallengeInstance
import com.adsamcik.tracker.game.challenge.data.definition.ExplorerChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.definition.StepChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.definition.WalkDistanceChallengeDefinition
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.ChallengeLoader
import com.adsamcik.tracker.game.challenge.worker.ChallengeExpiredWorker
import com.adsamcik.tracker.game.logGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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

	private val activeChallengeLock = ReentrantLock()

	/**
	 * Returns immutable list of active challenges
	 */
	val activeChallenges: NonNullLiveData<List<ChallengeInstance<*, *>>> get() = mutableActiveChallenges

	@WorkerThread
	private fun initFromDb(context: Context): List<ChallengeInstance<*, *>> {
		val database = ChallengeDatabase.database(context)
		val active = database.entryDao.getActiveEntry(Time.nowMillis)

		return active.mapNotNull {
			tryWithResultAndReport({ null }) {
				ChallengeLoader.loadChallenge(context, it)
			}.also { instance ->
				if (instance == null) {
					database.entryDao.delete(it)
				}
			}
		}
	}

	@AnyThread
	fun initialize(context: Context, onInitialized: (() -> Unit)? = null) {
		GlobalScope.launch(Dispatchers.Default) {
			val active = initFromDb(context)

			activeChallengeLock.withLock {
				mutableActiveChallengeList_.clear()
				mutableActiveChallengeList_.addAll(active)
				fillEmptyChallengeSlots(context)
			}
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
			activeChallengeLock.withLock {
				mutableActiveChallengeList_.forEach {
					it.process(
							context,
							session,
							onChallengeCompletedListener
					)
				}
			}
		}
	}

	private fun fillEmptyChallengeSlots(context: Context) {
		if (mutableActiveChallengeList_.size >= MAX_CHALLENGE_COUNT) return

		activeChallengeLock.withLock {
			while (mutableActiveChallengeList_.size < MAX_CHALLENGE_COUNT) {
				val newChallenge = activateRandomChallenge(context)
				if (newChallenge != null) {
					mutableActiveChallengeList_.add(newChallenge)
				} else {
					break
				}
			}
			mutableActiveChallenges.postValue(mutableActiveChallengeList_)
			scheduleNextChallengeExpiredWork(context)
		}
	}

	private fun scheduleNextChallengeExpiredWork(context: Context) {
		val nextExpiry = requireNotNull(mutableActiveChallengeList_.minBy { it.endTime }).endTime
		ChallengeExpiredWorker.schedule(context, nextExpiry)
		logGame(LogData(message = "Scheduled next expiry worker to run at ${nextExpiry.formatAsDateTime()}"))
	}

	internal fun checkExpiredChallenges(context: Context) {
		val expired = mutableActiveChallengeList_.filter { it.endTime > Time.nowMillis }
		if (expired.isNotEmpty()) {
			activeChallengeLock.withLock {
				mutableActiveChallengeList_.removeAll(expired)
				fillEmptyChallengeSlots(context)
			}
		}
	}

	private fun logNewChallenge(context: Context, definition: ChallengeDefinition<*>) {
		val title = context.getString(definition.titleRes)
		logGame(LogData(message = "Created new random challenge $title"))
	}


	private fun activateRandomChallenge(context: Context): ChallengeInstance<*, *>? {
		val possibleChallenges =
				enabledChallengeList.filterNot { definition ->
					mutableActiveChallengeList_.any { definition.type == it.data.type }
				}

		if (possibleChallenges.isEmpty()) return null

		val selectedChallengeIndex = Random.nextInt(possibleChallenges.size)
		val selectedChallengeDefinition = possibleChallenges[selectedChallengeIndex]

		logNewChallenge(context, selectedChallengeDefinition)

		return selectedChallengeDefinition.newInstance(context, Time.nowMillis)
	}
}

