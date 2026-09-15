package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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
