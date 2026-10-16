package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SharedWifiPhysicalAgeTest {
	@Test
	fun `stricter session age does not discard readings still useful to background Wi-Fi`() = runTest {
		val physical = mockk<WifiSourceRuntime>()
		val broker = mockk<SourceBroker>()
		val sinkFactory = mockk<DurableSourceEventSinkFactory>()
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
		val claim = SourceRuntimeClaim(SourceKind.WIFI, "action-wifi", 1, 1L,
			"session-a", "run-a")
		val session = demand(SourceBrokerPurpose.SESSION_CAPTURE, 10_000L)
		val background = demand(SourceBrokerPurpose.AMBIENT_PRODUCT, 600_000L)
		val appliedPlan = slot<WifiPlan>()
		every { physical.capabilities } returns MutableStateFlow(SourceCapabilities(
			available = true, batchingSupported = false, flushSupported = false,
			maximumBatchSize = null, minimumDelayMs = null,
		))
		every { sinkFactory.unbound } returns sink
		coEvery { broker.authorizationDemands(SourceKind.WIFI) } returns listOf(session, background)
		coEvery { physical.refreshShared(capture(appliedPlan), sink, claim) } returns
			SourceApplyResult.Applied(AppliedSourcePlan(
				desiredRevision = 9L, appliedRevision = 9L, source = SourceKind.WIFI,
				sourceInstanceId = SourceInstanceId("wifi-1"), registrationGeneration = 1L,
				appliedAtElapsedRealtimeNanos = 1L, status = SourceApplyStatus.APPLIED,
			))
		val requested = WifiPlan(9L, WifiMode.BROADCAST_DRIVEN, 60_000L,
			10_000L, 120_000L, RetryBackoff(30_000L, 1_800_000L))

		assertIs<SourceStartResult.Started>(
			SharedWifiSourceController(physical, broker, sinkFactory).start(claim, requested, mockk()),
		)
		assertEquals(600_000L, appliedPlan.captured.maximumAcceptableResultAgeMs)
		assertEquals(60_000L, appliedPlan.captured.minimumAttemptIntervalMs)
	}

	private fun demand(purpose: String, maximumAgeMs: Long): SourceDemandEntity =
		mockk<SourceDemandEntity>().also { demand ->
			every { demand.purpose } returns purpose
			every { demand.consumerId } returns if (purpose == SourceBrokerPurpose.AMBIENT_PRODUCT)
				{
				"app:ambient:wifi"
			} else {
				"session:session-a"
			}
			every { demand.logicalTrackingId } returns
				"session-a".takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE }
			every { demand.serviceRunId } returns
				"run-a".takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE }
			every { demand.lifecycleLeaseGeneration } returns
				1L.takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE }
			every { demand.sourcePolicyRevision } returns 9L
			every { demand.maximumAgeMs } returns maximumAgeMs
		}
}
