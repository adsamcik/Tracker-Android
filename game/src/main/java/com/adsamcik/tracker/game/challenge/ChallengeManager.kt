package com.adsamcik.tracker.game.challenge

import android.content.Context
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.game.CHALLENGE_LOG_SOURCE
import com.adsamcik.tracker.game.challenge.data.ChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.ChallengeInstance
import com.adsamcik.tracker.game.challenge.data.definition.ExplorerChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.definition.StepChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.definition.WalkDistanceChallengeDefinition
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.ChallengeLoader
import com.adsamcik.tracker.game.challenge.worker.ChallengeExpiredWorker
import com.adsamcik.tracker.game.logGame
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.extension.formatAsDateTime
import com.adsamcik.tracker.shared.base.misc.NonNullLiveData
import com.adsamcik.tracker.shared.base.misc.NonNullLiveMutableData
import com.adsamcik.tracker.shared.utils.extension.tryWithResultAndReport
import kotlinx.coroutines.DelicateCoroutinesApi
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
		val active = database.entryDao().getActiveEntry(Time.nowMillis)

		return active.mapNotNull {
			tryWithResultAndReport({ null }) {
				ChallengeLoader.loadChallenge(context, it)
			}.also { instance ->
				if (instance == null) {
					database.entryDao().delete(it)
				}
			}
		}
	}

	@OptIn(DelicateCoroutinesApi::class)
	@AnyThread
	fun initialize(context: Context, onInitialized: (() -> Unit)? = null) {
		GlobalScope.launch(Dispatchers.Default) {
			val active = initFromDb(context)

			activeChallengeLock.withLock {
				mutableActiveChallengeList_.clear()
				mutableActiveChallengeList_.addAll(active)
				fillEmptyChallengeSlots(context)
				mutableActiveChallenges.postValue(mutableActiveChallengeList_)
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
				mutableActiveChallenges.postValue(mutableActiveChallengeList_)
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
			scheduleNextChallengeExpiredWork(context)
		}
	}

	private fun scheduleNextChallengeExpiredWork(context: Context) {
		val nextExpiry = mutableActiveChallengeList_.minOf { it.endTime }
		ChallengeExpiredWorker.schedule(context, nextExpiry)
		logGame(
				LogData(
						message = "Scheduled next expiry worker to run at ${nextExpiry.formatAsDateTime()}",
						source = CHALLENGE_LOG_SOURCE
				)
		)
	}

	internal fun checkExpiredChallenges(context: Context) {
		val now = Time.nowMillis
		val expired = mutableActiveChallengeList_.filter { it.endTime <= now }
		if (expired.isNotEmpty()) {
			activeChallengeLock.withLock {
				mutableActiveChallengeList_.removeAll(expired.toSet())
				fillEmptyChallengeSlots(context)
				mutableActiveChallenges.postValue(mutableActiveChallengeList_)
			}
		}
	}

	private fun logNewChallenge(context: Context, instance: ChallengeInstance<*, *>) {
		val title = context.getString(instance.definition.titleRes)
		logGame(
				LogData(
						message = "Created new random challenge $title with expiration on ${instance.endTime.formatAsDateTime()}",
						source = CHALLENGE_LOG_SOURCE
				)
		)
	}


	private fun activateRandomChallenge(context: Context): ChallengeInstance<*, *>? {
		val possibleChallenges =
				enabledChallengeList.filterNot { definition ->
					mutableActiveChallengeList_.any { definition.type == it.data.type }
				}

		if (possibleChallenges.isEmpty()) return null

		val selectedChallengeIndex = Random.nextInt(possibleChallenges.size)
		val selectedChallengeDefinition = possibleChallenges[selectedChallengeIndex]

		val newInstance = selectedChallengeDefinition.newInstance(context, Time.nowMillis)

		logNewChallenge(context, newInstance)

		return newInstance
	}
}

