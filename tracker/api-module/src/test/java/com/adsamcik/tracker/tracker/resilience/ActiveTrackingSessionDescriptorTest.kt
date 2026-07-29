package com.adsamcik.tracker.tracker.resilience

import com.adsamcik.tracker.stats.api.PolicyTier
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class ActiveTrackingSessionDescriptorTest {
	private val descriptor = ActiveTrackingSessionDescriptor(
		isUserInitiated = true,
		isAmbient = false,
		policyTier = PolicyTier.PRECISION,
		logicalTrackingId = "logical-session",
		serviceRunId = "service-run-one",
	)

	@Test
	fun `replacement service run keeps logical identity but changes run correlation`() {
		val replacement = descriptor.forNewServiceRun(changedAtEpochMs = 100L)

		replacement.logicalTrackingId shouldBe descriptor.logicalTrackingId
		replacement.serviceRunId shouldNotBe descriptor.serviceRunId
		replacement.lifecycleRevision shouldBe 1L
		replacement.lifecycleChangedAtEpochMs shouldBe 100L
		replacement.isRestartEligible shouldBe true
	}

	@Test
	fun `stop candidate is durable lifecycle state and cannot restart`() {
		val candidate = descriptor.proposeStop(
			reason = TrackingStopCandidateReason.AUTOMATIC_ACTIVITY_INCOMPATIBLE,
			changedAtEpochMs = 200L,
		)

		candidate.logicalTrackingId shouldBe descriptor.logicalTrackingId
		candidate.lifecycleState shouldBe LogicalTrackingLifecycleState.STOP_CANDIDATE
		candidate.stopCandidate?.reason shouldBe
			TrackingStopCandidateReason.AUTOMATIC_ACTIVITY_INCOMPATIBLE
		candidate.isRestartEligible shouldBe false
		candidate.withdrawStopCandidate(changedAtEpochMs = 201L).isRestartEligible shouldBe true
	}
}
