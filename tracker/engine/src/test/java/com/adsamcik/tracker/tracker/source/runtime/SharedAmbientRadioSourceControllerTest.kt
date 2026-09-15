package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class SharedAmbientRadioSourceControllerTest {
	private val unboundSink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }

	@Test
	fun `Wi-Fi session stop retires only session join while one ambient registration remains`() =
		runTest {
			val physical = mockk<WifiSourceRuntime>()
			val broker = mockk<SourceBroker>()
			val sinkFactory = mockk<DurableSourceEventSinkFactory>()
			every { physical.capabilities } returns MutableStateFlow(capabilities())
			every { sinkFactory.unbound } returns unboundSink
			val capture = demand(SourceKind.WIFI, SourceBrokerPurpose.SESSION_CAPTURE)
			val ambient = demand(SourceKind.WIFI, SourceBrokerPurpose.AMBIENT_PRODUCT)
			coEvery { broker.authorizationDemands(SourceKind.WIFI) } returnsMany listOf(
				listOf(capture, ambient),
				listOf(ambient),
			)
			val plan = wifiPlan()
			val claim = claim(SourceKind.WIFI)
			coEvery { physical.refreshShared(plan, unboundSink, claim) } returns null
			coEvery { physical.reconfigure(claim, plan, unboundSink) } returns
				SourceApplyResult.Applied(applied(SourceKind.WIFI, plan.revision, 7L))
			coEvery {
				physical.refreshShared(match { it.mode == WifiMode.BROADCAST_DRIVEN }, unboundSink, null)
			} returns SourceApplyResult.Applied(applied(SourceKind.WIFI, 2L, 7L))
			val cutoff = cutoff()
			coEvery { physical.sharedSessionCutoff(cutoff) } returns completeAck(
				SourceKind.WIFI,
				7L,
				RegistrationRemovalOutcome.NOT_REGISTERED,
			)
			val subject = SharedWifiSourceController(physical, broker, sinkFactory)

			assertIs<SourceStartResult.Started>(subject.start(claim, plan, mockk()))
			val acknowledgement = subject.quiesce(cutoff)

			assertEquals(7L, acknowledgement.registrationGeneration)
			assertEquals(RegistrationRemovalOutcome.NOT_REGISTERED,
				acknowledgement.registrationRemovalOutcome)
			coVerify(exactly = 1) { physical.reconfigure(claim, plan, unboundSink) }
			coVerify(exactly = 1) { physical.sharedSessionCutoff(cutoff) }
			coVerify(exactly = 0) { physical.quiesce(any()) }
			coVerify(exactly = 0) { physical.close() }
		}

	@Test
	fun `Cell refresh budget downgrades to callback-only without retiring ambient provider`() =
		runTest {
			val physical = mockk<CellSourceRuntime>()
			val broker = mockk<SourceBroker>()
			val sinkFactory = mockk<DurableSourceEventSinkFactory>()
			every { physical.capabilities } returns MutableStateFlow(capabilities())
			every { sinkFactory.unbound } returns unboundSink
			val capture = demand(SourceKind.CELL, SourceBrokerPurpose.SESSION_CAPTURE)
			val ambient = demand(SourceKind.CELL, SourceBrokerPurpose.AMBIENT_PRODUCT)
			coEvery { broker.authorizationDemands(SourceKind.CELL) } returnsMany listOf(
				listOf(capture, ambient),
				listOf(ambient),
			)
			val plan = cellPlan()
			val claim = claim(SourceKind.CELL)
			coEvery { physical.refreshShared(plan, unboundSink, claim) } returns null
			coEvery { physical.reconfigure(claim, plan, unboundSink) } returns
				SourceApplyResult.Applied(applied(SourceKind.CELL, plan.revision, 8L))
			coEvery {
				physical.refreshShared(match { it.mode == CellMode.OBSERVE_CHANGES }, unboundSink, null)
			} returns SourceApplyResult.Applied(applied(SourceKind.CELL, 2L, 8L))
			val cutoff = cutoff()
			coEvery { physical.sharedSessionCutoff(cutoff) } returns completeAck(
				SourceKind.CELL,
				8L,
				RegistrationRemovalOutcome.NOT_REGISTERED,
			)
			val subject = SharedCellSourceController(physical, broker, sinkFactory)

			assertIs<SourceStartResult.Started>(subject.start(claim, plan, mockk()))
			val acknowledgement = subject.quiesce(cutoff)

			assertEquals(8L, acknowledgement.registrationGeneration)
			coVerify(exactly = 1) { physical.reconfigure(claim, plan, unboundSink) }
			coVerify(exactly = 1) { physical.sharedSessionCutoff(cutoff) }
			coVerify(exactly = 0) { physical.quiesce(any()) }
		}

	@Test
	fun `default-off ambient reconciliation performs no physical API call`() = runTest {
		val wifi = mockk<WifiSourceRuntime>()
		val broker = mockk<SourceBroker>()
		val sinkFactory = mockk<DurableSourceEventSinkFactory>()
		every { wifi.capabilities } returns MutableStateFlow(capabilities())
		every { sinkFactory.unbound } returns unboundSink
		coEvery { broker.authorizationDemands(SourceKind.WIFI) } returns emptyList()
		val subject = SharedWifiSourceController(wifi, broker, sinkFactory)

		assertIs<AmbientWifiRuntimeJoinResult.Inactive>(subject.reconcileAmbientJoin())

		coVerify(exactly = 0) { wifi.refreshShared(any(), any(), any()) }
		coVerify(exactly = 0) { wifi.reconfigure(any(), any()) }
		coVerify(exactly = 0) { wifi.close() }
	}

	@Test
	fun `failed ambient Wi-Fi provider retirement remains typed unavailable`() = runTest {
		val physical = mockk<WifiSourceRuntime>()
		val broker = mockk<SourceBroker>()
		val sinkFactory = mockk<DurableSourceEventSinkFactory>()
		every { physical.capabilities } returns MutableStateFlow(capabilities())
		every { sinkFactory.unbound } returns unboundSink
		val ambient = demand(SourceKind.WIFI, SourceBrokerPurpose.AMBIENT_PRODUCT)
		coEvery { broker.authorizationDemands(SourceKind.WIFI) } returnsMany listOf(
			listOf(ambient),
			emptyList(),
		)
		coEvery { physical.refreshShared(any(), unboundSink, null) } returns null
		coEvery { physical.reconfigure(any(), unboundSink) } answers {
			SourceApplyResult.Applied(applied(SourceKind.WIFI, firstArg<WifiPlan>().revision, 9L))
		}
		coEvery { physical.closeShared() } returns completeAck(
			SourceKind.WIFI,
			9L,
			RegistrationRemovalOutcome.FAILED,
		).copy(status = SourceStopStatus.PROVIDER_FAILED, appDrainComplete = false)
		val subject = SharedWifiSourceController(physical, broker, sinkFactory)

		assertIs<AmbientWifiRuntimeJoinResult.Active>(subject.reconcileAmbientJoin())
		val unavailable = assertIs<AmbientWifiRuntimeJoinResult.Unavailable>(
			subject.reconcileAmbientJoin(),
		)

		assertEquals(true, unavailable.retryable)
		coVerify(exactly = 1) { physical.closeShared() }
	}

	private fun demand(source: SourceKind, purpose: String) = SourceDemandEntity(
		demandId = "${source.name}-$purpose",
		consumerId = if (purpose == SourceBrokerPurpose.SESSION_CAPTURE) {
			"session:logical-1"
		} else {
			"app:ambient:${source.name.lowercase()}"
		},
		sourceKind = source.stableCode,
		purpose = purpose,
		logicalTrackingId = "logical-1".takeIf {
			purpose == SourceBrokerPurpose.SESSION_CAPTURE
		},
		serviceRunId = "run-1".takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE },
		manifestRevision = 1L.takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE },
		lifecycleLeaseGeneration = 1L.takeIf {
			purpose == SourceBrokerPurpose.SESSION_CAPTURE
		},
		sourcePolicyRevision = 2L,
		consentEpoch = 3L,
		persistenceEligible = true,
		qosCode = if (purpose == SourceBrokerPurpose.SESSION_CAPTURE) 3 else 0,
		minimumAcquisitionSpec = if (source == SourceKind.WIFI) {
			"wifi:v1:broadcast"
		} else {
			"cell:v1:callback"
		},
		adaptiveReductionAllowed = true,
		maximumAgeMs = 600_000L,
		desiredLatencyMs = Long.MAX_VALUE,
		requestedDeliveryLatencyMs = null,
		requestedBootId = "boot-1",
		requestedElapsedRealtimeNanos = 1L,
		requestedAtMs = 1L,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private fun wifiPlan() = WifiPlan(
		revision = 9L,
		mode = WifiMode.BROADCAST_DRIVEN,
		minimumAttemptIntervalMs = 60_000L,
		maximumAcceptableResultAgeMs = 60_000L,
		unchangedResultDedupeWindowMs = 120_000L,
		backoff = RetryBackoff(30_000L, 1_800_000L),
	)

	private fun cellPlan() = CellPlan(
		revision = 9L,
		mode = CellMode.OBSERVE_AND_SPARSE_REFRESH,
		minimumRefreshAttemptIntervalMs = 120_000L,
		maximumAcceptableCachedAgeMs = 60_000L,
		subscriptionIds = setOf(1),
		backoff = RetryBackoff(30_000L, 1_800_000L),
	)

	private fun claim(source: SourceKind) = SourceRuntimeClaim(
		source,
		"action-${source.name}",
		1,
		1L,
		"logical-1",
		"run-1",
	)

	private fun cutoff() = SessionCutoff("logical-1", 500L, 1_000L, Long.MAX_VALUE)

	private fun applied(source: SourceKind, revision: Long, generation: Long) =
		AppliedSourcePlan(
			desiredRevision = revision,
			appliedRevision = revision,
			source = source,
			sourceInstanceId = SourceInstanceId("${source.name.lowercase()}-1"),
			registrationGeneration = generation,
			appliedAtElapsedRealtimeNanos = 1L,
			status = SourceApplyStatus.APPLIED,
		)

	private fun completeAck(
		source: SourceKind,
		generation: Long,
		removal: RegistrationRemovalOutcome,
	) = SourceStopAck(
		source,
		SourceInstanceId("${source.name.lowercase()}-1"),
		generation,
		2L,
		3L,
		3L,
		3L,
		0L,
		null,
		null,
		removal,
		ProviderFlushOutcome.NOT_SUPPORTED,
		ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE,
		true,
		SourceStopStatus.COMPLETE,
	)

	private fun capabilities() = SourceCapabilities(
		available = true,
		batchingSupported = false,
		flushSupported = false,
		maximumBatchSize = null,
		minimumDelayMs = null,
	)
}
