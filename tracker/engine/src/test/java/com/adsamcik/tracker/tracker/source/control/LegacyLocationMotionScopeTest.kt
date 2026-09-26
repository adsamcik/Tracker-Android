package com.adsamcik.tracker.tracker.source.control

import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import io.kotest.matchers.shouldBe
import org.junit.Test

class LegacyLocationMotionScopeTest {
	@Test
	fun `legacy captured Location still supplies motion after provider becomes shared and unbound`() {
		val controller = CollectionMotionController()
		// No adopted balance profile or Activity helper authority is needed for a Location scope.
		controller.configureScope("live-location-host", "boot", 0L)
		controller.onDurableEvidence(SourceEvidenceCandidate(
			providerDedupKey = null,
			logicalTrackingId = null,
			serviceRunId = null,
			source = SourceKind.LOCATION,
			sourceInstanceId = SourceInstanceId("shared-location"),
			registrationGeneration = 1L,
			sourceSequence = 1L,
			configRevision = 1L,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = "boot",
			observedElapsedRealtimeNanos = 1_000_000_000L,
			receivedElapsedRealtimeNanos = 1_000_000_000L,
			wallTimeMs = 1_000L,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = 0L,
			acquiredAtMs = 1_000L,
			quality = SourceQuality(),
			payloadVersion = 1,
			payload = LocationFixPayload(50.0, 14.0, 5f, null, null, 2f, null, "gps", false),
		))
		controller.policy.value.motionState shouldBe CollectionMotionState.MOVING
	}
}
