package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.source.location.ProtectedLocationCanonicalDrainResult
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationCanonicalHandoff
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationCanonicalInactiveReason
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SourceProductDrainContractTest {
	@Test
	fun `protected Location collaborator absence is explicit and never a silent success`() = runTest {
		val request = request(
			source = SourceKind.LOCATION,
			target = SourceProductDrainTarget.ProtectedLocationWriter,
		)

		RequiredProtectedLocationSourceDrain().drainThrough(request) shouldBe
			SourceProductDrainResult.Inactive(
				request,
				"PROTECTED_LOCATION_SOURCE_DRAIN_COLLABORATOR_REQUIRED",
			)
	}

	@Test
	fun `complete settlement cannot understate the requested source high-water`() {
		val request = request(
			source = SourceKind.WIFI,
			target = SourceProductDrainTarget.SourceLocalWriter(
				destination = "SESSION_WIFI",
				writerOwner = "WIFI_SESSION_FACTS",
				writerOwnerGeneration = 2L,
				projectionId = "wifi-session-facts",
				projectionVersion = 1,
				bindingGeneration = 1L,
			),
		)

		shouldThrow<IllegalArgumentException> {
			SourceProductDrainResult.Complete(
				request,
				lastMaterializedAdmissionOrdinal = request.sourceHighWaterAdmissionOrdinal - 1L,
				factsInserted = 0,
				eventsValidated = 0,
			)
		}
	}

	@Test
	fun `protected Location adapter forwards exact membership and preserves committed evidence`() = runTest {
		val handoff = mockk<ProtectedLocationCanonicalHandoff>(relaxed = true)
		val request = request(
			source = SourceKind.LOCATION,
			target = SourceProductDrainTarget.ProtectedLocationWriter,
		)
		every { handoff.requestDrain() } returns Unit
		coEvery { handoff.drainThrough("logical", "run", 7L) } returns
			ProtectedLocationCanonicalDrainResult.Complete(
				lastCommittedOrdinal = 7L,
				observationsCommitted = 1,
				acceptedSamplesCommitted = 1,
				rejectedObservationsCommitted = 0,
				lifecycleSettled = 0,
			)
		val adapter = ProtectedLocationCanonicalSourceDrain(handoff)

		adapter.requestDrain()
		adapter.drainThrough(request) shouldBe SourceProductDrainResult.Complete(
			request,
			lastMaterializedAdmissionOrdinal = 7L,
			factsInserted = 2,
			eventsValidated = 1,
		)

		verify(exactly = 1) { handoff.requestDrain() }
		coVerify(exactly = 1) { handoff.drainThrough("logical", "run", 7L) }
	}

	@Test
	fun `protected Location adapter never promotes deferred inactive or nonterminal failure`() = runTest {
		val handoff = mockk<ProtectedLocationCanonicalHandoff>()
		val request = request(
			source = SourceKind.LOCATION,
			target = SourceProductDrainTarget.ProtectedLocationWriter,
		)
		coEvery { handoff.drainThrough(any(), any(), any()) } returnsMany listOf(
			ProtectedLocationCanonicalDrainResult.Deferred(3L, 4L, "ACTIVE_SESSION_CHANGED"),
			ProtectedLocationCanonicalDrainResult.Inactive(
				3L,
				ProtectedLocationCanonicalInactiveReason.NO_ACTIVE_LANE,
			),
			ProtectedLocationCanonicalDrainResult.Failed(
				3L,
				4L,
				"LOCATION_CANONICAL_PENDING_COMMIT",
				terminal = false,
			),
		)
		val adapter = ProtectedLocationCanonicalSourceDrain(handoff)

		adapter.drainThrough(request).shouldBeInstanceOf<SourceProductDrainResult.Deferred>()
			.reason shouldBe "ACTIVE_SESSION_CHANGED"
		adapter.drainThrough(request) shouldBe SourceProductDrainResult.Inactive(
			request = request,
			reason = ProtectedLocationCanonicalInactiveReason.NO_ACTIVE_LANE.name,
			lastMaterializedAdmissionOrdinal = 3L,
		)
		adapter.drainThrough(request).shouldBeInstanceOf<SourceProductDrainResult.Deferred>()
			.reason shouldBe "LOCATION_CANONICAL_PENDING_COMMIT"
	}

	@Test
	fun `protected Location terminal failure and authority change remain typed`() = runTest {
		val handoff = mockk<ProtectedLocationCanonicalHandoff>()
		val request = request(
			source = SourceKind.LOCATION,
			target = SourceProductDrainTarget.ProtectedLocationWriter,
		)
		coEvery { handoff.drainThrough(any(), any(), any()) } returnsMany listOf(
			ProtectedLocationCanonicalDrainResult.Failed(
				5L,
				6L,
				"LOCATION_ADAPTER_MOCK_PROVENANCE_UNVERIFIABLE",
				terminal = true,
			),
			ProtectedLocationCanonicalDrainResult.AuthorityChanged(
				5L,
				"LOCATION_CANONICAL_ACTIVE_SESSION_CHANGED",
			),
		)
		val adapter = ProtectedLocationCanonicalSourceDrain(handoff)

		adapter.drainThrough(request).shouldBeInstanceOf<SourceProductDrainResult.Failed>().let {
			it.terminalFailureRecorded shouldBe true
			it.failureCode shouldBe "LOCATION_ADAPTER_MOCK_PROVENANCE_UNVERIFIABLE"
		}
		adapter.drainThrough(request) shouldBe SourceProductDrainResult.AuthorityChanged(
			request = request,
			reason = "LOCATION_CANONICAL_ACTIVE_SESSION_CHANGED",
			lastMaterializedAdmissionOrdinal = 5L,
		)
	}

	private fun request(
		source: SourceKind,
		target: SourceProductDrainTarget,
	) = SourceProductDrainRequest(
		source = source,
		logicalTrackingId = "logical",
		serviceRunId = "run",
		cutoffElapsedRealtimeNanos = 2_000L,
		cutoffWallTimeMs = 2_000L,
		settlementHighWaterAdmissionOrdinal = 10L,
		sourceHighWaterAdmissionOrdinal = 7L,
		memberships = listOf(
			SourceDrainMembership(
				sourceInstanceId = "instance",
				registrationGeneration = 1L,
				lastAdmissionOrdinal = 7L,
				lastSourceSequence = 0L,
				appDrainComplete = true,
				providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
				stopStatus = "COMPLETE",
				unresolvedSequenceStart = null,
				unresolvedSequenceEndInclusive = null,
			),
		),
		target = target,
	)
}
