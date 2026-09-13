package com.adsamcik.tracker.tracker.source.activity

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test

class ActivityCapturedFactCoalescerTest {
	@Test
	fun `transition bands expose active inactive and unobserved time without fabricating coverage`() {
		val window = coalesced(
			transition("walk-enter", 100L, CapturedActivityType.WALKING),
			transition("walk-exit", 400L, CapturedActivityType.WALKING, ActivityTransitionChange.EXIT),
			transition("still-enter", 500L, CapturedActivityType.STILL),
		)

		window.bands.map { Triple(it.key.intervalStartElapsedRealtimeNanos,
			it.key.intervalEndExclusiveElapsedRealtimeNanos, it.activity) } shouldContainExactly listOf(
			Triple(100L, 400L, CapturedActivityType.WALKING),
			Triple(500L, 1_000L, CapturedActivityType.STILL),
		)
		window.gaps shouldContainExactly listOf(
			ActivityCoverageGap(0L, 100L, ActivityCoverageGapReason.NO_QUALIFIED_EVIDENCE),
			ActivityCoverageGap(400L, 500L, ActivityCoverageGapReason.NO_QUALIFIED_EVIDENCE),
		)
		window.coverage shouldBe ActivityCoverage.PARTIAL
		window.activeTime shouldBe ActivityActiveTime(
			knownActiveDurationNanos = 300L,
			knownInactiveDurationNanos = 500L,
			unknownActivityDurationNanos = 0L,
			unobservedDurationNanos = 200L,
		)
		window.activeTime.completeActiveDurationNanos shouldBe null
	}

	@Test
	fun `transition coverage wins over overlapping sampled classification`() {
		val window = coalesced(
			transition("walking", 100L, CapturedActivityType.WALKING),
			sampled("vehicle", 200L, 90, 700L, CapturedActivityType.IN_VEHICLE),
		)

		window.bands shouldContainExactly listOf(
			band(100L, 1_000L, CapturedActivityType.WALKING, ActivityBandMechanism.TRANSITION,
				"walking"),
		)
		window.unchangedEvidenceCount shouldBe 1
	}

	@Test
	fun `sampled detail fills only its explicit validity interval`() {
		val window = coalesced(
			sampled("running", 200L, 80, 450L, CapturedActivityType.RUNNING),
		)

		window.bands shouldContainExactly listOf(
			band(
				200L,
				450L,
				CapturedActivityType.RUNNING,
				ActivityBandMechanism.SAMPLED_CLASSIFICATION,
				"running",
				confidence = ActivityBandConfidence.Sampled(80, 80, 1),
			),
		)
		window.gaps shouldContainExactly listOf(
			ActivityCoverageGap(0L, 200L, ActivityCoverageGapReason.NO_QUALIFIED_EVIDENCE),
			ActivityCoverageGap(450L, 1_000L, ActivityCoverageGapReason.NO_QUALIFIED_EVIDENCE),
		)
	}

	@Test
	fun `adjacent equal samples coalesce with their exact confidence range`() {
		val window = coalesced(
			sampled("walking-1", 100L, 80, 300L, CapturedActivityType.WALKING),
			sampled("walking-2", 300L, 90, 600L, CapturedActivityType.WALKING),
		)

		window.bands shouldContainExactly listOf(
			ActivityCapturedBand(
				key = ActivityCapturedFactKey(authority, 100L, 600L),
				activity = CapturedActivityType.WALKING,
				mechanism = ActivityBandMechanism.SAMPLED_CLASSIFICATION,
				confidence = ActivityBandConfidence.Sampled(80, 90, 2),
				evidence = listOf(reference("walking-1", 100L), reference("walking-2", 300L)),
			),
		)
	}

	@Test
	fun `exact semantic and unchanged observations are suppressed independently`() {
		val enter = transition("enter", 100L, CapturedActivityType.WALKING)
		val sameMeaning = enter.copy(reference = reference("same-meaning", 100L))
		val duplicateEnter = transition("duplicate-enter", 200L, CapturedActivityType.WALKING)
		val result = ActivityCapturedFactCoalescer.coalesce(
			request(observations = listOf(enter, enter, sameMeaning, duplicateEnter)),
		)
		val window = (result as ActivityCoalescingResult.Coalesced).window

		window.exactDuplicateCount shouldBe 1
		window.semanticDuplicateCount shouldBe 1
		window.unchangedEvidenceCount shouldBe 1
		window.bands.single().evidence shouldContainExactly listOf(reference("enter", 100L))
	}

	@Test
	fun `declared provider gap clears transition state and blocks pre-gap samples`() {
		val result = ActivityCapturedFactCoalescer.coalesce(
			request(
				observations = listOf(
					transition("walking", 100L, CapturedActivityType.WALKING),
					sampled("still", 150L, 95, 900L, CapturedActivityType.STILL),
				),
				declaredGaps = listOf(
					ActivityCoverageGap(
						400L,
						500L,
						ActivityCoverageGapReason.PROVIDER_DISCONTINUITY,
					),
				),
			),
		)
		val window = (result as ActivityCoalescingResult.Coalesced).window

		window.bands shouldContainExactly listOf(
			band(100L, 400L, CapturedActivityType.WALKING, ActivityBandMechanism.TRANSITION,
				"walking"),
		)
		window.gaps shouldContainExactly listOf(
			ActivityCoverageGap(0L, 100L, ActivityCoverageGapReason.NO_QUALIFIED_EVIDENCE),
			ActivityCoverageGap(400L, 500L, ActivityCoverageGapReason.PROVIDER_DISCONTINUITY),
			ActivityCoverageGap(500L, 1_000L, ActivityCoverageGapReason.NO_QUALIFIED_EVIDENCE),
		)
	}

