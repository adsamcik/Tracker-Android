package com.adsamcik.tracker.tracker.source.runtime

import android.os.SystemClock
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilityReporter
import com.adsamcik.tracker.tracker.api.TrackingPurposeReconciliationRetryScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.After

class SharedPressureSourceControllerTest {
	private val physical = mockk<PressureSourceRuntime>()
	private val broker = mockk<SourceBroker>()
	private val factory = mockk<DurableSourceEventSinkFactory>()
	private val reporter = mockk<TrackingPurposeAvailabilityReporter>(relaxed = true)
	private val retries = mockk<TrackingPurposeReconciliationRetryScheduler>(relaxed = true)
	private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
	private val durableSink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
	private val sessionSink = SourceEventSink { error("Shared callbacks must use durable purpose admission") }
	private val claim = SourceRuntimeClaim(SourceKind.PRESSURE, "session-start", 1, 1L, "session", "run")
	private val background = PressurePlan(1L, true, 1_000_000, 60_000_000, 60_000L)
	private val session = PressurePlan(2L, true, 50_000, 1_000_000, 2_000L)
	private var demands = listOf<SourceDemandEntity>()
	private val subject: SharedPressureSourceController

	init {
		mockkStatic(SystemClock::class)
		every { SystemClock.elapsedRealtimeNanos() } returns 1L
		every { physical.capabilities } returns MutableStateFlow(SourceCapabilities(true, true, true, null, 1L))
		every { factory.unbound } returns durableSink
		coEvery { broker.authorizationDemands(SourceKind.PRESSURE) } coAnswers { demands }
		coEvery { physical.reconfigure(any<PressurePlan>(), durableSink) } coAnswers {
			SourceApplyResult.Applied(applied(firstArg()))
		}
		coEvery { physical.reconfigure(any<SourceRuntimeClaim>(), any(), durableSink) } coAnswers {
			SourceApplyResult.Applied(applied(secondArg()))
		}
		coEvery { physical.refreshCompatible(any(), durableSink) } coAnswers {
			SourceApplyResult.Applied(applied(firstArg()))
		}
		coEvery { physical.quiesce(any()) } returns acknowledgement()
		coEvery { physical.quiesceShared(any()) } returns acknowledgement()
		subject = SharedPressureSourceController(physical, broker, factory, reporter, retries, applicationScope)
	}

	@After
	fun tearDown() {
		applicationScope.cancel()
		unmockkStatic(SystemClock::class)
	}

