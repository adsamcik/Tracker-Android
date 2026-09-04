package com.adsamcik.tracker.game.session

import android.os.SystemClock
import com.adsamcik.tracker.game.minigame.MiniGameCompletionOutcome
import com.adsamcik.tracker.game.minigame.MiniGameConfiguration
import com.adsamcik.tracker.game.minigame.MiniGamePersonalBestComparison
import com.adsamcik.tracker.game.minigame.MiniGamePhase
import com.adsamcik.tracker.game.minigame.MiniGameRegistry
import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.adsamcik.tracker.game.minigame.MiniGameSignal
import com.adsamcik.tracker.game.minigame.MiniGameSignalQuality
import com.adsamcik.tracker.game.minigame.MiniGameSnapshot
import com.adsamcik.tracker.game.minigame.MiniGameState
import com.adsamcik.tracker.game.minigame.OutrunConfiguration
import com.adsamcik.tracker.game.minigame.TerritoryConfiguration
import com.adsamcik.tracker.game.minigame.ZenWalkConfiguration
import com.adsamcik.tracker.game.minigame.FuseRunConfiguration
import com.adsamcik.tracker.game.minigame.fuserun.FuseRunSession
import com.adsamcik.tracker.game.minigame.location.MiniGameLocationSample
import com.adsamcik.tracker.game.minigame.location.MiniGameLocationSource
import com.adsamcik.tracker.game.minigame.outrun.OutrunSession
import com.adsamcik.tracker.game.minigame.SwitchbackConfiguration
import com.adsamcik.tracker.game.minigame.switchback.SwitchbackSession
import com.adsamcik.tracker.game.minigame.territory.TerritorySession
import com.adsamcik.tracker.game.minigame.zenwalk.ZenWalkSession
import com.adsamcik.tracker.game.repository.GameReward
import com.adsamcik.tracker.game.repository.GameRewardEnsureResult
import com.adsamcik.tracker.game.repository.GameRewardRejectionReason
import com.adsamcik.tracker.game.repository.miniGameRewardId
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.MiniGameScoreDao
import com.adsamcik.tracker.shared.base.database.data.MiniGameScoreEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.google.android.gms.location.LocationRequest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface GameSessionClock {
	fun elapsedRealtimeMs(): Long
	fun currentTimeMillis(): Long
}

internal object SystemGameSessionClock : GameSessionClock {
	override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
	override fun currentTimeMillis(): Long = Time.nowMillis
}

internal fun interface GameSessionIdFactory {
	fun create(): GameSessionId
}

internal object UuidGameSessionIdFactory : GameSessionIdFactory {
	override fun create(): GameSessionId = GameSessionId(UUID.randomUUID().toString())
}

internal data class GameSessionStateUpdate(
	val state: GameSessionState,
	val isCommandTransition: Boolean,
)

internal data class PreparedGameSession(
	val session: MiniGameSession,
	val locationRequest: LocationRequest,
)

internal fun interface GameSessionFactory {
	fun create(
		configuration: MiniGameConfiguration,
		personalBestBeforeRun: Double?,
	): PreparedGameSession
}

/**
 * Creates the already-configured engines without changing their shared contract.
 */
internal class ConfiguredGameSessionFactory(
	private val registry: MiniGameRegistry,
) : GameSessionFactory {
	override fun create(
		configuration: MiniGameConfiguration,
		personalBestBeforeRun: Double?,
	): PreparedGameSession {
		val game = requireNotNull(registry.findById(configuration.gameId)) {
			"Unknown mini-game '${configuration.gameId}'"
		}
		require(configuration in game.supportedConfigurations) {
			"Unsupported configuration for mini-game '${configuration.gameId}'"
		}
		val session = when (configuration) {
			is OutrunConfiguration -> OutrunSession(configuration, personalBestBeforeRun)
			is TerritoryConfiguration -> TerritorySession(configuration, personalBestBeforeRun)
			is ZenWalkConfiguration -> ZenWalkSession(configuration, personalBestBeforeRun)
			is FuseRunConfiguration -> FuseRunSession(configuration, personalBestBeforeRun)
			is SwitchbackConfiguration -> SwitchbackSession(configuration, personalBestBeforeRun)
		}
		return PreparedGameSession(session, game.desiredLocationRequest())
	}
}