	@Test
	fun `coalescing is deterministic across observation input order`() {
		val observations = listOf(
			transition("walking", 100L, CapturedActivityType.WALKING),
			transition("running", 250L, CapturedActivityType.RUNNING),
			transition("running-exit", 500L, CapturedActivityType.RUNNING,
				ActivityTransitionChange.EXIT),
			sampled("still", 600L, 90, 900L, CapturedActivityType.STILL),
		)

		ActivityCapturedFactCoalescer.coalesce(request(observations)) shouldBe
			ActivityCapturedFactCoalescer.coalesce(request(observations.reversed()))
	}

	@Test
	fun `same durable identity with different meaning is rejected`() {
		val first = transition("collision", 100L, CapturedActivityType.WALKING)
		val conflicting = transition("collision", 100L, CapturedActivityType.RUNNING)

		ActivityCapturedFactCoalescer.coalesce(request(listOf(first, conflicting))) shouldBe
			ActivityCoalescingResult.Rejected(
				ActivityCoalescingRejection.EVENT_IDENTITY_COLLISION,
			)
	}

	@Test
	fun `authorization mixing is rejected before composition`() {
		val wrongAuthority = authority.copy(authorizationRevision = 8L)
		val observation = transition("wrong", 100L, CapturedActivityType.WALKING).copy(
			authority = wrongAuthority,
		)

		ActivityCapturedFactCoalescer.coalesce(request(listOf(observation))) shouldBe
			ActivityCoalescingResult.Rejected(ActivityCoalescingRejection.AUTHORITY_MISMATCH)
	}

	private fun coalesced(
		vararg observations: ActivityCapturedObservation,
	): ActivityCapturedWindow = (
		ActivityCapturedFactCoalescer.coalesce(request(observations.toList())) as
			ActivityCoalescingResult.Coalesced
		).window

	private fun request(
		observations: List<ActivityCapturedObservation>,
		declaredGaps: List<ActivityCoverageGap> = emptyList(),
	) = ActivityCapturedCoalescingRequest(
		authority = authority,
		intervalStartElapsedRealtimeNanos = 0L,
		intervalEndExclusiveElapsedRealtimeNanos = 1_000L,
		observations = observations,
		declaredGaps = declaredGaps,
	)

	private fun transition(
		id: String,
		time: Long,
		activity: CapturedActivityType,
		change: ActivityTransitionChange = ActivityTransitionChange.ENTER,
	) = ActivityCapturedObservation.Transition(
		reference = reference(id, time),
		authority = authority,
		activity = activity,
		observedWallTimeMs = time + 10_000L,
		wallTimeUncertaintyMs = 5L,
		change = change,
	)

	private fun sampled(
		id: String,
		time: Long,
		confidence: Int,
		coverageEnd: Long,
		activity: CapturedActivityType,
	) = ActivityCapturedObservation.SampledClassification(
		reference = reference(id, time),
		authority = authority,
		activity = activity,
		observedWallTimeMs = time + 10_000L,
		wallTimeUncertaintyMs = 5L,
		confidencePercent = confidence,
		coverageEndExclusiveElapsedRealtimeNanos = coverageEnd,
	)

	private fun band(
		start: Long,
		end: Long,
		activity: CapturedActivityType,
		mechanism: ActivityBandMechanism,
		id: String,
		confidence: ActivityBandConfidence = ActivityBandConfidence.TransitionSignal,
	) = ActivityCapturedBand(
		key = ActivityCapturedFactKey(authority, start, end),
		activity = activity,
		mechanism = mechanism,
		confidence = confidence,
		evidence = listOf(reference(id, start)),
	)

	private fun reference(id: String, time: Long) = ActivityCapturedObservationReference(
		sourceEventId = SourceEventId(id),
		admissionOrdinal = time + 1L,
		sourceSequence = time + 1L,
		providerElapsedRealtimeNanos = time,
		receivedElapsedRealtimeNanos = time + 10L,
	)

	private val authority = ActivityCaptureAuthority(
		logicalTrackingId = LogicalTrackingId("tracking-1"),
		serviceRunId = ServiceRunId("run-1"),
		sourceInstanceId = SourceInstanceId("activity-provider"),
		registrationGeneration = 3L,
		physicalConfigurationFingerprint = "activity-physical",
		authorizationRevision = 7L,
		authorizationFingerprint = "activity-eligibility",
		purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		sourcePolicyRevision = 11L,
		captureConsentEpoch = 13L,
		sessionManifestRevision = 17L,
		lifecycleLeaseGeneration = 19L,
		collectedDataEpoch = 23L,
		clockDomainId = "boot-1",
	)
}
