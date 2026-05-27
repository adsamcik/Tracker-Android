package com.adsamcik.tracker.game.challenge

import android.content.Context
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.game.CHALLENGE_LOG_SOURCE
import com.adsamcik.tracker.game.challenge.catalog.ChallengeCatalog
import com.adsamcik.tracker.game.challenge.data.ChallengeInstanceNew
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.game.challenge.engine.ChallengeEngine
import com.adsamcik.tracker.game.challenge.progression.ProgressionRepository
import com.adsamcik.tracker.game.challenge.worker.ChallengeExpiredWorker
import com.adsamcik.tracker.game.logGame
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.extension.formatAsDateTime
import com.adsamcik.tracker.shared.utils.extension.tryWithResultAndReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * Thin façade over [ChallengeEngine] + [ChallengeCatalog] that owns the UI-facing
 * [activeChallenges] `StateFlow`, the cold/loading/ready initialization state machine,
 * and the challenge lifecycle (activation + expiry scheduling).
 *
 * **Façade decision (p2-7):** kept as a thin coordinator instead of being deleted.
 *  - The engine is per-session evaluation only — it doesn't own activation, expiry, or UI state.
 *  - Splitting `activeChallenges: StateFlow` out into a separate `ActiveChallengesProvider`
 *    would just move three methods to a new class; the existing class is already small.
 *  - `awaitReady()` state machine is the right home here, not in the engine.
 * Future: if `ChallengeSignalProcessor` (p4-1) ships live progress, this façade should
 * forward `DomainEvent.ChallengeProgress` into the same StateFlow so UI sees a single
 * source of truth — no other refactor needed.
 *
 * All write side-effects (entity updates, history, XP, streak, personal records) live in
 * [ChallengeEngine] inside one Room transaction (see p2-3). This class never writes
 * directly to the database except for activation (new rows).
 */
