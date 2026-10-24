package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.shared.base.location.UiLocationRequest

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.tracker.source.coordinator.adaptiveLocationPlan
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class SharedLocationSourceControllerTest {
	@Test
	fun `temporary high quality lease changes passive transport without changing durable purpose`() {
		val background = LocationPlan(9, LocationBackend.FUSED, LocationMode.PASSIVE,
			120_000, 0, 0f, 0, preciseLocationAvailable = true,
			passiveSystemDelivery = true)
		val temporary = LocationPlan(0, LocationBackend.FUSED, LocationMode.HIGH_ACCURACY,
			2_000, 500, 0f, 0, preciseLocationAvailable = true)
		val joined = joinUiLocationPlan(background, temporary)

		assertEquals(LocationMode.HIGH_ACCURACY, joined.mode)
		assertEquals(false, joined.passiveSystemDelivery)
		assertEquals(background, joinUiLocationPlan(background, null))
	}
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
		every { physical.prepareUiLease() } returns Unit
		every { physical.failUiLeases() } returns Unit
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
	fun `temporary map lease works without a durable purpose and does not create one`() = runTest {
		val subject = subject()
		coEvery { physical.applyUiOnly(any()) } returns true
		coEvery { physical.closeShared() } returns completeAck()
		val id = requireNotNull(subject.acquireUiLease(
			UiLocationRequest("map-location-indicator", 2_000L, highAccuracy = true),
		))
		coVerify(exactly = 1) { physical.applyUiOnly(match {
			it.mode == LocationMode.HIGH_ACCURACY && it.requestedIntervalMs == 2_000L
		}) }
		coVerify(exactly = 0) { physical.refreshShared(any(), any(), any(), any()) }
		subject.releaseUiLease(id)
		coVerify(exactly = 1) { physical.closeShared() }
	}

	@Test
	fun `map and game leases release independently without creating a tracking purpose`() = runTest {
		val subject = subject()
		val plans = mutableListOf<LocationPlan>()
		coEvery { physical.applyUiOnly(any()) } answers {
			plans += firstArg<LocationPlan>()
			true
		}
		coEvery { physical.closeShared() } returns completeAck()
		val map = requireNotNull(subject.acquireUiLease(
			UiLocationRequest("my-location", 2_000L, 500L, highAccuracy = true, maximumAgeMillis = 10_000L),
		))
		val game = requireNotNull(subject.acquireUiLease(
			UiLocationRequest("game", 1_000L, 250L, highAccuracy = true, maximumAgeMillis = 5_000L),
		))
		assertEquals(listOf(2_000L, 1_000L), plans.map { it.requestedIntervalMs })
		subject.releaseUiLease(map)
		assertEquals(1_000L, plans.last().requestedIntervalMs)
		assertEquals(250L, plans.last().minimumUpdateIntervalMs)
		coVerify(exactly = 0) { physical.closeShared() }
		val callsAfterMapRelease = plans.size
		subject.releaseUiLease(map) // A repeated/stale release cannot change the surviving game lease.
		assertEquals(callsAfterMapRelease, plans.size)
		subject.releaseUiLease(game)
		coVerify(exactly = 1) { physical.closeShared() }
		coVerify(exactly = 0) { physical.refreshShared(any(), any(), any(), any()) }
		assertEquals(emptyList<SourceDemandEntity>(), demands)
		assertTrue(plans.all { it.mode == LocationMode.HIGH_ACCURACY && it.maximumBatchDelayMs == 0L })
	}

	@Test
	fun `cold ambient demand is not retired for a temporary map lease`() = runTest {
		val subject = subject()
		demands = listOf(demand(false))
		assertNull(subject.acquireUiLease(UiLocationRequest("map", 2_000L,
			highAccuracy = true)))
		coVerify(exactly = 0) { physical.closeShared() }
		coVerify(exactly = 0) { physical.applyUiOnly(any()) }
	}

	@Test
	fun `broker read failure cannot create an uncoordinated physical UI request`() = runTest {
		val subject = subject()
		coEvery { broker.authorizationDemands(SourceKind.LOCATION) } throws
			IllegalStateException("SourceBroker unavailable")
		assertFailsWith<IllegalStateException> {
			subject.acquireUiLease(UiLocationRequest("map", 2_000L))
		}
		coVerify(exactly = 0) { physical.applyUiOnly(any()) }
		coVerify(exactly = 0) { physical.refreshShared(any(), any(), any(), any()) }
	}

	@Test
	fun `broker failure during lease release fences temporary consumers and retires physical work`() = runTest {
		val subject = subject()
		coEvery { physical.applyUiOnly(any()) } returns true
		coEvery { physical.closeShared() } returns completeAck()
		val lease = requireNotNull(subject.acquireUiLease(UiLocationRequest("map", 2_000L)))
		coEvery { broker.authorizationDemands(SourceKind.LOCATION) } throws
			IllegalStateException("SourceBroker unavailable")
		assertFailsWith<IllegalStateException> { subject.releaseUiLease(lease) }
		coVerify(exactly = 1) { physical.closeShared() }
		io.mockk.verify(exactly = 1) { physical.failUiLeases() }
	}

	@Test
	fun `temporary game lease raises low quality tracking then restores its plan`() = runTest {
		val subject = subject()
		demands = listOf(demand(false))
		val lowPower = baseline.copy(mode = LocationMode.LOW_POWER, requestedIntervalMs = 60_000L)
		assertIs<AmbientLocationRuntimeJoinResult.Active>(subject.reconcileAmbientJoin(lowPower, 2L))
		val id = requireNotNull(subject.acquireUiLease(
			UiLocationRequest("mini-game-location", 1_000L, 500L, highAccuracy = true,
				maximumAgeMillis = 2_000L),
		))
		coVerify(exactly = 1) { physical.refreshShared(match {
			it.mode == LocationMode.HIGH_ACCURACY && it.requestedIntervalMs == 1_000L &&
				it.maximumBatchDelayMs == 0L
		}, sink, null, 120_000L) }
		subject.releaseUiLease(id)
		coVerify(exactly = 2) { physical.refreshShared(lowPower, sink, null, 120_000L) }
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
	fun `My location overrides a known paused background plan without resuming tracking`() = runTest {
		val subject = subject()
		demands = listOf(demand(false))
		coEvery { physical.closeShared() } returns completeAck()
		assertIs<AmbientLocationRuntimeJoinResult.Inactive>(
			subject.reconcileAmbientJoin(baseline.copy(mode = LocationMode.DISABLED), 2L),
		)
		coEvery { physical.applyUiOnly(any()) } returns true
		val lease = requireNotNull(subject.acquireUiLease(
			UiLocationRequest("my-location", 2_000L, 500L, highAccuracy = true, maximumAgeMillis = 10_000L),
		))
		coVerify(exactly = 1) { physical.applyUiOnly(match {
			it.mode == LocationMode.HIGH_ACCURACY && it.requestedIntervalMs == 2_000L &&
				it.minimumUpdateIntervalMs == 500L && it.maximumBatchDelayMs == 0L
		}) }
		subject.releaseUiLease(lease)
		coVerify(exactly = 2) { physical.closeShared() }
		coVerify(exactly = 0) { physical.refreshShared(any(), any(), any(), any()) }
		coVerify(exactly = 0) { physical.reconfigureShared(any(), any(), any(), any()) }
		assertEquals(listOf(demand(false)), demands)
	}

	@Test
	fun `pausing and restoring background preserves independently released map and game leases`() = runTest {
		val subject = subject()
		demands = listOf(demand(false))
		assertIs<AmbientLocationRuntimeJoinResult.Active>(subject.reconcileAmbientJoin(baseline, 2L))
		val map = requireNotNull(subject.acquireUiLease(
			UiLocationRequest("my-location", 2_000L, 500L, highAccuracy = true, maximumAgeMillis = 10_000L),
		))
		val game = requireNotNull(subject.acquireUiLease(
			UiLocationRequest("game", 1_000L, 250L, highAccuracy = true, maximumAgeMillis = 2_000L),
		))
		val temporaryPlans = mutableListOf<LocationPlan>()
		var retired = false
		coEvery { physical.applyUiOnly(any()) } answers {
			temporaryPlans += firstArg<LocationPlan>()
			retired
		}
		coEvery { physical.closeShared() } answers { retired = true; completeAck() }
		assertIs<AmbientLocationRuntimeJoinResult.Inactive>(
			subject.reconcileAmbientJoin(baseline.copy(mode = LocationMode.DISABLED), 2L),
		)
		coVerify(exactly = 1) { physical.closeShared() }
		assertEquals(listOf(1_000L, 1_000L), temporaryPlans.map(LocationPlan::requestedIntervalMs))
		subject.releaseUiLease(map)
		assertEquals(1_000L, temporaryPlans.last().requestedIntervalMs)
		assertEquals(250L, temporaryPlans.last().minimumUpdateIntervalMs)
		subject.releaseUiLease(map)
		assertEquals(3, temporaryPlans.size)
		assertIs<AmbientLocationRuntimeJoinResult.Active>(subject.reconcileAmbientJoin(baseline, 2L))
		coVerify(exactly = 2) { physical.refreshShared(match {
			it.mode == LocationMode.HIGH_ACCURACY && it.requestedIntervalMs == 1_000L &&
				it.minimumUpdateIntervalMs == 250L && it.maximumBatchDelayMs == 0L
		}, sink, null, 120_000L) }
		subject.releaseUiLease(game)
		coVerify(exactly = 2) { physical.refreshShared(baseline, sink, null, 120_000L) }
		coVerify(exactly = 1) { physical.closeShared() }
		assertEquals(listOf(demand(false)), demands)
	}

	@Test
	fun `paused background cannot replace failed physical retirement and failed lease can be retried`() = runTest {
		val subject = subject()
		demands = listOf(demand(false))
		coEvery { physical.closeShared() } returns completeAck().copy(
			registrationRemovalOutcome = RegistrationRemovalOutcome.FAILED,
			appDrainComplete = false,
			status = SourceStopStatus.PROVIDER_FAILED,
		)
		assertIs<AmbientLocationRuntimeJoinResult.Unavailable>(
			subject.reconcileAmbientJoin(baseline.copy(mode = LocationMode.DISABLED), 2L),
		)
		coEvery { physical.applyUiOnly(any()) } returns false
		assertNull(subject.acquireUiLease(
			UiLocationRequest("failed-map", 1_000L, 250L, highAccuracy = true),
		))
		coVerify(exactly = 1) { physical.applyUiOnly(any()) }
		coVerify(exactly = 0) { physical.refreshShared(any(), any(), any(), any()) }
		coEvery { physical.closeShared() } returns completeAck()
		coEvery { physical.applyUiOnly(any()) } returns true
		val retry = requireNotNull(subject.acquireUiLease(
			UiLocationRequest("retry-map", 6_000L, 1_000L, highAccuracy = true),
		))
		coVerify(exactly = 1) { physical.applyUiOnly(match {
			it.requestedIntervalMs == 6_000L && it.minimumUpdateIntervalMs == 1_000L
		}) }
		subject.releaseUiLease(retry)
		coVerify(exactly = 0) { physical.reconfigureShared(any(), any(), any(), any()) }
	}

	@Test
	fun `paused baseline cannot retire a newer unreconciled background demand for UI use`() = runTest {
		val subject = subject()
		demands = listOf(demand(false))
		coEvery { physical.closeShared() } returns completeAck()
		subject.reconcileAmbientJoin(baseline.copy(mode = LocationMode.DISABLED), 2L)
		demands = listOf(demand(false).copy(sourcePolicyRevision = 3L))
		assertNull(subject.acquireUiLease(UiLocationRequest("my-location", 2_000L, highAccuracy = true)))
		coVerify(exactly = 1) { physical.closeShared() }
		coVerify(exactly = 0) { physical.applyUiOnly(any()) }
		coVerify(exactly = 0) { physical.refreshShared(any(), any(), any(), any()) }
	}

	@Test
	fun `known paused background cannot displace an unjoined session Location demand`() = runTest {
		val subject = subject()
		demands = listOf(demand(false))
		coEvery { physical.closeShared() } returns completeAck()
		subject.reconcileAmbientJoin(baseline.copy(mode = LocationMode.DISABLED), 2L)
		demands += demand(true)
		assertNull(subject.acquireUiLease(UiLocationRequest("game", 1_000L, highAccuracy = true)))
		coVerify(exactly = 1) { physical.closeShared() }
		coVerify(exactly = 0) { physical.applyUiOnly(any()) }
	}

	@Test
	fun `known paused background cannot authorize a foreign ambient consumer for UI use`() = runTest {
		val subject = subject()
		demands = listOf(demand(false))
		coEvery { physical.closeShared() } returns completeAck()
		subject.reconcileAmbientJoin(baseline.copy(mode = LocationMode.DISABLED), 2L)
		demands += demand(false).copy(demandId = "foreign", consumerId = "ambient:foreign")
		assertNull(subject.acquireUiLease(UiLocationRequest("my-location", 2_000L, highAccuracy = true)))
		coVerify(exactly = 1) { physical.closeShared() }
		coVerify(exactly = 0) { physical.applyUiOnly(any()) }
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
	fun `End returns its retirement receipt without awaiting a cancellable background replacement`() = runTest {
		val subject = subject()
		demands = listOf(demand(false))
		subject.reconcileAmbientJoin(baseline, 2L)
		demands = demands + demand(true)
		subject.start(claim, focus, mockk())
		demands = listOf(demand(false))
		coEvery { physical.refreshShared(baseline, sink, null, 120_000L) } returns null
		val retired = completeAck()
		coEvery { physical.quiesce(cutoff) } returns retired
		coEvery { physical.reconfigureShared(any(), any(), any(), any()) } coAnswers { awaitCancellation() }

		val stopped = withTimeout(1_000L) { subject.shutdownIfOwned(claim, cutoff) }

		assertEquals(OwnedSourceShutdown.Released(retired.providerKeyOrNull(),
			retired.withSessionMembership(claim)), stopped)
		coVerify(exactly = 0) { physical.reconfigureShared(any(), any(), any(), any()) }
		// A cancelled later ambient refresh cannot reacquire the retired session claim.
		coEvery { physical.reconfigureShared(any(), any(), any(), any()) } throws
			CancellationException("background replacement cancelled")
		assertFailsWith<CancellationException> { subject.reconcileAmbientJoin(baseline, 2L) }
		assertIs<OwnedSourceShutdown.NotOwned>(subject.shutdownIfOwned(claim, cutoff))
		coVerify(exactly = 1) { physical.quiesce(cutoff) }
	}

	@Test
	fun `post-End background refresh retains the temporary UI lease and original background authority`() = runTest {
		val subject = subject()
		demands = listOf(demand(false))
		subject.reconcileAmbientJoin(baseline, 2L)
		demands = demands + demand(true)
		subject.start(claim, focus, mockk())
		val temporary = UiLocationRequest("my-location", 1_000L, 500L,
			highAccuracy = true, maximumAgeMillis = 5_000L)
		val lease = requireNotNull(subject.acquireUiLease(temporary))
		demands = listOf(demand(false))
		coEvery { physical.refreshShared(any(), sink, null, 120_000L) } returns null
		coEvery { physical.quiesce(cutoff) } returns completeAck()

		assertIs<OwnedSourceShutdown.Released>(subject.shutdownIfOwned(claim, cutoff))
		coVerify(exactly = 0) { physical.reconfigureShared(any(), any(), any(), any()) }
		coEvery { physical.reconfigureShared(any(), sink, null, 120_000L) } answers {
			SourceApplyResult.Applied(appliedState(SourceKind.LOCATION, firstArg<LocationPlan>().revision,
				null, SourceApplyStatus.APPLIED, 1L))
		}

		assertIs<AmbientLocationRuntimeJoinResult.Active>(subject.reconcileAmbientJoin(baseline, 2L))
		coVerify(exactly = 1) { physical.reconfigureShared(match {
			it.mode == LocationMode.HIGH_ACCURACY && it.requestedIntervalMs == 1_000L
		}, sink, null, 120_000L) }
		coEvery { physical.refreshShared(baseline, sink, null, 120_000L) } returns
			SourceApplyResult.Applied(appliedState(SourceKind.LOCATION, baseline.revision,
				null, SourceApplyStatus.APPLIED, 1L))
		subject.releaseUiLease(lease)
		coVerify(exactly = 2) { physical.refreshShared(baseline, sink, null, 120_000L) }
		assertEquals(listOf(demand(false)), demands)
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
	fun `foreground UI demand never reduces a durable high accuracy plan`() {
		val uiBalanced = baseline.copy(mode = LocationMode.BALANCED, requestedIntervalMs = 2_000L,
			maximumBatchDelayMs = 0L)
		val joined = joinUiLocationPlan(focus.copy(mode = LocationMode.PROBE), uiBalanced)
		assertEquals(LocationMode.HIGH_ACCURACY, joined.mode)
		assertEquals(0L, joined.maximumBatchDelayMs)
		assertEquals(2_000L, joined.requestedIntervalMs)
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
		demandId = if (capture) {
			"capture"
		} else {
			"background"
		},
		consumerId = if (capture) {
			"session:logical"
		} else {
			"ambient:location"
		},
		sourceKind = SourceKind.LOCATION.stableCode,
		purpose = if (capture) {
			SourceBrokerPurpose.SESSION_CAPTURE
		} else {
			SourceBrokerPurpose.AMBIENT_PRODUCT
		},
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
