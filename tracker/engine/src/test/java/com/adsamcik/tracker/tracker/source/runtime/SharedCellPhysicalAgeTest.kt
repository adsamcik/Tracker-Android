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
import io.mockk.capture
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SharedCellPhysicalAgeTest {
	@Test
	fun `stricter session age does not discard readings still useful to background Cell`() = runTest {
		val physical = mockk<CellSourceRuntime>()
		val broker = mockk<SourceBroker>()
		val sinkFactory = mockk<DurableSourceEventSinkFactory>()
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
		val claim = SourceRuntimeClaim(SourceKind.CELL, "action-cell", 1, 1L,
			"session-a", "run-a")
		val session = demand(SourceBrokerPurpose.SESSION_CAPTURE, 10_000L)
		val background = demand(SourceBrokerPurpose.AMBIENT_PRODUCT, 600_000L)
		val appliedPlan = slot<CellPlan>()
		every { physical.capabilities } returns MutableStateFlow(SourceCapabilities(
			available = true, batchingSupported = false, flushSupported = false,
			maximumBatchSize = null, minimumDelayMs = null,
		))
		every { sinkFactory.unbound } returns sink
		coEvery { broker.authorizationDemands(SourceKind.CELL) } returns listOf(session, background)
		coEvery { physical.refreshShared(capture(appliedPlan), sink, claim) } returns
			SourceApplyResult.Applied(AppliedSourcePlan(
				desiredRevision = 9L, appliedRevision = 9L, source = SourceKind.CELL,
				sourceInstanceId = SourceInstanceId("cell-1"), registrationGeneration = 1L,
				appliedAtElapsedRealtimeNanos = 1L, status = SourceApplyStatus.APPLIED,
			))
		val requested = CellPlan(9L, CellMode.OBSERVE_AND_SPARSE_REFRESH, 60_000L,
			10_000L, setOf(1), RetryBackoff(30_000L, 1_800_000L))

		assertIs<SourceStartResult.Started>(
			SharedCellSourceController(physical, broker, sinkFactory).start(claim, requested, mockk()),
		)
		assertEquals(600_000L, appliedPlan.captured.maximumAcceptableCachedAgeMs)
		assertEquals(60_000L, appliedPlan.captured.minimumRefreshAttemptIntervalMs)
	}

	private fun demand(purpose: String, maximumAgeMs: Long): SourceDemandEntity =
		mockk<SourceDemandEntity>().also { demand ->
			every { demand.purpose } returns purpose
			every { demand.consumerId } returns if (purpose == SourceBrokerPurpose.AMBIENT_PRODUCT)
				"app:ambient:cell" else "session:session-a"
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