	@Test
	fun `all-day Pressure starts without a session and shares the existing listener during Start`() = runTest {
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT))
		assertIs<SourceApplyResult.Applied>(subject.reconcileAmbient(background))
		demands = demands + demand(SourceBrokerPurpose.SESSION_CAPTURE)
		assertIs<SourceStartResult.Started>(subject.start(claim, session, sessionSink))
		coVerifyOrder {
			physical.reconfigure(background, durableSink)
			physical.reconfigure(claim, session, durableSink)
		}
		coVerify(exactly = 0) { physical.start(any<PressurePlan>(), any()) }
	}

	@Test
	fun `a session retry after ambient restart omits only the retired ambient predecessor`() = runTest {
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT), demand(SourceBrokerPurpose.SESSION_CAPTURE))
		coEvery { physical.reconfigure(claim, session, durableSink) } returns
			SourceApplyResult.Failed(applied(session), retryable = true)
		assertIs<SourceStartResult.Failed>(subject.start(claim, session, sessionSink))
		assertIs<SourceApplyResult.Applied>(subject.reconcileAmbient(background))

		val retryClaim = claim.copy(actionId = "session-retry", leaseGeneration = 2L)
		val retryPlan = session.copy(revision = 3L)
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT),
			demand(SourceBrokerPurpose.SESSION_CAPTURE, leaseGeneration = 2L))
		val ambientRetirement = acknowledgement().copy(failedAdmissionCount = 5L,
			unresolvedSequenceStart = 3L, unresolvedSequenceEndInclusive = 9L)
		coEvery { physical.reconfigure(retryClaim, retryPlan, durableSink) } returns
			SourceApplyResult.Applied(applied(retryPlan), ambientRetirement)

		val result = assertIs<SourceApplyResult.Applied>(subject.reconfigure(retryClaim, retryPlan, sessionSink))
		assertEquals(applied(retryPlan), result.state)
		assertEquals(null, result.stopAck)
		assertEquals(5L, ambientRetirement.failedAdmissionCount)
		assertEquals(null, ambientRetirement.logicalTrackingId)
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT))
		val stopped = assertIs<OwnedSourceShutdown.Released>(subject.shutdownIfOwned(retryClaim,
			SessionCutoff("session", 20L, 30L, 100L)))
		assertEquals(acknowledgement().copy(logicalTrackingId = "session", serviceRunId = "run"), stopped.stopAck)
		coVerifyOrder {
			physical.reconfigure(claim, session, durableSink)
			physical.reconfigure(background, durableSink)
			physical.reconfigure(retryClaim, retryPlan, durableSink)
			physical.quiesceShared(any())
		}
	}

	@Test
	fun `capture predecessor and already claimed retirement evidence remain unchanged`() = runTest {
		demands = listOf(demand(SourceBrokerPurpose.SESSION_CAPTURE))
		val captured = acknowledgement().copy(logicalTrackingId = "session", serviceRunId = "run",
			failedAdmissionCount = 2L, unresolvedSequenceStart = 3L, unresolvedSequenceEndInclusive = 4L)
		coEvery { physical.reconfigure(claim, session, durableSink) } returns
			SourceApplyResult.Degraded(applied(session), captured)
		assertEquals(captured,
			assertIs<SourceApplyResult.Degraded>(subject.reconfigure(claim, session, sessionSink)).stopAck)

		val nextClaim = claim.copy(actionId = "session-reconfigure", leaseGeneration = 2L)
		val nextPlan = session.copy(revision = 3L)
		demands = listOf(demand(SourceBrokerPurpose.SESSION_CAPTURE, leaseGeneration = 2L))
		val unclaimed = acknowledgement()
		coEvery { physical.reconfigure(nextClaim, nextPlan, durableSink) } returns
			SourceApplyResult.Applied(applied(nextPlan), unclaimed)
		assertEquals(unclaimed,
			assertIs<SourceApplyResult.Applied>(subject.reconfigure(nextClaim, nextPlan, sessionSink)).stopAck)
	}

	@Test
	fun `first session join retains unfinished or unknown ambient retirement evidence`() = runTest {
		demands = listOf(demand(SourceBrokerPurpose.SESSION_CAPTURE))
		val unsafePredecessors = listOf(
			acknowledgement().copy(appDrainComplete = false, status = SourceStopStatus.TIMED_OUT),
			acknowledgement().copy(registrationRemovalOutcome = RegistrationRemovalOutcome.UNOBSERVABLE),
			acknowledgement().copy(registrationGeneration = 0L),
		)
		for (predecessor in unsafePredecessors) {
			val owner = SharedPressureSourceController(physical, broker, factory, reporter, retries, applicationScope)
			coEvery { physical.reconfigure(claim, session, durableSink) } returns
				SourceApplyResult.Applied(applied(session), predecessor)
			assertEquals(predecessor,
				assertIs<SourceApplyResult.Applied>(owner.reconfigure(claim, session, sessionSink)).stopAck)
		}
	}

	@Test
	fun `End drains and removes the session listener before resuming independently allowed all-day Pressure`() = runTest {
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT), demand(SourceBrokerPurpose.SESSION_CAPTURE))
		subject.reconcileAmbient(background)
		subject.start(claim, session, sessionSink)
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT))
		val cutoff = SessionCutoff("session", 20L, 30L, 100L)
		val ack = subject.quiesce(cutoff)
		assertEquals("session", ack.logicalTrackingId)
		assertEquals("run", ack.serviceRunId)
		assertEquals(50L, ack.lastAdmissionOrdinal)
		assertEquals(RegistrationRemovalOutcome.REMOVED, ack.registrationRemovalOutcome)
		coVerify(exactly = 1) { physical.reconfigure(background, durableSink) }
		coVerify(exactly = 1) { retries.schedule(any()) }
		assertIs<SourceApplyResult.Applied>(subject.reconcileAmbient(background))
		coVerifyOrder {
			physical.reconfigure(claim, session, durableSink)
			physical.quiesceShared(cutoff)
			physical.reconfigure(background, durableSink)
		}
	}

	@Test
	fun `Off removes the all-day join and End does not restart a retired background demand`() = runTest {
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT), demand(SourceBrokerPurpose.SESSION_CAPTURE))
		subject.reconcileAmbient(background)
		subject.start(claim, session, sessionSink)
		demands = emptyList()
		assertIs<OwnedSourceShutdown.Released>(subject.shutdownIfOwned(claim,
			SessionCutoff("session", 20L, 30L, 100L)))
		coVerify(exactly = 1) { physical.reconfigure(background, durableSink) }
		coVerify(exactly = 0) { retries.schedule(any()) }
		assertIs<SourceApplyResult.Applied>(subject.reconcileAmbient(null))
		coVerify { physical.reconfigure(match<PressurePlan> { !it.enabled }, durableSink) }
	}

	@Test
	fun `an unfinished removal cannot start another Pressure listener`() = runTest {
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT), demand(SourceBrokerPurpose.SESSION_CAPTURE))
		subject.reconcileAmbient(background)
		subject.start(claim, session, sessionSink)
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT))
		coEvery { physical.quiesceShared(any()) } returns acknowledgement().copy(
			appDrainComplete = false, registrationRemovalOutcome = RegistrationRemovalOutcome.FAILED,
			status = SourceStopStatus.TIMED_OUT)
		assertIs<OwnedSourceShutdown.Incomplete>(subject.shutdownIfOwned(claim,
			SessionCutoff("session", 20L, 30L, 100L)))
		coVerify(exactly = 1) { physical.reconfigure(background, durableSink) }
	}

	@Test
	fun `a failed all-day reschedule cannot change the earned End acknowledgement`() = runTest {
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT), demand(SourceBrokerPurpose.SESSION_CAPTURE))
		subject.reconcileAmbient(background)
		subject.start(claim, session, sessionSink)
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT))
		coEvery { retries.schedule(any()) } throws IllegalStateException("Work storage unavailable")
		assertIs<OwnedSourceShutdown.Released>(subject.shutdownIfOwned(claim,
			SessionCutoff("session", 20L, 30L, 100L)))
		verify(exactly = 1) { reporter.invalidateAmbient(AmbientTrackingSource.PRESSURE) }
		coVerify(exactly = 1) { retries.schedule(match { debt ->
			debt.failures.single().source == AmbientTrackingSource.PRESSURE }) }
		coVerify(exactly = 1) { physical.reconfigure(background, durableSink) }
	}

	@Test
	fun `a slow all-day startup cannot delay End or replace its actual coverage`() = runTest {
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT), demand(SourceBrokerPurpose.SESSION_CAPTURE))
		subject.reconcileAmbient(background)
		subject.start(claim, session, sessionSink)
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT))
		val started = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		val original = acknowledgement().copy(failedAdmissionCount = 2L,
			unresolvedSequenceStart = 3L, unresolvedSequenceEndInclusive = 4L)
		coEvery { physical.quiesceShared(any()) } returns original
		coEvery { physical.reconfigure(background, durableSink) } coAnswers {
			started.complete(Unit)
			release.await()
			SourceApplyResult.Applied(applied(background))
		}
		coEvery { retries.schedule(any()) } coAnswers {
			subject.reconcileAmbient(background)
			true
		}
		try {
			val released = assertIs<OwnedSourceShutdown.Released>(subject.shutdownIfOwned(claim,
				SessionCutoff("session", 20L, 30L, 100L)))
			started.await()
			assertEquals(original.copy(logicalTrackingId = "session", serviceRunId = "run"), released.stopAck)
		} finally {
			release.complete(Unit)
		}
	}

	@Test
	fun `cancelled End still releases its capture claim after the physical provider drains`() = runTest {
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT), demand(SourceBrokerPurpose.SESSION_CAPTURE))
		subject.reconcileAmbient(background)
		subject.start(claim, session, sessionSink)
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT))
		val entered = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		var physicallyDrained = false
		coEvery { physical.quiesceShared(any()) } coAnswers {
			withContext(NonCancellable) {
				entered.complete(Unit)
				release.await()
				physicallyDrained = true
			}
			currentCoroutineContext().ensureActive()
			acknowledgement()
		}
		val stopping = launch { subject.quiesce(SessionCutoff("session", 20L, 30L, 100L)) }
		try {
			entered.await()
			stopping.cancel()
			release.complete(Unit)
			stopping.join()

			assertEquals(true, physicallyDrained)
			coVerify(exactly = 1) { retries.schedule(any()) }
			assertIs<SourceApplyResult.Applied>(subject.reconcileAmbient(background))
			coVerify(exactly = 2) { physical.reconfigure(background, durableSink) }
		} finally {
			release.complete(Unit)
			stopping.cancel()
			stopping.join()
		}
	}

	@Test
	fun `a delayed all-day retry refreshes a new session without taking over its listener`() = runTest {
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT), demand(SourceBrokerPurpose.SESSION_CAPTURE))
		subject.reconcileAmbient(background)
		subject.start(claim, session, sessionSink)
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT))
		val release = CompletableDeferred<Unit>()
		coEvery { retries.schedule(any()) } coAnswers {
			release.await()
			subject.reconcileAmbient(background)
			true
		}
		try {
			assertIs<OwnedSourceShutdown.Released>(subject.shutdownIfOwned(claim,
				SessionCutoff("session", 20L, 30L, 100L)))
			val nextClaim = claim.copy(actionId = "next-start", leaseGeneration = 2L)
			demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT),
				demand(SourceBrokerPurpose.SESSION_CAPTURE, leaseGeneration = 2L))
			assertIs<SourceStartResult.Started>(subject.start(nextClaim, session, sessionSink))
			release.complete(Unit)
			coVerify(exactly = 1) { physical.refreshCompatible(session, durableSink) }
			coVerify(exactly = 1) { physical.reconfigure(background, durableSink) }
		} finally {
			release.complete(Unit)
		}
	}

	@Test
	fun `removing all-day participation keeps an active session and stale session Start remains blocked`() = runTest {
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT), demand(SourceBrokerPurpose.SESSION_CAPTURE))
		subject.reconcileAmbient(background)
		subject.start(claim, session, sessionSink)
		demands = listOf(demand(SourceBrokerPurpose.SESSION_CAPTURE))
		assertIs<SourceApplyResult.Applied>(subject.reconcileAmbient(null))
		coVerify(exactly = 1) { physical.refreshCompatible(session, durableSink) }
		coVerify(exactly = 1) { physical.reconfigure(claim, session, durableSink) }
		demands = emptyList()
		assertIs<SourceStartResult.Failed>(subject.start(claim, session, sessionSink))
		coVerify(exactly = 1) { physical.reconfigure(claim, session, durableSink) }
	}

	@Test
	fun `all-day refresh cannot replace capture while its next lease is waiting to apply`() = runTest {
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT), demand(SourceBrokerPurpose.SESSION_CAPTURE))
		subject.reconcileAmbient(background)
		subject.start(claim, session, sessionSink)
		val nextClaim = claim.copy(actionId = "session-reconfigure", leaseGeneration = 2L)
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT),
			demand(SourceBrokerPurpose.SESSION_CAPTURE, leaseGeneration = 2L))

		val deferred = assertIs<SourceApplyResult.Failed>(subject.reconcileAmbient(background.copy(revision = 3L)))
		assertEquals(true, deferred.retryable)
		coVerify(exactly = 0) { physical.refreshCompatible(any(), any()) }
		coVerify(exactly = 1) { physical.reconfigure(any<PressurePlan>(), durableSink) }
		assertIs<SourceApplyResult.Applied>(subject.reconfigure(nextClaim, session.copy(revision = 3L), sessionSink))
		coVerify(exactly = 1) { physical.reconfigure(nextClaim, session.copy(revision = 3L), durableSink) }
	}

	@Test
	fun `all-day refresh before End preserves the original owner's actual stop coverage`() = runTest {
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT), demand(SourceBrokerPurpose.SESSION_CAPTURE))
		subject.reconcileAmbient(background)
		subject.start(claim, session, sessionSink)
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT))
		val original = acknowledgement().copy(failedAdmissionCount = 2L,
			unresolvedSequenceStart = 3L, unresolvedSequenceEndInclusive = 4L,
			providerFlushOutcome = ProviderFlushOutcome.NOT_SUPPORTED)
		coEvery { physical.quiesceShared(any()) } returns original

		val deferred = assertIs<SourceApplyResult.Failed>(subject.reconcileAmbient(background))
		assertEquals(true, deferred.retryable)
		coVerify(exactly = 0) { physical.refreshCompatible(any(), any()) }
		coVerify(exactly = 0) { physical.quiesceShared(any()) }
		coVerify(exactly = 1) { physical.reconfigure(any<PressurePlan>(), durableSink) }
		val released = assertIs<OwnedSourceShutdown.Released>(subject.shutdownIfOwned(claim,
			SessionCutoff("session", 20L, 30L, 100L)))
		assertEquals(original.copy(logicalTrackingId = "session", serviceRunId = "run"), released.stopAck)
		assertIs<SourceApplyResult.Applied>(subject.reconcileAmbient(background))
		coVerifyOrder {
			physical.reconfigure(claim, session, durableSink)
			physical.quiesceShared(any())
			physical.reconfigure(background, durableSink)
		}
	}

	@Test
	fun `failed refresh after Off leaves exact capture retirement to its explicit owner`() = runTest {
		demands = listOf(demand(SourceBrokerPurpose.SESSION_CAPTURE))
		subject.start(claim, session, sessionSink)
		demands = emptyList()

		val deferred = assertIs<SourceApplyResult.Failed>(subject.reconcileAmbient(null))
		assertEquals(true, deferred.retryable)
		coVerify(exactly = 0) { physical.refreshCompatible(any(), any()) }
		coVerify(exactly = 0) { physical.reconfigure(any<PressurePlan>(), durableSink) }
		assertIs<OwnedSourceShutdown.Released>(subject.shutdownIfOwned(claim,
			SessionCutoff("session", 20L, 30L, 100L)))
		coVerify(exactly = 1) { physical.quiesce(any()) }
		coVerify(exactly = 0) { physical.reconfigure(any<PressurePlan>(), durableSink) }
	}

	private fun applied(plan: PressurePlan) = AppliedSourcePlan(plan.revision, plan.revision, SourceKind.PRESSURE,
		SourceInstanceId("pressure"), 1L, 1L, SourceApplyStatus.APPLIED)

	private fun demand(purpose: String, leaseGeneration: Long = 1L) = mockk<SourceDemandEntity>().also { demand ->
		every { demand.purpose } returns purpose
		every { demand.persistenceEligible } returns true
		every { demand.logicalTrackingId } returns "session".takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE }
		every { demand.serviceRunId } returns "run".takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE }
		every { demand.lifecycleLeaseGeneration } returns leaseGeneration.takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE }
	}

	private fun acknowledgement() = SourceStopAck(SourceKind.PRESSURE, SourceInstanceId("pressure"), 1L, 2L,
		5L, 5L, 50L, 0L, null, null, RegistrationRemovalOutcome.REMOVED, ProviderFlushOutcome.COMPLETE,
		ProviderCoverage.CALLBACKS_ENTERED_BEFORE_BARRIER, true, SourceStopStatus.COMPLETE)
}