internal data class GameSessionCommit(
	val sessionId: GameSessionId,
	val startupGeneration: Long,
	val configuration: MiniGameConfiguration,
	val score: Double,
	val points: Int,
	val completedAtMs: Long,
)

internal interface GameSessionPersistence {
	suspend fun admitSessionGeneration(): Long?
	suspend fun loadPersonalBest(gameId: String): Double?
	suspend fun commit(commit: GameSessionCommit): GameRewardEnsureResult
}

internal class DefaultGameSessionPersistence(
	private val scoreDao: MiniGameScoreDao,
	private val dispatchers: DispatchersProvider,
	private val trackingStartupGate: TrackingStartupGate,
	private val ensureRewardInsideAcceptedGeneration: suspend (
		reward: GameReward,
		acceptedGeneration: Long,
	) -> GameRewardEnsureResult,
) : GameSessionPersistence {
	override suspend fun admitSessionGeneration(): Long? {
		val expectedGeneration = trackingStartupGate.currentGeneration
		return trackingStartupGate.withReadyGenerationOperation(expectedGeneration) {
			expectedGeneration
		}
	}

	override suspend fun loadPersonalBest(gameId: String): Double? =
		withContext(dispatchers.io) {
			scoreDao.getPersonalBest(gameId)
		}

	override suspend fun commit(commit: GameSessionCommit): GameRewardEnsureResult {
		val expectedGeneration = commit.startupGeneration
		val reward = GameReward(
			rewardId = miniGameRewardId(
				gameId = commit.configuration.gameId,
				earnedAtMs = commit.completedAtMs,
			),
			gameId = commit.configuration.gameId,
			points = commit.points,
			earnedAtMs = commit.completedAtMs,
		)
		return trackingStartupGate.withReadyGenerationOperation(expectedGeneration) {
			withContext(dispatchers.io) {
				scoreDao.insert(
					MiniGameScoreEntity(
						gameId = commit.configuration.gameId,
						score = commit.score,
						xpAwarded = commit.points,
						playedAt = commit.completedAtMs,
					),
				)
			}
			ensureRewardInsideAcceptedGeneration(reward, expectedGeneration)
		} ?: GameRewardEnsureResult.Rejected(
			GameRewardRejectionReason.PERSISTENCE_UNAVAILABLE,
		)
	}
}

/**
 * Serialized, service-owned mini-game runtime. Location and ticker producers
 * only enqueue events; the event loop is the sole mutator of the engine.
 */
