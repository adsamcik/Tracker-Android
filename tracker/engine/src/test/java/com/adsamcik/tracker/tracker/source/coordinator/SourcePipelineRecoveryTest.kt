package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceProjectionStateDao
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.tracker.api.ActivityAutomationDeliveryResult
import com.adsamcik.tracker.tracker.api.ActivityAutomationStartContext
import com.adsamcik.tracker.tracker.source.activity.ActivityCapturedFactProjectionLane
import com.adsamcik.tracker.tracker.source.cell.CellSessionFactProjectionLane
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationOutboxDispatcher
import com.adsamcik.tracker.tracker.source.projection.ActivityControlRetentionMaintenance
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationDrainResult
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationEffectConsumer
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationEffectValidation
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationEffectValidator
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationProjection
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationProjectionLane
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationStartPermit
import com.adsamcik.tracker.tracker.source.projection.ProjectionDispatcher
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.projection.PressureSessionFactProjectionLane
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactProjectionLane
import com.adsamcik.tracker.tracker.source.projection.legacy.LegacyV27ProjectionRecovery
import com.adsamcik.tracker.tracker.source.projection.legacy.LegacyV27ProjectionRecoveryResult
import com.adsamcik.tracker.tracker.source.wifi.WifiSessionFactProjectionLane
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifySequence
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourcePipelineRecoveryTest {
	private val legacy = mockk<LegacyV27ProjectionRecovery>()
	private val coordinator = mockk<TrackingCoordinator>()
	private val activityLane = mockk<ActivityAutomationProjectionLane>()
	private val activityEffects = mockk<ActivityAutomationOutboxDispatcher>()
	private val subject = SourcePipelineRecovery(
		legacy,
		coordinator,
		activityLane,
		activityEffects,
	)

	@Test
	fun `background Wi-Fi failure cannot wait for or suppress background Cell recovery`() = runTest {
		val projections = mockk<ProjectionDispatcher>()
		val wifiEntered = CompletableDeferred<Unit>()
		val releaseWifi = CompletableDeferred<Unit>()
		coEvery { projections.drainAmbientRadioProjection(SourceKind.WIFI, any()) } coAnswers {
			wifiEntered.complete(Unit)
			releaseWifi.await()
			CoordinatorDrainResult.ProjectionFailed(0L, 0, "ambient-wifi-facts", 1L)
		}
		coEvery { projections.drainAmbientRadioProjection(SourceKind.CELL, any()) } returns
			CoordinatorDrainResult.Complete(2L, 1)
		val recovery = radioRecovery(projections, backgroundScope)

		recovery.requestWifiSessionFactDrain()
		recovery.requestCellSessionFactDrain()
		runCurrent()
		wifiEntered.isCompleted shouldBe true
		coVerify(exactly = 1) { projections.drainAmbientRadioProjection(SourceKind.CELL, any()) }
		coVerify(exactly = 0) { coordinator.drainAvailable(any()) }
		coVerify(exactly = 0) { activityLane.drainAvailable() }
		releaseWifi.complete(Unit)
		runCurrent()
	}

	@Test
	fun `background radio hints conflate per source and never spin after a failure`() = runTest {
		val projections = mockk<ProjectionDispatcher>()
		val entered = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		var attempts = 0
		coEvery { projections.drainAmbientRadioProjection(SourceKind.WIFI, any()) } coAnswers {
			attempts++
			if (attempts == 1) { entered.complete(Unit); release.await() }
			CoordinatorDrainResult.ProjectionFailed(0L, 0, "ambient-wifi-facts", 1L)
		}
		val recovery = radioRecovery(projections, backgroundScope)
		repeat(100) { recovery.requestWifiSessionFactDrain() }
		runCurrent()
		entered.isCompleted shouldBe true
		repeat(100) { recovery.requestWifiSessionFactDrain() }
		runCurrent()
		release.complete(Unit)
		runCurrent()
		attempts shouldBe 2
		coVerify(exactly = 0) { projections.drainAmbientRadioProjection(SourceKind.CELL, any()) }
	}

	@Test
	fun `startup registers both background consumers before queuing independent retained drains`() = runTest {
		val projections = mockk<ProjectionDispatcher>()
		var registered = false
		coEvery { legacy.recover() } returns LegacyV27ProjectionRecoveryResult.NotRequired
		coEvery { projections.registerAmbientRadioProjections() } coAnswers { registered = true }
		coEvery { projections.drainAmbientRadioProjection(any(), any()) } coAnswers {
			registered shouldBe true
			CoordinatorDrainResult.Complete(0L, 0)
		}
		val recovery = radioRecovery(projections, backgroundScope)

		recovery.recoverStartupAuthority() shouldBe LegacyV27ProjectionRecoveryResult.NotRequired
		registered shouldBe true
		runCurrent()
		coVerify(exactly = 1) { projections.drainAmbientRadioProjection(SourceKind.WIFI, any()) }
		coVerify(exactly = 1) { projections.drainAmbientRadioProjection(SourceKind.CELL, any()) }
		coVerify(exactly = 0) { coordinator.drainAvailable(any()) }
	}

	@Test
	fun `background startup hint waits for Ready instead of being lost before the gate opens`() = runTest {
		val projections = mockk<ProjectionDispatcher>()
		val gate = mockk<TrackingStartupGate>()
		val ready = CompletableDeferred<Unit>()
		var opened = false
		every { gate.isReady } answers { opened }
		every { gate.currentGeneration } returns 3L
		every { gate.isReadyGeneration(3L) } answers { opened }
		every { gate.withRetainedDataGeneration<Boolean>(3L, any()) } answers {
			if (opened) secondArg<() -> Boolean>().invoke() else null
		}
		coEvery { gate.awaitReady() } coAnswers {
			ready.await()
			TrackingStartupResult.Ready(false, 0L)
		}
		coEvery { gate.withReadyGenerationTransfer<Unit>(3L, any()) } coAnswers {
			opened shouldBe true
			secondArg<suspend () -> Unit>().invoke()
		}
		coEvery { projections.drainAmbientRadioProjection(SourceKind.WIFI, any()) } returns
			CoordinatorDrainResult.Complete(1L, 1)
		val recovery = radioRecovery(projections, backgroundScope, javax.inject.Provider { gate })

		recovery.requestWifiSessionFactDrain()
		runCurrent()
		coVerify(exactly = 0) { projections.drainAmbientRadioProjection(any(), any()) }
		opened = true
		ready.complete(Unit)
		runCurrent()
		coVerify(exactly = 1) { projections.drainAmbientRadioProjection(SourceKind.WIFI, any()) }
	}

	@Test
	fun `deletion waits for background projection and an old queued hint cannot write across closure`() = runTest {
		val projections = mockk<ProjectionDispatcher>()
		val gate = TestTrackingStartupGate(7L)
		val entered = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		coEvery { projections.drainAmbientRadioProjection(SourceKind.WIFI, any()) } coAnswers {
			try {
				entered.complete(Unit)
				awaitCancellation()
			} finally {
				withContext(NonCancellable) { release.await() }
			}
		}
		val recovery = radioRecovery(projections, backgroundScope, javax.inject.Provider { gate })
		recovery.requestWifiSessionFactDrain()
		runCurrent()
		entered.isCompleted shouldBe true
		gate.closeAdmission()
		recovery.requestWifiSessionFactDrain()
		val drained = async { gate.awaitQuiescence() }
		runCurrent()
		drained.isCompleted shouldBe false
		release.complete(Unit)
		runCurrent()
		drained.await()
		coVerify(exactly = 1) { projections.drainAmbientRadioProjection(SourceKind.WIFI, any()) }
	}

	private fun radioRecovery(
		projections: ProjectionDispatcher,
		scope: CoroutineScope,
		gate: javax.inject.Provider<TrackingStartupGate>? = null,
	) = SourcePipelineRecovery(
		legacy, coordinator, activityLane, activityEffects,
		mockk<StepsSessionFactProjectionLane>(relaxed = true),
		mockk<PressureSessionFactProjectionLane>(relaxed = true),
		mockk<ActivityCapturedFactProjectionLane>(relaxed = true),
		mockk<CellSessionFactProjectionLane>(relaxed = true),
		mockk<WifiSessionFactProjectionLane>(relaxed = true),
		scope, ambientProjections = projections, ambientIngress = mockk<DurableSourceIngress>(),
		startupGateProvider = gate,
	)

	@Test
	fun `committed work hints return immediately and conflate behind one drain`() = runTest {
		val firstDrainEntered = CompletableDeferred<Unit>()
		val releaseFirstDrain = CompletableDeferred<Unit>()
		coEvery { activityLane.drainAvailable() } coAnswers {
			firstDrainEntered.complete(Unit)
			releaseFirstDrain.await()
			CoordinatorDrainResult.Complete(7L, 0)
		}
		coEvery { activityEffects.drain() } returns 0
		val scheduled = SourcePipelineRecovery(
			legacy,
			coordinator,
			activityLane,
			activityEffects,
			backgroundScope,
		)

		repeat(100) { scheduled.requestCommittedWorkDrain() }
		firstDrainEntered.isCompleted shouldBe false
		runCurrent()
		firstDrainEntered.isCompleted shouldBe true

		repeat(100) { scheduled.requestCommittedWorkDrain() }
		runCurrent()
		// The first Activity-local drain is deliberately blocked. New hints must conflate
		// without entering a concurrent projection drain or blocking the caller.
		coVerify(exactly = 0) { coordinator.drainAvailable(any()) }

		releaseFirstDrain.complete(Unit)
		// backgroundScope is intentionally ignored by advanceUntilIdle once only background work
		// remains. Run the queued continuation explicitly through the second conflated drain.
		runCurrent()

		coVerify(exactly = 0) { legacy.recover() }
		coVerify(exactly = 0) { coordinator.drainAvailable(any()) }
		coVerify(exactly = 2) { activityLane.drainAvailable() }
		coVerify(exactly = 2) { activityEffects.drain() }
	}

	@Test
	fun `app scope drain joins startup generation and cannot publish effects across deletion`() = runTest {
		val gate = TestTrackingStartupGate(initialGeneration = 7L)
		val projectionEntered = CompletableDeferred<Unit>()
		val releaseProjection = CompletableDeferred<Unit>()
		coEvery { activityLane.drainAvailable() } coAnswers {
			projectionEntered.complete(Unit)
			releaseProjection.await()
			CoordinatorDrainResult.Complete(9L, 1)
		}
		val scheduled = SourcePipelineRecovery(
			legacy,
			coordinator,
			activityLane,
			activityEffects,
			backgroundScope,
			javax.inject.Provider { gate },
		)

		scheduled.requestCommittedWorkDrain()
		runCurrent()
		projectionEntered.await()

		gate.closeAdmission()
		val deletionQuiescence = async { gate.awaitQuiescence() }
		runCurrent()
		deletionQuiescence.isCompleted shouldBe false

		releaseProjection.complete(Unit)
		runCurrent()
		deletionQuiescence.await()

		// Closing the generation while projection was in flight withholds every external effect.
		// The projection completed before deletion quiescence, so its rows can be scrubbed once and
		// cannot be recreated by the old app-scope job after deletion returns.
		coVerify(exactly = 1) { activityLane.drainAvailable() }
		coVerify(exactly = 0) { coordinator.drainAvailable(any()) }
		coVerify(exactly = 0) { activityEffects.drain(any(), any(), any()) }

		// A signal already queued against the closed generation is also fail-closed.
		scheduled.requestCommittedWorkDrain()
		runCurrent()
		coVerify(exactly = 1) { activityLane.drainAvailable() }
	}

	@Test
	fun `committed work drains only the Activity projection and effects`() = runTest {
		coEvery { activityLane.drainAvailable() } returns CoordinatorDrainResult.Complete(7L, 0)
		coEvery { activityEffects.drain() } returns 1

		val result = subject.drainCommittedWork()

		result shouldBe SourceRecoveryResult(
			drain = CoordinatorDrainResult.Complete(7L, 0),
			activityEffectsDelivered = 1,
			trackingFramesDelivered = 0,
			legacyRecovery = LegacyV27ProjectionRecoveryResult.NotRequired,
		)
		coVerifySequence {
			activityLane.drainAvailable()
			activityEffects.drain()
		}
		coVerify(exactly = 0) { legacy.recover() }
		coVerify(exactly = 0) { coordinator.drainAvailable(any()) }
	}

	@Test
	fun `durable startup recovery never invokes application effect consumers`() = runTest {
		coEvery { legacy.recover() } returns LegacyV27ProjectionRecoveryResult.NotRequired
		coEvery { coordinator.drainAvailable(any()) } returns CoordinatorDrainResult.Complete(4L, 2)

		val result = subject.recoverDurableState()

		result shouldBe SourceRecoveryResult(
			drain = CoordinatorDrainResult.Complete(4L, 2),
			activityEffectsDelivered = 0,
			trackingFramesDelivered = 0,
			legacyRecovery = LegacyV27ProjectionRecoveryResult.NotRequired,
		)
		coVerify(exactly = 0) { activityEffects.drain() }
	}

	@Test
	fun `startup authority recovers released v27 without acquiring a live projection lease`() = runTest {
		val legacyResult = LegacyV27ProjectionRecoveryResult.Complete(
			partial = false,
			suppressedOutboxCount = 0L,
		)
		coEvery { legacy.recover() } returns legacyResult

		subject.recoverStartupAuthority() shouldBe legacyResult

		coVerify(exactly = 1) { legacy.recover() }
		coVerify(exactly = 0) { coordinator.drainAvailable(any()) }
		coVerify(exactly = 0) { activityLane.drainAvailable() }
		coVerify(exactly = 0) { activityEffects.drain(any(), any(), any()) }
	}

	@Test
	fun `startup and committed fact hints independently kick their source local lanes`() = runTest {
		val stepsLane = mockk<StepsSessionFactProjectionLane>(relaxed = true)
		val pressureLane = mockk<PressureSessionFactProjectionLane>(relaxed = true)
		val capturedActivityLane = mockk<ActivityCapturedFactProjectionLane>(relaxed = true)
		val cellLane = mockk<CellSessionFactProjectionLane>(relaxed = true)
		val wifiLane = mockk<WifiSessionFactProjectionLane>(relaxed = true)
		val protectedLocationDrain = mockk<ProtectedLocationSourceDrain>(relaxed = true)
		val scheduled = SourcePipelineRecovery(
			legacy,
			coordinator,
			activityLane,
			activityEffects,
			stepsLane,
			pressureLane,
			capturedActivityLane,
			cellLane,
			wifiLane,
			backgroundScope,
			protectedLocationDrain,
		)
		coEvery { legacy.recover() } returns LegacyV27ProjectionRecoveryResult.NotRequired

		scheduled.recoverStartupAuthority()
		scheduled.requestStepsSessionFactDrain()
		scheduled.requestPressureSessionFactDrain()
		scheduled.requestActivityCapturedFactDrain()
		scheduled.requestCellSessionFactDrain()
		scheduled.requestWifiSessionFactDrain()
		scheduled.requestProtectedLocationCanonicalDrain()

		verify(exactly = 2) { stepsLane.requestDrain() }
		verify(exactly = 2) { pressureLane.requestDrain() }
		verify(exactly = 2) { capturedActivityLane.requestDrain() }
		verify(exactly = 2) { cellLane.requestDrain() }
		verify(exactly = 2) { wifiLane.requestDrain() }
		verify(exactly = 2) { protectedLocationDrain.requestDrain() }
		coVerify(exactly = 0) { stepsLane.drainAvailable() }
		coVerify(exactly = 0) { pressureLane.drainAvailable() }
		coVerify(exactly = 0) { capturedActivityLane.drainAvailable() }
		coVerify(exactly = 0) { cellLane.drainAvailable() }
		coVerify(exactly = 0) { wifiLane.drainAvailable() }
		coVerify(exactly = 0) { activityLane.drainAvailable() }
		coVerify(exactly = 0) { coordinator.drainAvailable(any()) }
	}

	@Test
	fun `post-authority activity drain does not rerun durable recovery`() = runTest {
		val expected = ActivityAutomationDrainResult.Complete(
			deliveredCount = 3,
			terminalCount = 2,
		)
		coEvery { activityEffects.drainToQuiescence() } returns expected
		coEvery { activityLane.drainAvailable() } returns CoordinatorDrainResult.Complete(9L, 2)

		subject.drainActivityAutomationEffects() shouldBe expected

		coVerify(exactly = 1) { activityEffects.drainToQuiescence() }
		coVerify(exactly = 1) { activityLane.drainAvailable() }
		coVerify(exactly = 0) { legacy.recover() }
		coVerify(exactly = 0) { coordinator.drainAvailable(any()) }
	}

	@Test
	fun `Activity projection poison keeps its effects pending and retryable`() = runTest {
		coEvery { activityLane.drainAvailable() } returns CoordinatorDrainResult.ProjectionFailed(
			lastCompletedOrdinal = 8L,
			eventsDispatched = 0,
			projectionId = ActivityAutomationProjection.ID,
			failedOrdinal = 9L,
		)
		subject.drainActivityAutomationEffects() shouldBe
			ActivityAutomationDrainResult.ProjectionDeferred()

		coVerify(exactly = 1) { activityLane.drainAvailable() }
		coVerify(exactly = 0) { activityEffects.drainToQuiescence(any(), any(), any(), any()) }
	}

	@Test
	fun `callback drain grants start context only to exact admitted ordinals`() = runTest {
		coEvery { activityLane.drainThrough(9L) } returns CoordinatorDrainResult.Complete(9L, 2)
		coEvery { activityEffects.drainToQuiescence(any(), any(), any(), any()) } returns
			ActivityAutomationDrainResult.Complete(1, 0)

		val result = subject.drainCommittedActivityCallbackWork(setOf(8L, 9L), 9L) {
			error("authority wait should not be needed")
		}

		result.activityEffectsDelivered shouldBe 1
		coVerify(exactly = 1) { activityLane.drainThrough(9L) }
		coVerify(exactly = 0) { legacy.recover() }
		coVerify(exactly = 0) { coordinator.drainAvailable(any()) }
		coVerify(exactly = 1) {
			activityEffects.drainToQuiescence(
				startPermit = ActivityAutomationStartPermit.FreshTransitionCallback(setOf(8L, 9L)),
				elapsedRealtimeNanos = any(),
			)
		}
	}

	@Test
	fun `cold callback retains exact permit across authority wait and redrain`() = runTest {
		coEvery { activityLane.drainThrough(9L) } returns CoordinatorDrainResult.Complete(9L, 1)
		val order = mutableListOf<String>()
		coEvery { activityEffects.drainToQuiescence(any(), any(), any(), any()) } answers {
			when (order.count { it.startsWith("drain") }) {
				0 -> ActivityAutomationDrainResult.Retryable(0, 0).also { order += "drain-unready" }
				else -> ActivityAutomationDrainResult.Complete(1, 0).also { order += "drain-ready" }
			}
		}

		val result = subject.drainCommittedActivityCallbackWork(setOf(9L), 9L) {
			order += "authority-ready"
		}

		result.activityEffectsDelivered shouldBe 1
		result.activityAutomationDrain shouldBe ActivityAutomationDrainResult.Complete(1, 0)
		order shouldBe listOf("drain-unready", "authority-ready", "drain-ready")
		coVerify(exactly = 2) {
			activityEffects.drainToQuiescence(
				startPermit = ActivityAutomationStartPermit.FreshTransitionCallback(setOf(9L)),
				elapsedRealtimeNanos = any(),
			)
		}
	}

	@Test
	fun `cold callback opens authority and delivers one start effect before it returns`() = runTest {
		val database = mockk<AppDatabase>()
		val dao = mockk<SourceProjectionStateDao>()
		val validator = mockk<ActivityAutomationEffectValidator>()
		val consumer = mockk<ActivityAutomationEffectConsumer>()
		val dispatcher = ActivityAutomationOutboxDispatcher(
			database, validator, consumer,
			mockk<ActivityControlRetentionMaintenance>(relaxed = true),
		)
		val composed = SourcePipelineRecovery(legacy, coordinator, activityLane, dispatcher)
		val effect = transitionEffect(9L)
		every { database.sourceProjectionStateDao() } returns dao
		coEvery { validator.validate(any()) } returns ActivityAutomationEffectValidation.Eligible(8, 3)
		coEvery { dao.pendingOutbox(any(), any(), any(), any()) } returnsMany listOf(
			listOf(effect),
			listOf(effect),
		)
		coEvery { dao.deleteFailure(any(), any(), any()) } returns 0
		coEvery { dao.markOutboxDelivered(any(), any()) } returns 1
		coEvery { activityLane.drainThrough(9L) } returns CoordinatorDrainResult.Complete(9L, 1)
		var authorityReady = false
		var serviceStarts = 0
		val contexts = mutableListOf<ActivityAutomationStartContext>()
		coEvery { consumer.deliver(any(), any(), any(), any(), any()) } answers {
			contexts += arg<ActivityAutomationStartContext>(4)
			if (!authorityReady) ActivityAutomationDeliveryResult.RETRY
			else ActivityAutomationDeliveryResult.ACCEPTED.also { serviceStarts++ }
		}

		val result = composed.drainCommittedActivityCallbackWork(
			setOf(9L),
			throughAdmissionOrdinal = 9L,
			elapsedRealtimeNanos = { 1_200L },
		) {
			authorityReady = true
		}

		result.activityEffectsDelivered shouldBe 1
		serviceStarts shouldBe 1
		contexts shouldBe listOf(
			ActivityAutomationStartContext.FRESH_TRANSITION_CALLBACK,
			ActivityAutomationStartContext.FRESH_TRANSITION_CALLBACK,
		)
		coVerify(exactly = 1) { dao.markOutboxDelivered(effect.stableId, any()) }
		coVerify(exactly = 0) { dao.markOutboxTerminal(any(), any(), any()) }
	}

	@Test
	fun `authority wait consuming live deadline redrains only as durable replay`() = runTest {
		val database = mockk<AppDatabase>()
		val dao = mockk<SourceProjectionStateDao>()
		val validator = mockk<ActivityAutomationEffectValidator>()
		val consumer = mockk<ActivityAutomationEffectConsumer>()
		val dispatcher = ActivityAutomationOutboxDispatcher(
			database, validator, consumer,
			mockk<ActivityControlRetentionMaintenance>(relaxed = true),
		)
		val composed = SourcePipelineRecovery(legacy, coordinator, activityLane, dispatcher)
		val effect = transitionEffect(9L)
		every { database.sourceProjectionStateDao() } returns dao
		coEvery { validator.validate(any()) } returns ActivityAutomationEffectValidation.Eligible(8, 3)
		coEvery { dao.pendingOutbox(any(), any(), any(), any()) } returns listOf(effect)
		coEvery { dao.deleteFailure(any(), any(), any()) } returns 0
		coEvery { dao.markOutboxTerminal(any(), any(), any()) } returns 1
		coEvery { activityLane.drainThrough(9L) } returns CoordinatorDrainResult.Complete(9L, 1)
		val contexts = mutableListOf<ActivityAutomationStartContext>()
		coEvery { consumer.deliver(any(), any(), any(), any(), capture(contexts)) } answers {
			if (arg<ActivityAutomationStartContext>(4) ==
				ActivityAutomationStartContext.FRESH_TRANSITION_CALLBACK
			) {
				ActivityAutomationDeliveryResult.RETRY
			} else {
				ActivityAutomationDeliveryResult.START_CONTEXT_EXPIRED
			}
		}

		val result = composed.drainCommittedActivityCallbackWork(
			setOf(9L),
			throughAdmissionOrdinal = 9L,
			elapsedRealtimeNanos = {
				1_100L + testScheduler.currentTime * 1_000_000L
			},
		) {
			delay(7_000L)
		}

		result.activityAutomationDrain shouldBe ActivityAutomationDrainResult.Complete(0, 1)
		contexts shouldBe listOf(
			ActivityAutomationStartContext.FRESH_TRANSITION_CALLBACK,
			ActivityAutomationStartContext.DURABLE_REPLAY,
		)
		coVerify(exactly = 1) {
			dao.markOutboxTerminal(effect.stableId, "START_CONTEXT_EXPIRED", any())
		}
		coVerify(exactly = 0) { dao.markOutboxDelivered(any(), any()) }
	}

	@Test
	fun `callback timeout leaves effect pending and replay terminalizes expired context honestly`() = runTest {
		val database = mockk<AppDatabase>()
		val dao = mockk<SourceProjectionStateDao>()
		val validator = mockk<ActivityAutomationEffectValidator>()
		val consumer = mockk<ActivityAutomationEffectConsumer>()
		val dispatcher = ActivityAutomationOutboxDispatcher(
			database, validator, consumer,
			mockk<ActivityControlRetentionMaintenance>(relaxed = true),
		)
		val composed = SourcePipelineRecovery(legacy, coordinator, activityLane, dispatcher)
		val effect = transitionEffect(9L)
		every { database.sourceProjectionStateDao() } returns dao
		coEvery { validator.validate(any()) } returns ActivityAutomationEffectValidation.Eligible(8, 3)
		coEvery { dao.pendingOutbox(any(), any(), any(), any()) } returns listOf(effect)
		coEvery { dao.deleteFailure(any(), any(), any()) } returns 0
		coEvery { activityLane.drainThrough(9L) } returns CoordinatorDrainResult.Complete(9L, 1)
		coEvery { consumer.deliver(any(), any(), any(), any(), any()) } returns
			ActivityAutomationDeliveryResult.RETRY

		shouldThrow<TimeoutCancellationException> {
			withTimeout(100L) {
				composed.drainCommittedActivityCallbackWork(
					setOf(9L),
					throughAdmissionOrdinal = 9L,
					elapsedRealtimeNanos = { 1_200L },
				) { awaitCancellation() }
			}
		}

		dispatcher.drainRequired.value shouldBe true
		coVerify(exactly = 0) { dao.markOutboxDelivered(any(), any()) }
		coVerify(exactly = 0) { dao.markOutboxTerminal(any(), any(), any()) }

		coEvery {
			consumer.deliver(
				any(),
				any(),
				any(),
				any(),
				ActivityAutomationStartContext.DURABLE_REPLAY,
			)
		} returns ActivityAutomationDeliveryResult.START_CONTEXT_EXPIRED
		coEvery { dao.markOutboxTerminal(any(), any(), any()) } returns 1

		dispatcher.drainToQuiescence() shouldBe
			ActivityAutomationDrainResult.Complete(deliveredCount = 0, terminalCount = 1)
		coVerify(exactly = 1) {
			dao.markOutboxTerminal(effect.stableId, "START_CONTEXT_EXPIRED", any())
		}
	}

	@Test
	fun `terminal legacy result is read once per process`() = runTest {
		coEvery { legacy.recover() } returns LegacyV27ProjectionRecoveryResult.NotRequired
		coEvery { coordinator.drainAvailable(any()) } returns CoordinatorDrainResult.Complete(0L, 0)

		subject.recoverDurableState()
		subject.recoverDurableState()

		coVerify(exactly = 1) { legacy.recover() }
		coVerify(exactly = 2) { coordinator.drainAvailable(any()) }
	}

	@Test
	fun `not-required legacy recovery permits an empty live drain`() = runTest {
		coEvery { activityLane.drainAvailable() } returns CoordinatorDrainResult.Complete(0L, 0)
		coEvery { activityEffects.drain() } returns 0

		subject.drainCommittedWork().legacyRecovery shouldBe
			LegacyV27ProjectionRecoveryResult.NotRequired
	}

	@Test
	fun `nonterminal legacy outcomes never open startup authority`() = runTest {
		val outcomes = listOf(
			LegacyV27ProjectionRecoveryResult.LeaseUnavailable,
			LegacyV27ProjectionRecoveryResult.LifecycleSuperseded,
			LegacyV27ProjectionRecoveryResult.Blocked("UNKNOWN_WRITER"),
			LegacyV27ProjectionRecoveryResult.FailedRetryable("STORAGE"),
		)

		outcomes.forEach { outcome ->
			coEvery { legacy.recover() } returns outcome
			shouldThrow<LegacyV27ProjectionRecoveryNotReadyException> {
				subject.recoverStartupAuthority()
			}.result shouldBe outcome
		}

		coVerify(exactly = 0) { coordinator.drainAvailable(any()) }
		coVerify(exactly = 0) { activityEffects.drain() }
	}

	@Test
	fun `global projection failure is not consulted by the independent Activity lane`() = runTest {
		coEvery { coordinator.drainAvailable(any()) } returns
			CoordinatorDrainResult.ProjectionFailed(4L, 4, "location-domain", 5L)
		coEvery { activityLane.drainAvailable() } returns CoordinatorDrainResult.Complete(5L, 1)
		coEvery { activityEffects.drain() } returns 1

		val result = subject.drainCommittedWork()

		result.drain shouldBe CoordinatorDrainResult.Complete(5L, 1)
		result.activityEffectsDelivered shouldBe 1
		coVerify(exactly = 0) { coordinator.drainAvailable(any()) }
		coVerify(exactly = 1) { activityLane.drainAvailable() }
		coVerify(exactly = 1) { activityEffects.drain() }
	}

	private fun transitionEffect(ordinal: Long) = SourceProjectionOutboxEntity(
		stableId = "transition-$ordinal",
		projectionId = ActivityAutomationProjection.ID,
		projectionVersion = ActivityAutomationProjection.VERSION,
		admissionOrdinal = ordinal,
		effectKind = ActivityAutomationProjection.OUTBOX_KIND,
		payloadVersion = ActivityAutomationProjection.PAYLOAD_VERSION,
		payload = ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeInt(ActivityAutomationProjection.KIND_TRANSITION)
				output.writeInt(1)
				output.writeInt(100)
				output.writeInt(ActivityTransitionType.ENTER.value)
				output.writeUTF("boot")
				output.writeLong(1_000L)
				output.writeLong(1_100L)
				output.writeLong(1L)
				output.writeLong(2L)
				output.writeUTF("authorization")
				output.writeLong(0L)
				output.writeLong(1L)
			}
			bytes.toByteArray()
		},
		createdAtMs = ordinal,
		deliveredAtMs = null,
	)

	private class TestTrackingStartupGate(
		initialGeneration: Long,
	) : TrackingStartupGate {
		private val operationMutex = Mutex()
		private val transfers = mutableSetOf<Job>()
		@Volatile private var ready = true
		@Volatile private var generation = initialGeneration

		override val isReady: Boolean
			get() = ready

		override val currentGeneration: Long
			get() = generation

		override suspend fun <T> withReadyGenerationOperation(
			expectedGeneration: Long,
			operation: suspend () -> T,
		): T? = operationMutex.withLock {
			if (isReadyGeneration(expectedGeneration)) operation() else null
		}

		override suspend fun <T> withReadyGenerationTransfer(
			expectedGeneration: Long,
			operation: suspend () -> T,
		): T? = coroutineScope {
			val child = async(start = CoroutineStart.LAZY) { operation() }
			val admitted = synchronized(transfers) {
				if (!isReadyGeneration(expectedGeneration)) false else { transfers.add(child); true }
			}
			if (!admitted) { child.cancel(); return@coroutineScope null }
			child.invokeOnCompletion { synchronized(transfers) { transfers.remove(child) } }
			child.start()
			child.await()
		}

		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
			TrackingStartupResult.Ready(
				legacyRecoveryPartial = false,
				liveCompletedThroughOrdinal = 0L,
			)

		fun closeAdmission() {
			ready = false
			generation += 1L
			synchronized(transfers) { transfers.toList() }.forEach { it.cancel() }
		}

		suspend fun awaitQuiescence() {
			synchronized(transfers) { transfers.toList() }.joinAll()
			operationMutex.withLock { Unit }
		}
	}
}
