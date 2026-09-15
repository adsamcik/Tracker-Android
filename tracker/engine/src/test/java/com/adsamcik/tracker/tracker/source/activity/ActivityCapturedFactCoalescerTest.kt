package com.adsamcik.tracker.tracker.source.activity

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import org.junit.Test

class ActivityCapturedFactCoalescerTest {
	@Test
	fun `semantic mutation must name its exact predecessor`() {
		shouldThrow<IllegalArgumentException> {
			mutation(semanticRevision = 3L, supersedes = 1L)
		}
	}

	@Test
	fun `transition bands expose active inactive and unobserved time without fabricating coverage`() {
		val window = coalesced(
			transition("walk-enter", 100L, CapturedActivityType.WALKING),
			transition("walk-exit", 400L, CapturedActivityType.WALKING, ActivityTransitionChange.EXIT),
			transition("still-enter", 500L, CapturedActivityType.STILL),
		)

		window.bands.map { Triple(it.intervalStartElapsedRealtimeNanos,
			it.intervalEndExclusiveElapsedRealtimeNanos, it.activity) } shouldContainExactly listOf(
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
		window.bands.first().wallTimeRange.startInclusive.authority.kind shouldBe
			ActivityWallTimeBoundaryKind.EXACT_PROVIDER_OBSERVATION
		window.bands.first().wallTimeRange.endExclusive.authority.kind shouldBe
			ActivityWallTimeBoundaryKind.EXACT_PROVIDER_OBSERVATION
		window.bands.first().wallTimeRange.continuity shouldBe
			ActivityWallTimeContinuity.DISCONTINUITY_DETECTED
	}

	@Test
	fun `transition coverage wins over overlapping sampled classification`() {
		val window = coalesced(
			transition("walking", 100L, CapturedActivityType.WALKING),
			sampled("vehicle", 200L, 90, 700L, CapturedActivityType.IN_VEHICLE),
		)

		window.bands.single().let { band ->
			band.intervalStartElapsedRealtimeNanos shouldBe 100L
			band.intervalEndExclusiveElapsedRealtimeNanos shouldBe 1_000L
			band.activity shouldBe CapturedActivityType.WALKING
			band.mechanism shouldBe ActivityBandMechanism.TRANSITION
			band.evidence shouldContainExactly listOf(
				transition("walking", 100L, CapturedActivityType.WALKING).reference,
			)
			band.wallTimeRange.endExclusive.authority.kind shouldBe
				ActivityWallTimeBoundaryKind.SAME_CLOCK_EXTRAPOLATION
			band.wallTimeRange.endExclusive.uncertaintyMs shouldBe 6L
			band.wallTimeRange.continuity shouldBe ActivityWallTimeContinuity.SAME_ANCHOR
		}
		window.unchangedEvidenceCount shouldBe 1
	}

	@Test
	fun `direct high confidence sample refines only a compatible coarse transition`() {
		val window = coalesced(
			transition("on-foot", 100L, CapturedActivityType.ON_FOOT),
			sampled("walking", 200L, 90, 400L, CapturedActivityType.WALKING),
		)

		window.bands.map { band ->
			Triple(
				band.intervalStartElapsedRealtimeNanos to
					band.intervalEndExclusiveElapsedRealtimeNanos,
				band.activity,
				band.mechanism,
			)
		} shouldContainExactly listOf(
			Triple(100L to 200L, CapturedActivityType.ON_FOOT, ActivityBandMechanism.TRANSITION),
			Triple(
				200L to 400L,
				CapturedActivityType.WALKING,
				ActivityBandMechanism.SAMPLED_REFINEMENT,
			),
			Triple(400L to 1_000L, CapturedActivityType.ON_FOOT,
				ActivityBandMechanism.TRANSITION),
		)
		window.bands[1].confidence shouldBe ActivityBandConfidence.Sampled(90, 90, 1)
		window.bands[1].refinedTransitionActivity shouldBe CapturedActivityType.ON_FOOT
		window.bands[1].evidence shouldContainExactly listOf(
			transition("on-foot", 100L, CapturedActivityType.ON_FOOT).reference,
			sampled("walking", 200L, 90, 400L, CapturedActivityType.WALKING).reference,
		)
	}

	@Test
	fun `sample never contradicts a definitive transition`() {
		val window = coalesced(
			transition("still", 100L, CapturedActivityType.STILL),
			sampled("walking", 200L, 100, 800L, CapturedActivityType.WALKING),
		)

		window.bands.single().activity shouldBe CapturedActivityType.STILL
		window.bands.single().mechanism shouldBe ActivityBandMechanism.TRANSITION
		window.unchangedEvidenceCount shouldBe 1
	}

	@Test
	fun `unknown transition may be refined by known direct detail`() {
		val window = coalesced(
			transition("unknown", 100L, CapturedActivityType.UNKNOWN),
			sampled("walking", 200L, 90, 400L, CapturedActivityType.WALKING),
		)

		window.bands.single {
			it.mechanism == ActivityBandMechanism.SAMPLED_REFINEMENT
		}.refinedTransitionActivity shouldBe CapturedActivityType.UNKNOWN
	}

	@Test
	fun `unknown exit does not negate known sampled activity`() {
		val window = coalesced(
			sampled("walking", 100L, 90, 900L, CapturedActivityType.WALKING),
			transition(
				"unknown-exit",
				400L,
				CapturedActivityType.UNKNOWN,
				ActivityTransitionChange.EXIT,
			),
		)

		window.bands.single().intervalStartElapsedRealtimeNanos shouldBe 100L
		window.bands.single().intervalEndExclusiveElapsedRealtimeNanos shouldBe 900L
		window.bands.single().evidence shouldContainExactly listOf(
			sampled("walking", 100L, 90, 900L, CapturedActivityType.WALKING).reference,
		)
		window.unchangedEvidenceCount shouldBe 1
	}

	@Test
	fun `unknown exit clips older unknown sampled activity`() {
		val window = coalesced(
			sampled("unknown", 100L, 90, 900L, CapturedActivityType.UNKNOWN),
			transition(
				"unknown-exit",
				400L,
				CapturedActivityType.UNKNOWN,
				ActivityTransitionChange.EXIT,
			),
		)

		window.bands.single().intervalStartElapsedRealtimeNanos shouldBe 100L
		window.bands.single().intervalEndExclusiveElapsedRealtimeNanos shouldBe 400L
		window.bands.single().evidence shouldContainExactly listOf(
			sampled("unknown", 100L, 90, 900L, CapturedActivityType.UNKNOWN).reference,
			transition("unknown-exit", 400L, CapturedActivityType.UNKNOWN,
				ActivityTransitionChange.EXIT).reference,
		)
		window.gaps.last() shouldBe ActivityCoverageGap(
			400L,
			1_000L,
			ActivityCoverageGapReason.NO_QUALIFIED_EVIDENCE,
		)
		window.unchangedEvidenceCount shouldBe 0
	}

	@Test
	fun `unmatched exit is a negative boundary for older compatible sample coverage`() {
		val window = coalesced(
			sampled("walking", 100L, 90, 900L, CapturedActivityType.WALKING),
			transition(
				"walking-exit",
				400L,
				CapturedActivityType.WALKING,
				ActivityTransitionChange.EXIT,
			),
		)

		window.bands.single().intervalStartElapsedRealtimeNanos shouldBe 100L
		window.bands.single().intervalEndExclusiveElapsedRealtimeNanos shouldBe 400L
		window.bands.single().evidence shouldContainExactly listOf(
			sampled("walking", 100L, 90, 900L, CapturedActivityType.WALKING).reference,
			transition("walking-exit", 400L, CapturedActivityType.WALKING,
				ActivityTransitionChange.EXIT).reference,
		)
		window.gaps.last() shouldBe ActivityCoverageGap(
			400L,
			1_000L,
			ActivityCoverageGapReason.NO_QUALIFIED_EVIDENCE,
		)
		window.unchangedEvidenceCount shouldBe 0
	}

	@Test
	fun `equal-time exit wins when source sequence does not prove the sample came later`() {
		val window = coalesced(
			sampled("old-walking", 100L, 90, 900L, CapturedActivityType.WALKING),
			transition(
				"on-foot-exit",
				400L,
				CapturedActivityType.ON_FOOT,
				ActivityTransitionChange.EXIT,
			),
			sampled("new-walking", 400L, 90, 700L, CapturedActivityType.WALKING),
		)

		window.bands.single().intervalStartElapsedRealtimeNanos shouldBe 100L
		window.bands.single().intervalEndExclusiveElapsedRealtimeNanos shouldBe 400L
		window.bands.single().evidence shouldContainExactly listOf(
			sampled("old-walking", 100L, 90, 900L, CapturedActivityType.WALKING).reference,
			transition("on-foot-exit", 400L, CapturedActivityType.ON_FOOT,
				ActivityTransitionChange.EXIT).reference,
		)
		window.unchangedEvidenceCount shouldBe 1
	}

	@Test
	fun `equal-time sample survives only when source sequence proves it followed exit`() {
		val window = coalesced(
			sampled("old-walking", 100L, 90, 900L, CapturedActivityType.WALKING),
			transition(
				"on-foot-exit",
				400L,
				CapturedActivityType.ON_FOOT,
				ActivityTransitionChange.EXIT,
				sourceSequence = 401L,
			),
			sampled(
				"new-walking",
				400L,
				90,
				700L,
				CapturedActivityType.WALKING,
				sourceSequence = 402L,
			),
		)

		window.bands.single().intervalStartElapsedRealtimeNanos shouldBe 100L
		window.bands.single().intervalEndExclusiveElapsedRealtimeNanos shouldBe 700L
		window.bands.single().evidence shouldContainExactly listOf(
			sampled("old-walking", 100L, 90, 900L, CapturedActivityType.WALKING).reference,
			transition("on-foot-exit", 400L, CapturedActivityType.ON_FOOT,
				ActivityTransitionChange.EXIT, sourceSequence = 401L).reference,
			sampled("new-walking", 400L, 90, 700L, CapturedActivityType.WALKING,
				sourceSequence = 402L).reference,
		)
	}

	@Test
	fun `sampled detail fills only its explicit validity interval`() {
		val window = coalesced(
			sampled("running", 200L, 80, 450L, CapturedActivityType.RUNNING),
		)

		window.bands.single().let { band ->
			band.intervalStartElapsedRealtimeNanos shouldBe 200L
			band.intervalEndExclusiveElapsedRealtimeNanos shouldBe 450L
			band.activity shouldBe CapturedActivityType.RUNNING
			band.mechanism shouldBe ActivityBandMechanism.SAMPLED_CLASSIFICATION
			band.confidence shouldBe ActivityBandConfidence.Sampled(80, 80, 1)
			band.evidence shouldContainExactly listOf(
				sampled("running", 200L, 80, 450L, CapturedActivityType.RUNNING).reference,
			)
		}
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

		window.bands.single().let { band ->
			band.key shouldBe ActivityCapturedFactKey(mutation(), 0)
			band.intervalStartElapsedRealtimeNanos shouldBe 100L
			band.intervalEndExclusiveElapsedRealtimeNanos shouldBe 600L
			band.activity shouldBe CapturedActivityType.WALKING
			band.mechanism shouldBe ActivityBandMechanism.SAMPLED_CLASSIFICATION
			band.confidence shouldBe ActivityBandConfidence.Sampled(80, 90, 2)
			band.evidence shouldContainExactly listOf(
				sampled("walking-1", 100L, 80, 300L,
					CapturedActivityType.WALKING).reference,
				sampled("walking-2", 300L, 90, 600L,
					CapturedActivityType.WALKING).reference,
			)
		}
	}

	@Test
	fun `exact semantic and unchanged observations are suppressed independently`() {
		val enter = transition("enter", 100L, CapturedActivityType.WALKING)
		val sameMeaning = enter.copy(
			reference = transition("same-meaning", 100L, CapturedActivityType.WALKING).reference,
		)
		val duplicateEnter = transition("duplicate-enter", 200L, CapturedActivityType.WALKING)
		val result = ActivityCapturedFactCoalescer.coalesce(
			request(observations = listOf(enter, enter, sameMeaning, duplicateEnter)),
		)
		val window = (result as ActivityCoalescingResult.Coalesced).window

		window.exactDuplicateCount shouldBe 1
		window.semanticDuplicateCount shouldBe 1
		window.unchangedEvidenceCount shouldBe 1
		window.bands.single().evidence shouldContainExactly listOf(
			transition("enter", 100L, CapturedActivityType.WALKING).reference,
		)
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

		window.bands.single().let { band ->
			band.intervalStartElapsedRealtimeNanos shouldBe 100L
			band.intervalEndExclusiveElapsedRealtimeNanos shouldBe 400L
			band.activity shouldBe CapturedActivityType.WALKING
			band.mechanism shouldBe ActivityBandMechanism.TRANSITION
			band.evidence shouldContainExactly listOf(
				transition("walking", 100L, CapturedActivityType.WALKING).reference,
			)
		}
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

	@Test
	fun `coalescing window cannot extend a transition beyond the exact capture cutoff`() {
		val restricted = authority.withCaptureValidity(0L, 400L)
		val observation = transition("walking", 100L, CapturedActivityType.WALKING).copy(
			authority = restricted,
		)
		val outside = ActivityCapturedFactCoalescer.coalesce(
			requestFor(restricted, 0L, 401L, listOf(observation)),
		)
		val exact = ActivityCapturedFactCoalescer.coalesce(
			requestFor(restricted, 0L, 400L, listOf(observation)),
		) as ActivityCoalescingResult.Coalesced

		outside shouldBe ActivityCoalescingResult.Rejected(
			ActivityCoalescingRejection.WINDOW_OUTSIDE_CAPTURE_VALIDITY,
		)
		exact.window.bands.single().intervalEndExclusiveElapsedRealtimeNanos shouldBe 400L
	}

	@Test
	fun `coalescing window must stay inside the intersection of every temporal limit`() {
		val restricted = authority.copy(
			temporalAuthority = ActivityCaptureTemporalAuthority(
				providerAcceptance = ActivityProviderTimeInterval(50L, 900L),
				authorizationEffect = ActivityProviderTimeInterval(100L, 800L),
				sessionRunEffect = ActivityProviderTimeInterval(150L, 850L),
			),
		)
		val observation = transition("walking", 200L, CapturedActivityType.WALKING).copy(
			authority = restricted,
		)

		ActivityCapturedFactCoalescer.coalesce(
			requestFor(restricted, 149L, 800L, listOf(observation)),
		) shouldBe ActivityCoalescingResult.Rejected(
			ActivityCoalescingRejection.WINDOW_OUTSIDE_CAPTURE_VALIDITY,
		)
		val exact = ActivityCapturedFactCoalescer.coalesce(
			requestFor(restricted, 150L, 800L, listOf(observation)),
		) as ActivityCoalescingResult.Coalesced
		exact.window.authority.temporalAuthority.capturedIntersection shouldBe
			ActivityProviderTimeInterval(150L, 800L)
	}

	@Test
	fun `lookback observation must remain inside the same exact capture validity`() {
		val restricted = authority.withCaptureValidity(100L, 500L)
		val observation = transition("pre-authority", 50L, CapturedActivityType.WALKING).copy(
			authority = restricted,
		)

		ActivityCapturedFactCoalescer.coalesce(
			requestFor(restricted, 100L, 400L, listOf(observation)),
		) shouldBe ActivityCoalescingResult.Rejected(
			ActivityCoalescingRejection.OBSERVATION_OUTSIDE_CAPTURE_VALIDITY,
		)
	}

	@Test
	fun `late correction replaces one stable window with a new semantic revision`() {
		val firstMutation = mutation()
		val correctedMutation = mutation(semanticRevision = 2L, supersedes = 1L)
		val initial = ActivityCapturedFactCoalescer.coalesce(
			request(
				observations = listOf(transition("walking", 100L, CapturedActivityType.WALKING)),
				mutation = firstMutation,
			),
		) as ActivityCoalescingResult.Coalesced
		val corrected = ActivityCapturedFactCoalescer.coalesce(
			request(
				observations = listOf(
					transition("walking", 100L, CapturedActivityType.WALKING),
					transition("late-exit", 400L, CapturedActivityType.WALKING,
						ActivityTransitionChange.EXIT),
				),
				mutation = correctedMutation,
			),
		) as ActivityCoalescingResult.Coalesced

		initial.window.mutation.identity shouldBe corrected.window.mutation.identity
		initial.window.bands.single().key.fragmentOrdinal shouldBe 0
		corrected.window.bands.single().key.fragmentOrdinal shouldBe 0
		corrected.window.mutation.supersedesSemanticRevision shouldBe 1L
		initial.window.bands.single().intervalEndExclusiveElapsedRealtimeNanos shouldBe 1_000L
		corrected.window.bands.single().intervalEndExclusiveElapsedRealtimeNanos shouldBe 400L
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
		mutation: ActivityCapturedWindowMutation = mutation(),
	) = ActivityCapturedCoalescingRequest(
		mutation = mutation,
		observations = observations,
		declaredGaps = declaredGaps,
	)

	private fun requestFor(
		captureAuthority: ActivityCaptureAuthority,
		start: Long,
		end: Long,
		observations: List<ActivityCapturedObservation>,
	) = ActivityCapturedCoalescingRequest(
		mutation = ActivityCapturedWindowMutation(
			identity = ActivityCapturedWindowIdentity(captureAuthority, start, end),
			semanticRevision = 1L,
			supersedesSemanticRevision = null,
		),
		observations = observations,
	)

	private fun ActivityCaptureAuthority.withCaptureValidity(
		start: Long,
		end: Long,
	) = copy(
		temporalAuthority = ActivityCaptureTemporalAuthority(
			providerAcceptance = ActivityProviderTimeInterval(start, end),
			authorizationEffect = ActivityProviderTimeInterval(start, end),
			sessionRunEffect = ActivityProviderTimeInterval(start, end),
		),
	)

	private fun mutation(
		semanticRevision: Long = 1L,
		supersedes: Long? = null,
	) = ActivityCapturedWindowMutation(
		identity = ActivityCapturedWindowIdentity(authority, 0L, 1_000L),
		semanticRevision = semanticRevision,
		supersedesSemanticRevision = supersedes,
	)

	private fun transition(
		id: String,
		time: Long,
		activity: CapturedActivityType,
		change: ActivityTransitionChange = ActivityTransitionChange.ENTER,
		sourceSequence: Long = time + 1L,
	) = ActivityCapturedObservation.Transition(
		reference = transitionReference(id, time, activity, change, sourceSequence),
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
		sourceSequence: Long = time + 1L,
	) = ActivityCapturedObservation.SampledClassification(
		reference = sampledReference(id, time, confidence, coverageEnd, activity, sourceSequence),
		authority = authority,
		activity = activity,
		observedWallTimeMs = time + 10_000L,
		wallTimeUncertaintyMs = 5L,
		confidencePercent = confidence,
		coverageEndExclusiveElapsedRealtimeNanos = coverageEnd,
	)

	private fun transitionReference(
		id: String,
		time: Long,
		activity: CapturedActivityType,
		change: ActivityTransitionChange = ActivityTransitionChange.ENTER,
		sourceSequence: Long = time + 1L,
	) = ActivityCapturedObservationReference(
		sourceEventId = SourceEventId(id),
		admissionOrdinal = time + 1L,
		sourceSequence = sourceSequence,
		providerElapsedRealtimeNanos = time,
		receivedElapsedRealtimeNanos = time + 10L,
		observationKind = ActivityCapturedObservationKind.TRANSITION,
		observedActivity = activity,
		transitionChange = change,
		confidencePercent = null,
		coverageEndExclusiveElapsedRealtimeNanos = null,
	)

	private fun sampledReference(
		id: String,
		time: Long,
		confidence: Int,
		coverageEnd: Long,
		activity: CapturedActivityType,
		sourceSequence: Long = time + 1L,
	) = ActivityCapturedObservationReference(
		sourceEventId = SourceEventId(id),
		admissionOrdinal = time + 1L,
		sourceSequence = sourceSequence,
		providerElapsedRealtimeNanos = time,
		receivedElapsedRealtimeNanos = time + 10L,
		observationKind = ActivityCapturedObservationKind.SAMPLED_CLASSIFICATION,
		observedActivity = activity,
		transitionChange = null,
		confidencePercent = confidence,
		coverageEndExclusiveElapsedRealtimeNanos = coverageEnd,
	)

	private val authority = ActivityCaptureAuthority(
		logicalTrackingId = LogicalTrackingId("tracking-1"),
		serviceRunId = ServiceRunId("run-1"),
		sourceInstanceId = SourceInstanceId("activity-provider"),
		registrationGeneration = 3L,
		configurationRevision = 5L,
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
		temporalAuthority = ActivityCaptureTemporalAuthority(
			providerAcceptance = ActivityProviderTimeInterval(0L, 10_000L),
			authorizationEffect = ActivityProviderTimeInterval(0L, 10_000L),
			sessionRunEffect = ActivityProviderTimeInterval(0L, 10_000L),
		),
	)
}
