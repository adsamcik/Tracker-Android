package com.adsamcik.tracker.game.challenge

import android.content.Context
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.game.CHALLENGE_LOG_SOURCE
import com.adsamcik.tracker.game.challenge.data.ChallengeInstanceNew
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.game.challenge.processor.ChallengeTypeRegistry
import com.adsamcik.tracker.game.challenge.progression.ProgressionRepository
import com.adsamcik.tracker.game.challenge.worker.ChallengeExpiredWorker
import com.adsamcik.tracker.game.logGame
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.extension.formatAsDateTime
import com.adsamcik.tracker.shared.utils.extension.tryWithResultAndReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Manages challenge lifecycle: loading, session processing, creation, and expiry.
 * Injected via Hilt. Uses [ChallengeTypeRegistry] for type-agnostic challenge operations.
 */
@Singleton
class ChallengeManager @Inject constructor(
	private val registry: ChallengeTypeRegistry,
	private val progressionRepository: ProgressionRepository,
	private val dispatchers: DispatchersProvider,
) {
	private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

	private val activeChallengeList = mutableListOf<ChallengeInstanceNew>()

	private val _activeChallenges: MutableStateFlow<List<ChallengeInstanceNew>> =
		MutableStateFlow(emptyList())

	private val lock = Mutex()

	/**
	 * Observable list of currently active challenges.
	 */
	val activeChallenges: StateFlow<List<ChallengeInstanceNew>> get() = _activeChallenges.asStateFlow()

	@WorkerThread
	private suspend fun loadFromDb(context: Context): List<ChallengeInstanceNew> {
		val dao = ChallengeDatabase.database(context).challengeDao()
		val now = Time.nowMillis
		return dao.getActive(now).mapNotNull { entity ->
			tryWithResultAndReport({ null }) {
				val processor = registry.get(entity.type)
				ChallengeInstanceNew(entity, processor)
			}
		}
	}

	@AnyThread
	fun initialize(context: Context, onInitialized: (() -> Unit)? = null) {
		scope.launch {
			val active = loadFromDb(context)

			lock.withLock {
				activeChallengeList.clear()
				activeChallengeList.addAll(active)
				fillEmptyChallengeSlotsLocked(context)
				_activeChallenges.value = activeChallengeList.toList()
			}
			onInitialized?.invoke()
		}
	}

	suspend fun processSession(
		context: Context,
		session: TrackerSession,
		onChallengeCompletedListener: (ChallengeInstanceNew) -> Unit
	) {
		if (activeChallengeList.isEmpty()) {
			initialize(context) {
				if (activeChallengeList.isEmpty()) return@initialize
				scope.launch {
					processSession(context, session, onChallengeCompletedListener)
				}
			}
			return
		}

		val dao = ChallengeDatabase.database(context).challengeDao()

		lock.withLock {
			activeChallengeList.forEachIndexed { index, instance ->
				if (instance.isCompleted) return@forEachIndexed

				val delta = instance.processor.extractProgress(context, session)
				if (delta > 0.0) {
					val updatedEntity = instance.entity.copy(
						currentValue = instance.entity.currentValue + delta,
						isCompleted = (instance.entity.currentValue + delta) >= instance.entity.requiredValue
					)
					dao.update(updatedEntity)
					val updatedInstance = instance.copy(entity = updatedEntity)
					activeChallengeList[index] = updatedInstance

					logGame(
						LogData(
							message = "Processed ${updatedInstance.getTitle(context)}: +$delta (${updatedEntity.currentValue}/${updatedEntity.requiredValue})",
							source = CHALLENGE_LOG_SOURCE
						)
					)

					if (updatedInstance.isCompleted) {
						val result = progressionRepository.onChallengeCompleted(context, updatedInstance)
						logGame(
							LogData(
								message = "Challenge completed! Medal=${result.medal}, XP=${result.xpAwarded}, Streak=${result.streakCount}",
								source = CHALLENGE_LOG_SOURCE
							)
						)
						onChallengeCompletedListener(updatedInstance)
					}
				}
			}
			_activeChallenges.value = activeChallengeList.toList()
		}

		// Award passive session XP
		progressionRepository.onTrackingSession(context, session)
	}

	private suspend fun fillEmptyChallengeSlots(context: Context) {
		lock.withLock {
			fillEmptyChallengeSlotsLocked(context)
		}
	}

	// Must be called while [lock] is already held
	private suspend fun fillEmptyChallengeSlotsLocked(context: Context) {
		if (activeChallengeList.size >= MAX_CHALLENGE_COUNT) return

		while (activeChallengeList.size < MAX_CHALLENGE_COUNT) {
			val newChallenge = activateRandomChallenge(context)
			if (newChallenge != null) {
				activeChallengeList.add(newChallenge)
			} else {
				break
			}
		}
		if (activeChallengeList.isNotEmpty()) {
			scheduleNextExpiry(context)
		}
	}

	private fun scheduleNextExpiry(context: Context) {
		val nextExpiry = activeChallengeList
			.filter { !it.isCompleted }
			.minOfOrNull { it.entity.endTime } ?: return
		ChallengeExpiredWorker.schedule(context, nextExpiry)
		logGame(
			LogData(
				message = "Scheduled next expiry at ${nextExpiry.formatAsDateTime()}",
				source = CHALLENGE_LOG_SOURCE
			)
		)
	}

	internal suspend fun checkExpiredChallenges(context: Context) {
		val now = Time.nowMillis
		lock.withLock {
			val expired = activeChallengeList.filter { it.entity.endTime <= now && !it.isCompleted }
			if (expired.isNotEmpty()) {
				val result = progressionRepository.onChallengesExpired(context, expired)
				logGame(
					LogData(
						message = "Expired ${result.expiredCount} challenges. Streak broken=${result.streakBroken}, freeze used=${result.freezeUsed}",
						source = CHALLENGE_LOG_SOURCE
					)
				)
				activeChallengeList.removeAll(expired.toSet())
				fillEmptyChallengeSlotsLocked(context)
				_activeChallenges.value = activeChallengeList.toList()
			}
		}
	}

	private suspend fun activateRandomChallenge(context: Context): ChallengeInstanceNew? {
		val activeTypes = activeChallengeList.map { it.entity.type }.toSet()
		val availableProcessors = registry.all.filter { it.type !in activeTypes }
		if (availableProcessors.isEmpty()) return null

		val processor = availableProcessors.random(Random)
		val now = Time.nowMillis
		val durationRange = processor.minDurationMultiplier..processor.maxDurationMultiplier
		val durationMult = Random.nextDouble(durationRange.start, durationRange.endInclusive)
		val duration = (processor.defaultDurationMs * durationMult).toLong()

		val difficulty = calculateDifficulty(context)

		val entity = ChallengeEntity(
			type = processor.type,
			startTime = now,
			endTime = now + duration,
			difficulty = difficulty,
			requiredValue = processor.defaultRequiredValue * durationMult,
		)

		val dao = ChallengeDatabase.database(context).challengeDao()
		val id = dao.insert(entity)
		val savedEntity = entity.copy(id = id)

		logGame(
			LogData(
				message = "Created ${processor.type.name} challenge, expires ${savedEntity.endTime.formatAsDateTime()}",
				source = CHALLENGE_LOG_SOURCE
			)
		)

		return ChallengeInstanceNew(savedEntity, processor)
	}

	/**
	 * Calculates difficulty based on the player's recent challenge completion rate.
	 * High success rate → harder challenges; low success rate → easier ones.
	 * Falls back to MEDIUM when no history is available.
	 */
	private suspend fun calculateDifficulty(context: Context): ChallengeDifficulty {
		val history = ChallengeDatabase.database(context).challengeHistoryDao().getAll()
		return difficultyFromCompletionRate(history.map { it.outcome })
	}

	companion object {
		internal const val MAX_CHALLENGE_COUNT = 3
		internal const val DIFFICULTY_HISTORY_WINDOW = 10

		/**
		 * Pure function: determines difficulty from a list of outcome strings.
		 * Takes the last [DIFFICULTY_HISTORY_WINDOW] outcomes and calculates completion rate.
		 */
		internal fun difficultyFromCompletionRate(outcomes: List<String>): ChallengeDifficulty {
			if (outcomes.isEmpty()) return ChallengeDifficulty.MEDIUM

			val recent = outcomes.takeLast(DIFFICULTY_HISTORY_WINDOW)
			val completedCount = recent.count { it == "COMPLETED" }
			val completionRate = completedCount.toDouble() / recent.size

			return when {
				completionRate >= 0.8 -> ChallengeDifficulty.VERY_HARD
				completionRate >= 0.6 -> ChallengeDifficulty.HARD
				completionRate >= 0.4 -> ChallengeDifficulty.MEDIUM
				completionRate >= 0.2 -> ChallengeDifficulty.EASY
				else -> ChallengeDifficulty.VERY_EASY
			}
		}
	}
}
