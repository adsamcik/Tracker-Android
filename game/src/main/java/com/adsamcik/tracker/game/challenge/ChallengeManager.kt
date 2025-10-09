package com.adsamcik.tracker.game.challenge

import android.content.Context
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.game.CHALLENGE_LOG_SOURCE
import com.adsamcik.tracker.game.challenge.data.ChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.ChallengeInstance
import com.adsamcik.tracker.game.challenge.data.definition.ActiveTimeChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.definition.ExplorerChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.definition.StepChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.definition.WalkDistanceChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.instance.ActiveTimeChallengeInstance
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.ChallengeLoader
import com.adsamcik.tracker.game.challenge.worker.ChallengeExpiredWorker
import com.adsamcik.tracker.game.logGame
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.extension.formatAsDateTime
import com.adsamcik.tracker.shared.utils.extension.tryWithResultAndReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

/**
 * Singleton class that manages saving and loading of challenges from cache storage or network.
 * Exposes reactive Flow-based state for active challenges.
 */
object ChallengeManager {
	// Replace GlobalScope with a supervised singleton scope (still global but cancellable in tests)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private val enabledChallengeList: Array<ChallengeDefinition<*>> = arrayOf(
		ExplorerChallengeDefinition(),
		WalkDistanceChallengeDefinition(),
		StepChallengeDefinition(),
		ActiveTimeChallengeDefinition()
	)

	private val mutableActiveChallengeList_: MutableList<ChallengeInstance<*, *>> = mutableListOf()

	private const val MAX_CHALLENGE_COUNT = 3

	private val _activeChallenges: MutableStateFlow<List<ChallengeInstance<*, *>>> = 
		MutableStateFlow(emptyList())

	private val activeChallengeLock = ReentrantLock()

	/**
	 * Returns immutable StateFlow of active challenges
	 */
	val activeChallenges: StateFlow<List<ChallengeInstance<*, *>>> get() = _activeChallenges.asStateFlow()

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

	@AnyThread
	fun initialize(context: Context, onInitialized: (() -> Unit)? = null) {
		scope.launch {
			val active = initFromDb(context)

			activeChallengeLock.withLock {
				mutableActiveChallengeList_.clear()
				mutableActiveChallengeList_.addAll(active)
				fillEmptyChallengeSlots(context)
				_activeChallenges.value = mutableActiveChallengeList_.toList()
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
				_activeChallenges.value = mutableActiveChallengeList_.toList()
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
				_activeChallenges.value = mutableActiveChallengeList_.toList()
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