@Singleton
class ChallengeManager @Inject constructor(
	private val progressionRepository: ProgressionRepository,
	private val dispatchers: DispatchersProvider,
	private val challengeDatabase: ChallengeDatabase,
	private val engine: ChallengeEngine,
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

	// ── State machine ──────────────────────────────────────────────────────────

	private sealed interface State {
		object Cold : State
		data class Loading(val job: Job) : State
		object Ready : State
		data class Failed(val cause: Throwable) : State
	}

	private val stateMutex = Mutex()
	private var state: State = State.Cold

	/**
	 * Suspend until the manager has loaded its initial state from the database.
	 * Idempotent: concurrent callers await the same [Loading] job rather than issuing
	 * duplicate DB reads. Callers after [Ready] return immediately.
	 * On failure the state transitions to [Failed] and any subsequent [awaitReady]
	 * call re-attempts the load.
	 */
	@AnyThread
	suspend fun awaitReady(context: Context) {
		while (true) {
			val loadingJob = stateMutex.withLock {
				when (val s = state) {
					State.Ready -> return
					is State.Loading -> s.job
					State.Cold, is State.Failed -> {
						val job = scope.launch {
							try {
								loadInitial(context)
								transitionTo(State.Ready)
							} catch (t: CancellationException) {
								transitionTo(State.Cold)
								throw t
							} catch (t: Throwable) {
								transitionTo(State.Failed(t))
							}
						}
						state = State.Loading(job)
						job
					}
				}
			}
			loadingJob.join()
			val current = stateMutex.withLock { state }
			if (current is State.Failed) throw current.cause
			// If Ready, the next iteration's fast-path returns; otherwise re-loop.
		}
	}

	private suspend fun loadInitial(context: Context) {
		val active = loadFromDb()
		lock.withLock {
			activeChallengeList.clear()
			activeChallengeList.addAll(active)
			fillEmptyChallengeSlotsLocked(context)
			_activeChallenges.value = activeChallengeList.toList()
		}
	}

	private suspend fun transitionTo(newState: State) {
		stateMutex.withLock { state = newState }
	}

	@WorkerThread
	private suspend fun loadFromDb(): List<ChallengeInstanceNew> {
		val dao = challengeDatabase.challengeDao()
		val now = Time.nowMillis
		return dao.getActive(now).mapNotNull { entity ->
			tryWithResultAndReport({ null }) {
				ChallengeInstanceNew.fromDefinition(entity, ChallengeCatalog.byType(entity.type))
			}
		}
	}

	/**
	 * Kicks off the state-machine load eagerly. If already [Ready] the [awaitReady] call
	 * inside returns immediately. The optional [onInitialized] callback fires once [Ready]
	 * (or after a failure — callers that care about errors should use [awaitReady] directly).
	 */
	@AnyThread
	fun initialize(context: Context, onInitialized: (() -> Unit)? = null) {
		scope.launch {
			try {
				awaitReady(context)
			} finally {
				onInitialized?.invoke()
			}
		}
	}

	@WorkerThread
	suspend fun processSession(
		context: Context,
		session: TrackerSession,
		onChallengeCompletedListener: (ChallengeInstanceNew) -> Unit
	) {
		awaitReady(context)

		val result = engine.applySession(session)

		// Refresh the in-memory list from the engine's updates so the StateFlow reflects new progress.
		if (result.updates.isNotEmpty()) {
			lock.withLock {
				val byId = result.updates.associateBy { it.id }
				for (i in activeChallengeList.indices) {
					val current = activeChallengeList[i]
					val replacement = byId[current.entity.id]
					if (replacement != null && replacement != current.entity) {
						activeChallengeList[i] = current.copy(entity = replacement)
					}
				}
				_activeChallenges.value = activeChallengeList.toList()
			}
		}

		// Fire completion callbacks AFTER the StateFlow update so observers see the completed state.
		for (entity in result.newlyCompleted) {
			val def = ChallengeCatalog.byType(entity.type)
			val instance = ChallengeInstanceNew.fromDefinition(entity, def)
			logGame(
				LogData(
					message = "Challenge completed: ${instance.getTitle(context)}",
					source = CHALLENGE_LOG_SOURCE
				)
			)
			onChallengeCompletedListener(instance)
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
		awaitReady(context)
		val now = Time.nowMillis
		lock.withLock {
			val expired = activeChallengeList.filter { it.entity.endTime <= now && !it.isCompleted }
			if (expired.isNotEmpty()) {
				val result = progressionRepository.onChallengesExpired(expired)
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
		val availableDefinitions = ChallengeCatalog.definitions.filter { it.type !in activeTypes }
		if (availableDefinitions.isEmpty()) return null

		val definition = availableDefinitions.random(Random)
		val now = Time.nowMillis
		val durationMult = Random.nextDouble(
			definition.minDurationMultiplier,
			definition.maxDurationMultiplier,
		)
		val duration = (definition.defaultDurationMs * durationMult).toLong()

		val difficulty = calculateDifficulty()
		val targetRandomMult = Random.nextDouble(
			definition.minTargetMultiplier,
			definition.maxTargetMultiplier,
		)
		val difficultyMult = definition.difficultyTargetMultiplier(difficulty)
		val requiredValue = definition.defaultRequiredValue * targetRandomMult * difficultyMult

		val entity = ChallengeEntity(
			type = definition.type,
			startTime = now,
			endTime = now + duration,
			difficulty = difficulty,
			// Target now scales with difficulty (per-type curve) AND independent target
			// randomness, decoupled from duration randomness. Previously `requiredValue =
			// defaultRequiredValue * durationMult` double-counted duration as difficulty.
			requiredValue = requiredValue,
		)

		val dao = challengeDatabase.challengeDao()
		val id = dao.insert(entity)
		val savedEntity = entity.copy(id = id)

		logGame(
			LogData(
				message = "Created ${definition.type.name} challenge, expires ${savedEntity.endTime.formatAsDateTime()}",
				source = CHALLENGE_LOG_SOURCE
			)
		)

		return ChallengeInstanceNew.fromDefinition(savedEntity, definition)
	}

	/**
	 * Calculates difficulty based on the player's recent challenge completion rate.
	 * High success rate → harder challenges; low success rate → easier ones.
	 * Falls back to MEDIUM when no history is available.
	 *
	 * Uses an SQL aggregate over the last [DIFFICULTY_HISTORY_WINDOW] history rows
	 * (returns null if history is empty).
	 */
	private suspend fun calculateDifficulty(): ChallengeDifficulty {
		val rate = challengeDatabase
			.challengeHistoryDao()
			.getRecentCompletionRate(DIFFICULTY_HISTORY_WINDOW)
		return difficultyFromCompletionRate(rate)
	}

	companion object {
		internal const val MAX_CHALLENGE_COUNT = 3
		internal const val DIFFICULTY_HISTORY_WINDOW = 10

		/**
		 * Pure function: determines difficulty from a list of outcome strings.
		 * Kept for backwards compatibility with [DifficultyCalculationTest]; new
		 * call-sites should use the [Double] overload backed by the SQL aggregate.
		 */
		internal fun difficultyFromCompletionRate(outcomes: List<String>): ChallengeDifficulty {
			if (outcomes.isEmpty()) return ChallengeDifficulty.MEDIUM

			val recent = outcomes.takeLast(DIFFICULTY_HISTORY_WINDOW)
			val completedCount = recent.count { it == "COMPLETED" }
			val completionRate = completedCount.toDouble() / recent.size

			return difficultyFromCompletionRate(completionRate)
		}

		/**
		 * Pure function: determines difficulty from a precomputed completion rate.
		 * Null (no history) collapses to MEDIUM for parity with the [List] overload.
		 */
		internal fun difficultyFromCompletionRate(rate: Double?): ChallengeDifficulty {
			if (rate == null) return ChallengeDifficulty.MEDIUM
			return when {
				rate >= 0.8 -> ChallengeDifficulty.VERY_HARD
				rate >= 0.6 -> ChallengeDifficulty.HARD
				rate >= 0.4 -> ChallengeDifficulty.MEDIUM
				rate >= 0.2 -> ChallengeDifficulty.EASY
				else -> ChallengeDifficulty.VERY_EASY
			}
		}
	}
}
