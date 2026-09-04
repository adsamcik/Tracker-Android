package com.adsamcik.tracker.game.session

import com.adsamcik.tracker.game.minigame.MiniGameConfiguration
import com.adsamcik.tracker.game.minigame.MiniGameConfigurations
import com.adsamcik.tracker.game.minigame.MiniGamePhase
import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.adsamcik.tracker.game.minigame.MiniGameSignal
import com.adsamcik.tracker.game.minigame.MiniGameSignalQuality
import com.adsamcik.tracker.game.minigame.MiniGameState
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import com.adsamcik.tracker.game.minigame.location.MiniGameLocationSample
import com.adsamcik.tracker.game.minigame.location.MiniGameLocationSource
import com.adsamcik.tracker.game.repository.GameRewardEnsureResult
import com.adsamcik.tracker.game.repository.GameRewardRejectionReason
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameSessionRuntimeTest {

	@Test
	fun `commands are serialized and pause resume never overlaps location subscriptions`() = runTest {
		val harness = RuntimeHarness(this)

		harness.runtime.submit(GameSessionCommand.Start(CONFIGURATION))
		runCurrent()
		harness.locationSource.subscribers shouldBe 1

		repeat(5) {
			harness.runtime.submit(GameSessionCommand.Pause)
			harness.runtime.submit(GameSessionCommand.Resume)
		}
		runCurrent()

		harness.locationSource.subscribers shouldBe 1
		harness.locationSource.maxConcurrentSubscribers shouldBe 1
		harness.publisher.states.filterIsInstance<GameSessionState.Paused>().shouldHaveSize(5)
		harness.factory.sessions.shouldHaveSize(1)
		harness.runtime.shutdown()
	}

	@Test
	fun `start followed immediately by pause is processed in intent order`() = runTest {
		val harness = RuntimeHarness(this)

		harness.runtime.submit(GameSessionCommand.Start(CONFIGURATION))
		harness.runtime.submit(GameSessionCommand.Pause)
		runCurrent()

		harness.publisher.states.last().shouldBeInstanceOf<GameSessionState.Paused>()
		harness.locationSource.subscribers shouldBe 0
		harness.terminalCalls shouldBe 0
		harness.runtime.shutdown()
	}

	@Test
	fun `pause stops active time and resume keeps the same engine`() = runTest {
		val harness = RuntimeHarness(this)
		harness.runtime.submit(GameSessionCommand.Start(CONFIGURATION))
		runCurrent()
		val session = harness.factory.sessions.single()

		harness.clock.elapsedMs = 5_000L
		harness.runtime.requestTick()
		runCurrent()
		session.lastElapsedMs shouldBe 5_000L

		harness.runtime.submit(GameSessionCommand.Pause)
		runCurrent()
		harness.clock.elapsedMs = 50_000L
		harness.runtime.requestTick()
		runCurrent()
		session.lastElapsedMs shouldBe 5_000L

		harness.runtime.submit(GameSessionCommand.Resume)
		runCurrent()
		harness.clock.elapsedMs = 53_000L
		harness.runtime.requestTick()
		runCurrent()

		harness.factory.sessions.shouldHaveSize(1)
		session.lastElapsedMs shouldBe 8_000L
		harness.runtime.shutdown()
	}

	@Test
	fun `screen lifecycle inactivity does not pause or finish a running session`() = runTest {
		val harness = RuntimeHarness(this)
		harness.runtime.submit(GameSessionCommand.Start(CONFIGURATION))
		runCurrent()

		harness.clock.elapsedMs = 90_000L
		harness.runtime.requestTick()
		runCurrent()

		harness.publisher.states.last().shouldBeInstanceOf<GameSessionState.Active>()
		harness.locationSource.subscribers shouldBe 1
		harness.persistence.commits.shouldHaveSize(0)
		harness.runtime.shutdown()
	}

	@Test
	fun `runtime shutdown cancels unfinished work without fabricating a score`() = runTest {
		val harness = RuntimeHarness(this)
		harness.runtime.submit(GameSessionCommand.Start(CONFIGURATION))
		runCurrent()
		harness.locationSource.subscribers shouldBe 1

		harness.runtime.shutdown()
		runCurrent()

		harness.locationSource.subscribers shouldBe 0
		harness.persistence.commits.shouldHaveSize(0)
		harness.publisher.states.none { it is GameSessionState.Finished } shouldBe true
	}

	@Test
	fun `finish without an accepted sample fails without score or reward`() = runTest {
		val harness = RuntimeHarness(this)
		harness.runtime.submit(GameSessionCommand.Start(CONFIGURATION))
		runCurrent()

		harness.runtime.submit(GameSessionCommand.Finish)
		runCurrent()

		val failed = harness.publisher.states.last().shouldBeInstanceOf<GameSessionState.Failed>()
		failed.reason shouldBe GameSessionFailureReason.LOCATION_UNAVAILABLE
		harness.persistence.commits.shouldHaveSize(0)
		harness.factory.sessions.single().endCalls shouldBe 1
		harness.runtime.shutdown()
	}

	@Test
	fun `natural completion remains single flight when finish commands arrive afterward`() = runTest {
		val harness = RuntimeHarness(this, finishAfterSamples = 1)
		harness.runtime.submit(GameSessionCommand.Start(CONFIGURATION))
		runCurrent()

		harness.locationSource.emit(SAMPLE)
		runCurrent()
		harness.runtime.submit(GameSessionCommand.Finish)
		harness.runtime.submit(GameSessionCommand.Finish)
		runCurrent()

		harness.persistence.commits.shouldHaveSize(1)
		harness.factory.sessions.single().endCalls shouldBe 1
		harness.publisher.states.filterIsInstance<GameSessionState.Finished>().shouldHaveSize(1)
		harness.terminalCalls shouldBe 1
		harness.runtime.shutdown()
	}

	@Test
	fun `racing explicit finalizers persist exactly once`() = runTest {
		val harness = RuntimeHarness(this)
		harness.runtime.submit(GameSessionCommand.Start(CONFIGURATION))
		runCurrent()
		harness.locationSource.emit(SAMPLE)
		runCurrent()

		repeat(5) { harness.runtime.submit(GameSessionCommand.Finish) }
		runCurrent()

		harness.persistence.commits.shouldHaveSize(1)
		harness.factory.sessions.single().endCalls shouldBe 1
		harness.publisher.states.filterIsInstance<GameSessionState.Finished>().shouldHaveSize(1)
		harness.runtime.shutdown()
	}

	@Test
	fun `personal best is loaded before configured session creation`() = runTest {
		val harness = RuntimeHarness(this)
		harness.persistence.personalBest = 12.5

		harness.runtime.submit(GameSessionCommand.Start(CONFIGURATION))
		runCurrent()

		harness.persistence.events shouldBe listOf("personal-best")
		harness.factory.personalBests shouldBe listOf(12.5)
		val active = harness.publisher.states.last().shouldBeInstanceOf<GameSessionState.Active>()
		active.snapshot.personalBest.scoreBeforeRun shouldBe 12.5
		harness.runtime.shutdown()
	}

	@Test
	fun `start while startup admission is closed cannot activate or later commit`() = runTest {
		val harness = RuntimeHarness(this)
		harness.persistence.admittedGeneration = null

		harness.runtime.submit(GameSessionCommand.Start(CONFIGURATION))
		runCurrent()

		val failed = harness.publisher.states.last().shouldBeInstanceOf<GameSessionState.Failed>()
		failed.reason shouldBe GameSessionFailureReason.PERSISTENCE_FAILED
		harness.factory.sessions shouldBe emptyList()
		harness.locationSource.subscribers shouldBe 0
		harness.persistence.commitAttempts shouldBe emptyList()
		harness.terminalCalls shouldBe 1
		harness.runtime.shutdown()
	}

	@Test
	fun `active run keeps its admitted generation and cannot commit after deletion`() = runTest {
		val harness = RuntimeHarness(this)
		harness.runtime.submit(GameSessionCommand.Start(CONFIGURATION))
		runCurrent()
		harness.locationSource.emit(SAMPLE)
		runCurrent()

		harness.persistence.acceptedGeneration = 2L
		harness.runtime.submit(GameSessionCommand.Finish)
		runCurrent()

		harness.persistence.commitAttempts.single().startupGeneration shouldBe 1L
		harness.persistence.commits shouldBe emptyList()
		val failed = harness.publisher.states.last().shouldBeInstanceOf<GameSessionState.Failed>()
		failed.reason shouldBe GameSessionFailureReason.PERSISTENCE_FAILED
		harness.runtime.shutdown()
	}

	private class RuntimeHarness(
		testScope: TestScope,
		finishAfterSamples: Int = Int.MAX_VALUE,
	) {
		val clock = FakeGameSessionClock()
		val factory = RecordingSessionFactory(finishAfterSamples)
		val locationSource = ControllableLocationSource()
		val persistence = RecordingPersistence()
		val publisher = RecordingPublisher()
		var terminalCalls: Int = 0
		private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
		val runtime = GameSessionRuntime(
			parentScope = testScope.backgroundScope,
			dispatcher = dispatcher,
			clock = clock,
			sessionFactory = factory,
			locationSource = locationSource,
			persistence = persistence,
			statePublisher = publisher,
			sessionIdFactory = GameSessionIdFactory { GameSessionId("session-1") },
			onTerminal = { terminalCalls++ },
		)
	}

	private class FakeGameSessionClock : GameSessionClock {
		var elapsedMs: Long = 0L
		var wallMs: Long = 1_700_000_000_000L
		override fun elapsedRealtimeMs(): Long = elapsedMs
		override fun currentTimeMillis(): Long = wallMs
	}

	private class RecordingSessionFactory(
		private val finishAfterSamples: Int,
	) : GameSessionFactory {
		val sessions = mutableListOf<RecordingSession>()
		val personalBests = mutableListOf<Double?>()

		override fun create(
			configuration: MiniGameConfiguration,
			personalBestBeforeRun: Double?,
		): PreparedGameSession {
			personalBests += personalBestBeforeRun
			val session = RecordingSession(configuration, personalBestBeforeRun, finishAfterSamples)
			sessions += session
			return PreparedGameSession(session, REQUEST)
		}
	}

	private class RecordingSession(
		configuration: MiniGameConfiguration,
		personalBest: Double?,
		private val finishAfterSamples: Int,
	) : MiniGameSession(configuration, personalBest) {
		private var mutableState = MiniGameState.IDLE
		private var samples = 0
		var endCalls = 0
		var lastElapsedMs = 0L

		override val state: MiniGameState get() = mutableState
		override val score: Double get() = samples.toDouble()
		override val statusText: String get() = "test"
		override val snapshot
			get() = buildSnapshot(
				phase = when (mutableState) {
					MiniGameState.IDLE -> MiniGamePhase.WAITING_TO_START
					MiniGameState.RUNNING -> MiniGamePhase.ACTIVE
					MiniGameState.WARNING -> MiniGamePhase.WARNING
					MiniGameState.FINISHED -> MiniGamePhase.COMPLETED
				},
				signal = if (samples == 0) {
					MiniGameSignal.UNKNOWN
				} else {
					MiniGameSignal(MiniGameSignalQuality.GOOD, 0L, false)
				},
				currentScore = score,
				visualPayload = MiniGameVisualPayload.Pending,
			)

		override fun onActiveElapsedTimeChanged(elapsedActiveTimeMs: Long) {
			super.onActiveElapsedTimeChanged(elapsedActiveTimeMs)
			lastElapsedMs = elapsedActiveTimeMs
		}

		override fun onLocationUpdate(
			latitude: Double,
			longitude: Double,
			speedMps: Float,
			accuracyM: Float,
			timestampMs: Long,
		) {
			if (accuracyM > 30f) return
			samples++
			mutableState = if (samples >= finishAfterSamples) {
				MiniGameState.FINISHED
			} else {
				MiniGameState.RUNNING
			}
		}

		override fun onSessionEnd() {
			endCalls++
			mutableState = MiniGameState.FINISHED
		}

		override fun calculatePoints(): Int = 42
	}

	private class ControllableLocationSource : MiniGameLocationSource {
		private val samples = MutableSharedFlow<MiniGameLocationSample>(extraBufferCapacity = 8)
		var subscribers: Int = 0
		var maxConcurrentSubscribers: Int = 0

		override fun samples(request: LocationRequest): Flow<MiniGameLocationSample> = flow {
			subscribers++
			maxConcurrentSubscribers = maxOf(maxConcurrentSubscribers, subscribers)
			try {
				samples.collect { emit(it) }
			} finally {
				subscribers--
			}
		}

		suspend fun emit(sample: MiniGameLocationSample) {
			samples.emit(sample)
		}
	}

	private class RecordingPersistence : GameSessionPersistence {
		var personalBest: Double? = null
		var admittedGeneration: Long? = 1L
		var acceptedGeneration: Long = 1L
		val commits = mutableListOf<GameSessionCommit>()
		val commitAttempts = mutableListOf<GameSessionCommit>()
		val events = mutableListOf<String>()

		override suspend fun admitSessionGeneration(): Long? = admittedGeneration

		override suspend fun loadPersonalBest(gameId: String): Double? {
			events += "personal-best"
			return personalBest
		}

		override suspend fun commit(commit: GameSessionCommit): GameRewardEnsureResult {
			commitAttempts += commit
			if (commit.startupGeneration != acceptedGeneration) {
				return GameRewardEnsureResult.Rejected(
					GameRewardRejectionReason.PERSISTENCE_UNAVAILABLE,
				)
			}
			commits += commit
			return GameRewardEnsureResult.Created
		}
	}

	private class RecordingPublisher : GameSessionStatePublisher {
		val states = mutableListOf<GameSessionState>()
		override fun publish(state: GameSessionState) {
			states += state
		}
	}

	private companion object {
		val CONFIGURATION = MiniGameConfigurations.DEFAULT_TERRITORY
		val REQUEST: LocationRequest =
			LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 2_000L).build()
		val SAMPLE = MiniGameLocationSample(
			latitude = 50.0876,
			longitude = 14.4213,
			speedMps = 1.2f,
			accuracyM = 5f,
			timestampMs = 1_700_000_000_000L,
		)
	}
}
