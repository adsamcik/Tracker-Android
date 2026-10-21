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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.After

class SharedPressureSourceControllerTest {
	private val physical = mockk<PressureSourceRuntime>()
	private val broker = mockk<SourceBroker>()
	private val factory = mockk<DurableSourceEventSinkFactory>()
	private val reporter = mockk<TrackingPurposeAvailabilityReporter>(relaxed = true)
	private val retries = mockk<TrackingPurposeReconciliationRetryScheduler>(relaxed = true)
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
		coEvery { physical.quiesce(any()) } returns acknowledgement()
		coEvery { physical.quiesceShared(any()) } returns acknowledgement()
		subject = SharedPressureSourceController(physical, broker, factory, reporter, retries)
	}

	@After
	fun tearDown() = unmockkStatic(SystemClock::class)

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
	fun `a failed all-day restart withdraws readiness and retries while End remains accepted`() = runTest {
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT), demand(SourceBrokerPurpose.SESSION_CAPTURE))
		subject.reconcileAmbient(background)
		subject.start(claim, session, sessionSink)
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT))
		coEvery { physical.reconfigure(background, durableSink) } returns
			SourceApplyResult.Failed(applied(background).copy(status = SourceApplyStatus.FAILED), retryable = true)
		assertIs<OwnedSourceShutdown.Released>(subject.shutdownIfOwned(claim,
			SessionCutoff("session", 20L, 30L, 100L)))
		verify(exactly = 1) { reporter.invalidateAmbient(AmbientTrackingSource.PRESSURE) }
		coVerify(exactly = 1) { retries.schedule(match { debt ->
			debt.failures.single().source == AmbientTrackingSource.PRESSURE }) }
	}

	@Test
	fun `removing all-day participation keeps an active session and stale session Start remains blocked`() = runTest {
		demands = listOf(demand(SourceBrokerPurpose.AMBIENT_PRODUCT), demand(SourceBrokerPurpose.SESSION_CAPTURE))
		subject.reconcileAmbient(background)
		subject.start(claim, session, sessionSink)
		demands = listOf(demand(SourceBrokerPurpose.SESSION_CAPTURE))
		assertIs<SourceApplyResult.Applied>(subject.reconcileAmbient(null))
		coVerify(exactly = 2) { physical.reconfigure(claim, session, durableSink) }
		demands = emptyList()
		assertIs<SourceStartResult.Failed>(subject.start(claim, session, sessionSink))
		coVerify(exactly = 2) { physical.reconfigure(claim, session, durableSink) }
	}

	private fun applied(plan: PressurePlan) = AppliedSourcePlan(plan.revision, plan.revision, SourceKind.PRESSURE,
		SourceInstanceId("pressure"), 1L, 1L, SourceApplyStatus.APPLIED)

	private fun demand(purpose: String) = mockk<SourceDemandEntity>().also { demand ->
		every { demand.purpose } returns purpose
		every { demand.persistenceEligible } returns true
		every { demand.logicalTrackingId } returns "session".takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE }
		every { demand.serviceRunId } returns "run".takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE }
		every { demand.lifecycleLeaseGeneration } returns 1L.takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE }
	}

	private fun acknowledgement() = SourceStopAck(SourceKind.PRESSURE, SourceInstanceId("pressure"), 1L, 2L,
		5L, 5L, 50L, 0L, null, null, RegistrationRemovalOutcome.REMOVED, ProviderFlushOutcome.COMPLETE,
		ProviderCoverage.CALLBACKS_ENTERED_BEFORE_BARRIER, true, SourceStopStatus.COMPLETE)
}
