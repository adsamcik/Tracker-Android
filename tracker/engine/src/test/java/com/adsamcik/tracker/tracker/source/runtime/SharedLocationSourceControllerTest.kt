package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.SourceInstanceId

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.tracker.source.coordinator.adaptiveLocationPlan
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class SharedLocationSourceControllerTest {
	private val physical = mockk<LocationSourceRuntime>()
	private val broker = mockk<SourceBroker>()
	private val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
	private val sinkFactory = mockk<DurableSourceEventSinkFactory>()
	private val claim = SourceRuntimeClaim(SourceKind.LOCATION, "action", 1, 1L, "logical", "run")
	private val cutoff = SessionCutoff("logical", 500L, 1_000L, Long.MAX_VALUE)
	private val baseline = adaptiveLocationPlan(3, 9L, LocationBackend.FUSED, true)
	private val focus = adaptiveLocationPlan(4, 10L, LocationBackend.FUSED, true)
	private var demands = emptyList<SourceDemandEntity>()

	private fun subject(): SharedLocationSourceController {
		every { physical.capabilities } returns MutableStateFlow(
			SourceCapabilities(true, true, true, null, null),
		)
		every { sinkFactory.unbound } returns sink
		coEvery { physical.shutdownIfOwned(any(), any()) } returns OwnedSourceShutdown.NotOwned
		coEvery { broker.authorizationDemands(SourceKind.LOCATION) } answers { demands }
		coEvery { physical.refreshShared(any(), sink, any(), any()) } answers {
			SourceApplyResult.Applied(appliedState(
				SourceKind.LOCATION, firstArg<com.adsamcik.tracker.tracker.source.model.LocationPlan>().revision,
				null, SourceApplyStatus.APPLIED, 1L,
			))
		}
		return SharedLocationSourceController(physical, broker, sinkFactory)
	}

	@Test
	fun `sessionless plan cannot acquire without exact durable background policy`() = runTest {
		val subject = subject()
		demands = listOf(demand(false).copy(sourcePolicyRevision = 1L))
		assertIs<AmbientLocationRuntimeJoinResult.Inactive>(subject.reconcileAmbientJoin(baseline, 2L))
		coVerify(exactly = 0) { physical.refreshShared(any(), any(), any(), any()) }
		coVerify(exactly = 0) { physical.reconfigureShared(any(), any(), any(), any()) }
	}

	@Test
	fun `ending focus restores authorized background and never uses session bound sink`() = runTest {
		val subject = subject()
		demands = listOf(demand(false))
		assertIs<AmbientLocationRuntimeJoinResult.Active>(subject.reconcileAmbientJoin(baseline, 2L))
		demands = demands + demand(true)
		assertIs<SourceStartResult.Started>(subject.start(claim, focus, mockk()))
		coEvery { physical.sharedSessionCutoff(cutoff) } returns completeAck()
		demands = listOf(demand(false))
		val stopped = subject.quiesce(cutoff)
		assertEquals("logical", stopped.logicalTrackingId)
		assertEquals("run", stopped.serviceRunId)
		assertIs<OwnedSourceShutdown.Released>(stopped.toOwnedShutdown())
		coVerify(exactly = 2) { physical.refreshShared(baseline, sink, null, 120_000L) }
		coVerify(exactly = 1) { physical.refreshShared(focus, sink, claim, 120_000L) }
		coVerify(exactly = 0) { physical.quiesce(any()) }
		assertIs<OwnedSourceShutdown.NotOwned>(subject.shutdownIfOwned(claim, cutoff))
	}

	@Test
	fun `disabled exact background plan suspends provider while authority can recover`() = runTest {
		val subject = subject()
		demands = listOf(demand(false))
		subject.reconcileAmbientJoin(baseline, 2L)
		coEvery { physical.closeShared() } returns completeAck()
		assertIs<AmbientLocationRuntimeJoinResult.Inactive>(
			subject.reconcileAmbientJoin(baseline.copy(mode = LocationMode.DISABLED), 2L),
		)
		assertIs<AmbientLocationRuntimeJoinResult.Active>(subject.reconcileAmbientJoin(baseline, 2L))
		coVerify(exactly = 1) { physical.closeShared() }
		coVerify(exactly = 2) { physical.refreshShared(baseline, sink, null, 120_000L) }
	}

	@Test
	fun `stale background update and release cannot displace newer authorized baseline`() = runTest {
		val subject = subject()
		demands = listOf(demand(false).copy(sourcePolicyRevision = 3L))
		subject.reconcileAmbientJoin(baseline, 3L)
		assertIs<AmbientLocationRuntimeJoinResult.Unavailable>(subject.reconcileAmbientJoin(focus, 2L))
		assertIs<AmbientLocationRuntimeJoinResult.Unavailable>(subject.reconcileAmbientJoin(null, 2L))
		assertIs<AmbientLocationRuntimeJoinResult.Unavailable>(subject.reconcileAmbientJoin(null, 4L))
		demands = demands + demand(true)
		subject.start(claim, focus, mockk())
		demands = demands.filter { it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT }
		coEvery { physical.sharedSessionCutoff(cutoff) } returns completeAck()
		subject.quiesce(cutoff)
		coVerify(exactly = 2) { physical.refreshShared(baseline, sink, null, 120_000L) }
		coVerify(exactly = 0) { physical.closeShared() }
	}

	@Test
	fun `shared age admits union without rewriting legacy capture profile`() = runTest {
		val subject = subject()
		demands = listOf(demand(false))
		subject.reconcileAmbientJoin(baseline, 2L)
		demands = demands + demand(true).copy(maximumAgeMs = 2_000L)
		val legacy = focus.copy(adaptiveProfileVersion = null)
		subject.start(claim, legacy, mockk())
		coVerify(exactly = 1) {
			physical.refreshShared(match { it.adaptiveProfileVersion == null }, sink, claim, 120_000L)
		}
	}

	@Test
	fun `stale claim cannot join another session authority`() = runTest {
		val subject = subject()
		demands = listOf(demand(true).copy(serviceRunId = "replacement"))
		assertIs<SourceStartResult.Blocked>(subject.start(claim, focus, mockk()))
		coVerify(exactly = 0) { physical.refreshShared(any(), any(), any(), any()) }
	}

	@Test
	fun `last background demand retirement reports physical cleanup failure`() = runTest {
		val subject = subject()
		demands = listOf(demand(false))
		subject.reconcileAmbientJoin(baseline, 2L)
		demands = emptyList()
		coEvery { physical.closeShared() } returns completeAck().copy(
			registrationRemovalOutcome = RegistrationRemovalOutcome.FAILED,
			appDrainComplete = false, status = SourceStopStatus.PROVIDER_FAILED,
		)
		assertIs<AmbientLocationRuntimeJoinResult.Unavailable>(subject.reconcileAmbientJoin(null, 3L))
		coVerify(exactly = 1) { physical.closeShared() }
	}

	@Test
	fun `changed physical request does not restart after incomplete session retirement`() = runTest {
		val subject = subject()
		demands = listOf(demand(false))
		subject.reconcileAmbientJoin(baseline, 2L)
		demands = demands + demand(true)
		subject.start(claim, focus, mockk())
		demands = listOf(demand(false))
		coEvery { physical.refreshShared(baseline, sink, null, 120_000L) } returns null
		coEvery { physical.quiesce(cutoff) } returns completeAck().copy(
			registrationRemovalOutcome = RegistrationRemovalOutcome.FAILED,
			appDrainComplete = false, status = SourceStopStatus.PROVIDER_FAILED,
		)
		assertIs<OwnedSourceShutdown.Incomplete>(subject.quiesce(cutoff).toOwnedShutdown())
		coVerify(exactly = 0) { physical.reconfigureShared(any(), any(), any(), any()) }
	}

	@Test
	fun `joined plan retains stronger background and passive adds no active work`() {
		assertEquals(focus.copy(revision = baseline.revision), joinLocationPlans(baseline, focus))
		assertEquals(focus, joinLocationPlans(focus, baseline.copy(mode = LocationMode.PASSIVE)))
		assertEquals(baseline, joinLocationPlans(null, baseline))
		assertNull(joinLocationPlans(null, null))
		assertEquals(focus, joinLocationPlans(focus, baseline.copy(mode = LocationMode.DISABLED)))
		assertEquals(baseline, joinLocationPlans(focus.copy(mode = LocationMode.DISABLED), baseline))
		assertNull(joinLocationPlans(baseline.copy(adaptiveProfileVersion = null), focus)?.adaptiveProfileVersion)
	}

	@Test
	fun `foreign Location disable cannot retire current capture`() = runTest {
		val subject = subject()
		demands = listOf(demand(true))
		assertIs<SourceStartResult.Started>(subject.start(claim, focus, mockk()))
		assertIs<SourceApplyResult.Failed>(subject.reconfigure(
			claim.copy(serviceRunId = "other-run"), focus.copy(mode = LocationMode.DISABLED), mockk()))
		coVerify(exactly = 0) { physical.quiesce(any()) }
		coEvery { physical.quiesce(any()) } returns completeAck()
		assertIs<OwnedSourceShutdown.Released>(subject.shutdownIfOwned(claim, cutoff))
	}

	@Test
	fun `failed physical Location successor stays cleanable without discarding logical predecessor`() = runTest {
		val subject = subject()
		demands = listOf(demand(true))
		assertIs<SourceStartResult.Started>(subject.start(claim, focus, mockk()))
		val successor = claim.copy(actionId = "successor", leaseGeneration = 2L)
		demands = listOf(demand(true).copy(lifecycleLeaseGeneration = 2L))
		coEvery { physical.refreshShared(focus, sink, successor, any()) } returns
			SourceApplyResult.Failed(appliedState(SourceKind.LOCATION, focus.revision, null,
				SourceApplyStatus.FAILED, 1L), retryable = true)
		val acknowledgement = completeAck().withSessionMembership(successor)
		coEvery { physical.shutdownIfOwned(successor, cutoff) } returns
			OwnedSourceShutdown.Released(null, acknowledgement)
		assertIs<SourceApplyResult.Failed>(subject.reconfigure(successor, focus, mockk()))
		assertIs<OwnedSourceShutdown.Released>(subject.shutdownIfOwned(successor, cutoff))
		coVerify(exactly = 0) { physical.quiesce(any()) }
		coEvery { physical.quiesce(any()) } returns completeAck()
		assertIs<OwnedSourceShutdown.Released>(subject.shutdownIfOwned(claim, cutoff))
	}

	@Test
	fun `Location close preserves background and only releases capture after its acknowledgement`() = runTest {
		val subject = subject()
		demands = listOf(demand(false))
		subject.reconcileAmbientJoin(baseline, 2L)
		demands = demands + demand(true)
		subject.start(claim, focus, mockk())
		coEvery { physical.sharedSessionCutoff(any()) } returnsMany listOf(
			completeAck().copy(appDrainComplete = false, status = SourceStopStatus.TIMED_OUT), completeAck(),
		)
		subject.close()
		assertIs<OwnedSourceShutdown.Released>(subject.shutdownIfOwned(claim, cutoff))
		coVerify(exactly = 2) { physical.sharedSessionCutoff(any()) }
		coVerify(exactly = 0) { physical.close() }
		coVerify(exactly = 0) { physical.quiesce(any()) }
	}

	private fun demand(capture: Boolean) = SourceDemandEntity(
		demandId = if (capture) "capture" else "background",
		consumerId = if (capture) "session:logical" else "ambient:location",
		sourceKind = SourceKind.LOCATION.stableCode,
		purpose = if (capture) SourceBrokerPurpose.SESSION_CAPTURE else SourceBrokerPurpose.AMBIENT_PRODUCT,
		logicalTrackingId = "logical".takeIf { capture }, serviceRunId = "run".takeIf { capture },
		manifestRevision = 1L.takeIf { capture }, lifecycleLeaseGeneration = 1L.takeIf { capture },
		sourcePolicyRevision = 2L, consentEpoch = 3L, persistenceEligible = true, qosCode = 2,
		maximumAgeMs = 120_000L, desiredLatencyMs = 10_000L,
		requestedBootId = "boot", requestedElapsedRealtimeNanos = 1L, requestedAtMs = 1L,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null, retireElapsedRealtimeNanos = null, retiredAtMs = null,
	)

	private fun completeAck() = SourceStopAck(
		SourceKind.LOCATION, SourceInstanceId("location-test"), 1L, 9L, 3L, 3L, 3L, 0L, null, null,
		RegistrationRemovalOutcome.NOT_REGISTERED, ProviderFlushOutcome.NOT_REQUESTED,
		ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE, true, SourceStopStatus.COMPLETE,
	)
}