internal class GameSessionRuntime(
	parentScope: CoroutineScope,
	dispatcher: CoroutineDispatcher,
	private val clock: GameSessionClock,
	private val sessionFactory: GameSessionFactory,
	private val locationSource: MiniGameLocationSource,
	private val persistence: GameSessionPersistence,
	private val statePublisher: GameSessionStatePublisher,
	private val sessionIdFactory: GameSessionIdFactory = UuidGameSessionIdFactory,
	private val onStateUpdate: (GameSessionStateUpdate) -> Unit = {},
	private val onTerminal: () -> Unit = {},
	private val tickerIntervalMs: Long = DEFAULT_TICKER_INTERVAL_MS,
) {
	private val runtimeJob = SupervisorJob(parentScope.coroutineContext[Job])
	private val scope = CoroutineScope(
		parentScope.coroutineContext.withDispatcher(dispatcher) + runtimeJob,
	)
	private val events = Channel<RuntimeEvent>(Channel.UNLIMITED)
	private val activePresent = AtomicBoolean(false)
	private var active: ActiveSession? = null
	private var locationGeneration: Long = 0L
	private var terminalNotificationSent: Boolean = false

	init {
		scope.launch {
			for (event in events) {
				try {
					process(event)
				} catch (cancellation: CancellationException) {
					throw cancellation
				} catch (_: Throwable) {
					val current = active
					if (current == null) {
						fail(null, GameSessionFailureReason.INTERNAL_ERROR)
					} else {
						cancelWithoutScore(current, GameSessionFailureReason.INTERNAL_ERROR)
					}
				}
			}
		}
	}

	val hasActiveSession: Boolean
		get() = activePresent.get()

	fun submit(command: GameSessionCommand): Boolean =
		events.trySend(RuntimeEvent.Command(command)).isSuccess

	internal fun requestTick(): Boolean =
		events.trySend(RuntimeEvent.Tick(active?.sessionId)).isSuccess

	fun shutdown() {
		runtimeJob.cancel()
		events.close()
		active = null
		activePresent.set(false)
	}

	private suspend fun process(event: RuntimeEvent) {
		when (event) {
			is RuntimeEvent.Command -> processCommand(event.command)
			is RuntimeEvent.Location -> processLocation(event)
			is RuntimeEvent.Tick -> processTick(event.sessionId)
			is RuntimeEvent.LocationFailed -> processLocationFailure(event)
		}
	}

	private suspend fun processCommand(command: GameSessionCommand) {
		when (command) {
			is GameSessionCommand.Start -> start(command.configuration)
			GameSessionCommand.Pause -> active?.let { pause() } ?: notifyTerminal()
			GameSessionCommand.Resume -> active?.let { resume() } ?: notifyTerminal()
			GameSessionCommand.Finish -> active?.let { finalize(it) } ?: notifyTerminal()
		}
	}

	private suspend fun start(configuration: MiniGameConfiguration) {
		active?.let {
			publish(it.toState(), isCommandTransition = true)
			return
		}
		terminalNotificationSent = false
		publish(GameSessionState.Starting(configuration), isCommandTransition = true)
		val startupGeneration = try {
			persistence.admitSessionGeneration()
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Throwable) {
			null
		}
		if (startupGeneration == null) {
			fail(configuration, GameSessionFailureReason.PERSISTENCE_FAILED)
			return
		}

		val personalBest = try {
			persistence.loadPersonalBest(configuration.gameId)
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Throwable) {
			fail(configuration, GameSessionFailureReason.PERSISTENCE_FAILED)
			return
		}
		val prepared = try {
			sessionFactory.create(configuration, personalBest)
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: IllegalArgumentException) {
			fail(configuration, GameSessionFailureReason.INVALID_COMMAND)
			return
		} catch (_: Throwable) {
			fail(configuration, GameSessionFailureReason.INTERNAL_ERROR)
			return
		}

		val now = clock.elapsedRealtimeMs()
		val newActive = ActiveSession(
			sessionId = sessionIdFactory.create(),
			startupGeneration = startupGeneration,
			configuration = configuration,
			session = prepared.session,
			locationRequest = prepared.locationRequest,
			activeSinceElapsedMs = now,
		)
		active = newActive
		activePresent.set(true)
		updateEngineElapsed(newActive)
		publish(newActive.toState(), isCommandTransition = true)
		startLocationCollection(newActive)
		startTicker(newActive)
	}

	private suspend fun pause() {
		val current = active ?: return
		if (current.isPaused || current.finalizationStarted.get()) return
		updateEngineElapsed(current, freezeActiveWindow = true)
		current.isPaused = true
		locationGeneration++
		current.locationJob?.cancelAndJoin()
		current.locationJob = null
		publish(current.toState(), isCommandTransition = true)
	}

	private fun resume() {
		val current = active ?: return
		if (!current.isPaused || current.finalizationStarted.get()) return
		current.isPaused = false
		current.activeSinceElapsedMs = clock.elapsedRealtimeMs()
		startLocationCollection(current)
		updateEngineElapsed(current)
		publish(current.toState(), isCommandTransition = true)
	}

	private suspend fun processLocation(event: RuntimeEvent.Location) {
		val current = active ?: return
		if (
			current.sessionId != event.sessionId ||
			current.isPaused ||
			event.generation != locationGeneration ||
			current.finalizationStarted.get()
		) {
			return
		}

		updateEngineElapsed(current)
		current.session.onLocationUpdate(
			latitude = event.sample.latitude,
			longitude = event.sample.longitude,
			speedMps = event.sample.speedMps,
			accuracyM = event.sample.accuracyM,
			timestampMs = event.sample.timestampMs,
		)
		val snapshot = current.session.snapshot
		if (
			snapshot.signal.quality == MiniGameSignalQuality.GOOD ||
			snapshot.signal.quality == MiniGameSignalQuality.FAIR
		) {
			current.hasAcceptedSample = true
			current.lastAcceptedSampleElapsedMs = clock.elapsedRealtimeMs()
		}

		if (current.session.state == MiniGameState.FINISHED) {
			finalize(current)
		} else {
			publish(current.toState(), isCommandTransition = false)
		}
	}

	private fun processTick(sessionId: GameSessionId?) {
		val current = active ?: return
		if (sessionId != null && current.sessionId != sessionId) return
		updateEngineElapsed(current)
		publish(current.toState(), isCommandTransition = false)
	}

	private suspend fun processLocationFailure(event: RuntimeEvent.LocationFailed) {
		val current = active ?: return
		if (
			current.sessionId != event.sessionId ||
			event.generation != locationGeneration ||
			current.finalizationStarted.get()
		) {
			return
		}
		cancelWithoutScore(current, event.reason)
	}

	private fun startLocationCollection(current: ActiveSession) {
		check(current.locationJob?.isActive != true) {
			"Only one game-owned location collector may be active"
		}
		val generation = ++locationGeneration
		current.locationJob = scope.launch {
			try {
				locationSource.samples(current.locationRequest).collect { sample ->
					events.send(RuntimeEvent.Location(current.sessionId, generation, sample))
				}
			} catch (cancellation: CancellationException) {
				throw cancellation
			} catch (_: SecurityException) {
				events.send(
					RuntimeEvent.LocationFailed(
						current.sessionId,
						generation,
						GameSessionFailureReason.PERMISSION_REQUIRED,
					),
				)
			} catch (_: Throwable) {
				events.send(
					RuntimeEvent.LocationFailed(
						current.sessionId,
						generation,
						GameSessionFailureReason.LOCATION_UNAVAILABLE,
					),
				)
			}
		}
	}

	private fun startTicker(current: ActiveSession) {
		current.tickerJob = scope.launch {
			while (isActive) {
				delay(tickerIntervalMs)
				events.send(RuntimeEvent.Tick(current.sessionId))
			}
		}
	}

	private suspend fun finalize(current: ActiveSession) {
		if (!current.finalizationStarted.compareAndSet(false, true)) return
		updateEngineElapsed(current, freezeActiveWindow = true)
		publish(
			GameSessionState.Finishing(
				current.sessionId,
				current.configuration,
				current.snapshot(MiniGamePhase.COMPLETED),
			),
			isCommandTransition = true,
		)
		locationGeneration++
		current.locationJob?.cancelAndJoin()
		current.locationJob = null
		current.tickerJob?.cancelAndJoin()
		current.tickerJob = null
		current.freezeOnce()

		if (!current.hasAcceptedSample) {
			completeFailure(current, GameSessionFailureReason.LOCATION_UNAVAILABLE)
			return
		}

		val finalScore = current.session.score
		val points = current.session.calculatePoints()
		if (!finalScore.isFinite() || finalScore < 0.0 || points < 0) {
			completeFailure(current, GameSessionFailureReason.INTERNAL_ERROR)
			return
		}
		val completedAtMs = clock.currentTimeMillis().coerceAtLeast(0L)
		val commit = GameSessionCommit(
			sessionId = current.sessionId,
			startupGeneration = current.startupGeneration,
			configuration = current.configuration,
			score = finalScore,
			points = points,
			completedAtMs = completedAtMs,
		)
		val rewardResult = try {
			persistence.commit(commit)
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Throwable) {
			completeFailure(current, GameSessionFailureReason.PERSISTENCE_FAILED)
			return
		}
		if (
			rewardResult is GameRewardEnsureResult.Rejected ||
			rewardResult == GameRewardEnsureResult.Unsupported
		) {
			completeFailure(current, GameSessionFailureReason.PERSISTENCE_FAILED)
			return
		}

		val result = GameSessionResult(
			sessionId = current.sessionId,
			configuration = current.configuration,
			finalScore = finalScore,
			pointsAwarded = points,
			completedAtMs = completedAtMs,
			completionOutcome = current.session.snapshot.personalBest.toCompletionOutcome(),
		)
		active = null
		activePresent.set(false)
		publish(GameSessionState.Finished(result), isCommandTransition = true)
		notifyTerminal()
	}

	private suspend fun cancelWithoutScore(
		current: ActiveSession,
		reason: GameSessionFailureReason,
	) {
		locationGeneration++
		current.locationJob?.cancelAndJoin()
		current.tickerJob?.cancelAndJoin()
		completeFailure(current, reason)
	}

	private fun completeFailure(
		current: ActiveSession,
		reason: GameSessionFailureReason,
	) {
		active = null
		activePresent.set(false)
		publish(GameSessionState.Failed(current.configuration, reason), isCommandTransition = true)
		notifyTerminal()
	}

	private fun fail(
		configuration: MiniGameConfiguration?,
		reason: GameSessionFailureReason,
	) {
		active = null
		activePresent.set(false)
		publish(GameSessionState.Failed(configuration, reason), isCommandTransition = true)
		notifyTerminal()
	}

	private fun notifyTerminal() {
		if (terminalNotificationSent) return
		terminalNotificationSent = true
		onTerminal()
	}

	private fun updateEngineElapsed(
		current: ActiveSession,
		freezeActiveWindow: Boolean = false,
	) {
		val activeSince = current.activeSinceElapsedMs
		if (activeSince != null) {
			val now = clock.elapsedRealtimeMs()
			val window = (now - activeSince).coerceAtLeast(0L)
			val elapsed = current.accumulatedActiveMs + window
			current.session.onActiveElapsedTimeChanged(elapsed)
			if (freezeActiveWindow) {
				current.accumulatedActiveMs = elapsed
				current.activeSinceElapsedMs = null
			}
		} else {
			current.session.onActiveElapsedTimeChanged(current.accumulatedActiveMs)
		}
	}

	private fun publish(state: GameSessionState, isCommandTransition: Boolean) {
		statePublisher.publish(state)
		runCatching {
			onStateUpdate(GameSessionStateUpdate(state, isCommandTransition))
		}
	}

	private inner class ActiveSession(
		val sessionId: GameSessionId,
		val startupGeneration: Long,
		val configuration: MiniGameConfiguration,
		val session: MiniGameSession,
		val locationRequest: LocationRequest,
		var activeSinceElapsedMs: Long?,
	) {
		var accumulatedActiveMs: Long = 0L
		var isPaused: Boolean = false
		var hasAcceptedSample: Boolean = false
		var lastAcceptedSampleElapsedMs: Long? = null
		var locationJob: Job? = null
		var tickerJob: Job? = null
		var isFrozen: Boolean = false
		val finalizationStarted = AtomicBoolean(false)

		fun freezeOnce() {
			if (isFrozen) return
			isFrozen = true
			session.onSessionEnd()
		}

		fun toState(): GameSessionState {
			val snapshot = snapshot(if (isPaused) MiniGamePhase.PAUSED else null)
			return if (isPaused) {
				GameSessionState.Paused(sessionId, configuration, snapshot)
			} else {
				GameSessionState.Active(sessionId, configuration, snapshot)
			}
		}

		fun snapshot(forcedPhase: MiniGamePhase?): MiniGameSnapshot {
			val base = session.snapshot
			val acceptedAt = lastAcceptedSampleElapsedMs
			val signal = if (acceptedAt == null) {
				base.signal
			} else {
				val ageMs = (clock.elapsedRealtimeMs() - acceptedAt).coerceAtLeast(0L)
				MiniGameSignal(
					quality = base.signal.quality,
					ageMs = ageMs,
					isStale = ageMs >= SIGNAL_STALE_AFTER_MS,
				)
			}
			return base.copy(
				phase = forcedPhase ?: base.phase,
				signal = signal,
			)
		}
	}

	private sealed interface RuntimeEvent {
		data class Command(val command: GameSessionCommand) : RuntimeEvent
		data class Location(
			val sessionId: GameSessionId,
			val generation: Long,
			val sample: MiniGameLocationSample,
		) : RuntimeEvent
		data class Tick(val sessionId: GameSessionId?) : RuntimeEvent
		data class LocationFailed(
			val sessionId: GameSessionId,
			val generation: Long,
			val reason: GameSessionFailureReason,
		) : RuntimeEvent
	}

	private companion object {
		const val DEFAULT_TICKER_INTERVAL_MS: Long = 1_000L
		const val SIGNAL_STALE_AFTER_MS: Long = 15_000L
	}
}

private fun CoroutineContext.withDispatcher(dispatcher: CoroutineDispatcher): CoroutineContext =
	minusKey(kotlin.coroutines.ContinuationInterceptor) + dispatcher

private fun com.adsamcik.tracker.game.minigame.MiniGamePersonalBest.toCompletionOutcome():
	MiniGameCompletionOutcome = when (comparison) {
	MiniGamePersonalBestComparison.FIRST_RUN -> MiniGameCompletionOutcome.FirstRun
	MiniGamePersonalBestComparison.AHEAD -> MiniGameCompletionOutcome.PersonalBest(difference)
	MiniGamePersonalBestComparison.TIED -> MiniGameCompletionOutcome.TiedBest
	MiniGamePersonalBestComparison.BEHIND -> MiniGameCompletionOutcome.BelowBest(difference)
}
