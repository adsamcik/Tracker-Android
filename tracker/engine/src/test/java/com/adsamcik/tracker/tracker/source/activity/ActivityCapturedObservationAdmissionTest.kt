package com.adsamcik.tracker.tracker.source.activity

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.StableActivityTypeCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test

class ActivityCapturedObservationAdmissionTest {
	@Test
	fun `control-only callback is rejected from captured history`() {
		val result = ActivityCapturedObservationAdmission.admit(
			event(purposeMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART),
			acquisitionAuthority(),
		)

		result shouldBe rejected(ActivityCaptureAdmissionRejection.CONTROL_ONLY)
	}

	@Test
	fun `capture authorization remains valid when the registration also serves control`() {
		val mask = SourceBrokerPurpose.MASK_SESSION_CAPTURE or
			SourceBrokerPurpose.MASK_CONTROL_AUTOSTART
		val result = ActivityCapturedObservationAdmission.admit(
			event(purposeMask = mask),
			acquisitionAuthority(authority(purposeMask = mask)),
		)

		val captured = result as ActivityCaptureAdmissionResult.Captured
		captured.observation.authority.purposeEligibilityMask shouldBe mask
	}

	@Test
	fun `exact authorization mismatch is rejected`() {
		val result = ActivityCapturedObservationAdmission.admit(
			event(authorizationRevision = 8L),
			acquisitionAuthority(),
		)

		result shouldBe rejected(ActivityCaptureAdmissionRejection.ACQUISITION_AUTHORITY_MISMATCH)
	}

	@Test
	fun `fresh still transition is retained but stale still is rejected`() {
		val fresh = ActivityCapturedObservationAdmission.admit(
			event(
				payload = transition(StableActivityTypeCode.STILL),
				providerTime = 1_000L,
				receivedTime = 1_100L,
			),
			acquisitionAuthority(maximumObservationAgeNanos = 100L),
		)
		val stale = ActivityCapturedObservationAdmission.admit(
			event(
				payload = transition(StableActivityTypeCode.STILL),
				providerTime = 1_000L,
				receivedTime = 1_101L,
			),
			acquisitionAuthority(maximumObservationAgeNanos = 100L),
		)

		(fresh as ActivityCaptureAdmissionResult.Captured).observation.activity shouldBe
			CapturedActivityType.STILL
		stale shouldBe rejected(ActivityCaptureAdmissionRejection.STALE_PROVIDER_TIME)
	}

	@Test
	fun `transition capture does not require sampled-classification authority`() {
		val result = ActivityCapturedObservationAdmission.admit(
			event(payload = transition(StableActivityTypeCode.WALKING)),
			acquisitionAuthority(
				sampledPolicy = ActivitySampledClassificationPolicy.NotDirectlyRequested,
			),
		)

		(result as ActivityCaptureAdmissionResult.Captured).observation shouldBe
			ActivityCapturedObservation.Transition(
				reference = reference(),
				authority = authority(),
				activity = CapturedActivityType.WALKING,
				observedWallTimeMs = 2_000L,
				wallTimeUncertaintyMs = 5L,
				change = ActivityTransitionChange.ENTER,
			)
	}

	@Test
	fun `sampled classification requires explicit direct capture detail`() {
		val result = ActivityCapturedObservationAdmission.admit(
			event(payload = sampled(confidence = 90)),
			acquisitionAuthority(
				sampledPolicy = ActivitySampledClassificationPolicy.NotDirectlyRequested,
			),
		)

		result shouldBe rejected(
			ActivityCaptureAdmissionRejection.SAMPLED_CLASSIFICATION_NOT_DIRECTLY_REQUESTED,
		)
	}

	@Test
	fun `direct sampled detail retains its exact confidence and bounded coverage`() {
		val result = ActivityCapturedObservationAdmission.admit(
			event(payload = sampled(confidence = 80)),
			acquisitionAuthority(
				sampledPolicy = ActivitySampledClassificationPolicy.DirectCaptureDetail(
					minimumConfidencePercent = 75,
					maximumCoverageAfterObservationNanos = 500L,
				),
			),
		)

		val observation = (result as ActivityCaptureAdmissionResult.Captured).observation as
			ActivityCapturedObservation.SampledClassification
		observation.confidencePercent shouldBe 80
		observation.coverageEndExclusiveElapsedRealtimeNanos shouldBe 1_500L
	}

	@Test
	fun `sampled detail below the direct threshold is rejected`() {
		val result = ActivityCapturedObservationAdmission.admit(
			event(payload = sampled(confidence = 74)),
			acquisitionAuthority(
				sampledPolicy = ActivitySampledClassificationPolicy.DirectCaptureDetail(
					minimumConfidencePercent = 75,
					maximumCoverageAfterObservationNanos = 500L,
				),
			),
		)

		result shouldBe rejected(
			ActivityCaptureAdmissionRejection.SAMPLED_CLASSIFICATION_BELOW_THRESHOLD,
		)
	}

	@Test
	fun `provider time must equal the durable observation identity`() {
		val result = ActivityCapturedObservationAdmission.admit(
			event(
				payload = transition(
					activity = StableActivityTypeCode.WALKING,
					providerTime = 999L,
				),
				providerTime = 1_000L,
			),
			acquisitionAuthority(),
		)

		result shouldBe rejected(ActivityCaptureAdmissionRejection.MALFORMED_PROVIDER_TIME)
	}

	@Test
	fun `provider acceptance is exact and end exclusive`() {
		val acceptedInterval = ActivityProviderTimeInterval(1_000L, 1_001L)
		val accepted = ActivityCapturedObservationAdmission.admit(
			event(providerTime = 1_000L),
			acquisitionAuthority(
				providerAcceptance = acceptedInterval,
			),
		)
		val rejected = ActivityCapturedObservationAdmission.admit(
			event(providerTime = 1_000L),
			acquisitionAuthority(
				providerAcceptance = ActivityProviderTimeInterval(999L, 1_000L),
			),
		)

		val captured = accepted as ActivityCaptureAdmissionResult.Captured
		captured.observation.authority.temporalAuthority.providerAcceptance shouldBe
			acceptedInterval
		rejected shouldBe rejected(ActivityCaptureAdmissionRejection.OUTSIDE_PROVIDER_ACCEPTANCE)
	}

	@Test
	fun `captured observation retains each exact temporal limit and their intersection`() {
		val temporalAuthority = ActivityCaptureTemporalAuthority(
			providerAcceptance = ActivityProviderTimeInterval(900L, 1_200L),
			authorizationEffect = ActivityProviderTimeInterval(950L, 1_100L),
			sessionRunEffect = ActivityProviderTimeInterval(980L, 1_050L),
		)
		val result = ActivityCapturedObservationAdmission.admit(
			event(providerTime = 1_000L),
			acquisitionAuthority(
				providerAcceptance = temporalAuthority.providerAcceptance,
				authorizationEffect = temporalAuthority.authorizationEffect,
				sessionRunEffect = temporalAuthority.sessionRunEffect,
			),
		)

		val captured = result as ActivityCaptureAdmissionResult.Captured
		captured.observation.authority.temporalAuthority shouldBe temporalAuthority
		captured.observation.authority.temporalAuthority.capturedIntersection shouldBe
			ActivityProviderTimeInterval(980L, 1_050L)
	}

	@Test
	fun `authorization retirement is exact and end exclusive`() {
		val result = ActivityCapturedObservationAdmission.admit(
			event(providerTime = 1_000L),
			acquisitionAuthority(
				authorizationEffect = ActivityProviderTimeInterval(900L, 1_000L),
			),
		)

		result shouldBe rejected(ActivityCaptureAdmissionRejection.OUTSIDE_AUTHORIZATION_EFFECT)
	}

	@Test
	fun `session run cutoff is exact and end exclusive`() {
		val result = ActivityCapturedObservationAdmission.admit(
			event(providerTime = 1_000L),
			acquisitionAuthority(
				sessionRunEffect = ActivityProviderTimeInterval(900L, 1_000L),
			),
		)

		result shouldBe rejected(ActivityCaptureAdmissionRejection.OUTSIDE_SESSION_RUN_EFFECT)
	}

	@Test
	fun `sampled validity is clipped to the earliest exact acquisition cutoff`() {
		val result = ActivityCapturedObservationAdmission.admit(
			event(
				payload = ActivityRecognitionPayload(
					activityType = StableActivityTypeCode.WALKING,
					confidencePercent = 90,
					providerElapsedRealtimeNanos = 1_000L,
				),
			),
			acquisitionAuthority(
				sampledPolicy = ActivitySampledClassificationPolicy.DirectCaptureDetail(75, 500L),
				sessionRunEffect = ActivityProviderTimeInterval(0L, 1_100L),
			),
		)

		val observation = (result as ActivityCaptureAdmissionResult.Captured).observation as
			ActivityCapturedObservation.SampledClassification
		observation.coverageEndExclusiveElapsedRealtimeNanos shouldBe 1_100L
	}

	@Test
	fun `thresholds cannot be attached to a different historical configuration identity`() {
		val captureAuthority = authority()

		shouldThrow<IllegalArgumentException> {
			ActivityCaptureAcquisitionAuthority(
				captureAuthority = captureAuthority,
				historicalConfiguration = ActivityHistoricalAcquisitionConfiguration(
					identity = ActivityAcquisitionConfigurationIdentity(
						sourceInstanceId = captureAuthority.sourceInstanceId,
						registrationGeneration = captureAuthority.registrationGeneration,
						configurationRevision = captureAuthority.configurationRevision + 1L,
						physicalConfigurationFingerprint =
							captureAuthority.physicalConfigurationFingerprint,
						authorizationRevision = captureAuthority.authorizationRevision,
						authorizationFingerprint = captureAuthority.authorizationFingerprint,
					),
					providerAcceptance = ActivityProviderTimeInterval(0L, 10_000L),
					authorizationEffect = ActivityProviderTimeInterval(0L, 10_000L),
					sessionRunEffect = ActivityProviderTimeInterval(0L, 10_000L),
					maximumObservationAgeNanos = 100L,
					sampledClassificationPolicy =
						ActivitySampledClassificationPolicy.NotDirectlyRequested,
				),
			)
		}
	}

	private fun acquisitionAuthority(
		captureAuthority: ActivityCaptureAuthority = authority(),
		maximumObservationAgeNanos: Long = 100L,
		sampledPolicy: ActivitySampledClassificationPolicy =
			ActivitySampledClassificationPolicy.NotDirectlyRequested,
		providerAcceptance: ActivityProviderTimeInterval =
			ActivityProviderTimeInterval(0L, 10_000L),
		authorizationEffect: ActivityProviderTimeInterval =
			ActivityProviderTimeInterval(0L, 10_000L),
		sessionRunEffect: ActivityProviderTimeInterval =
			ActivityProviderTimeInterval(0L, 10_000L),
	): ActivityCaptureAcquisitionAuthority {
		val exactCaptureAuthority = captureAuthority.copy(
			temporalAuthority = ActivityCaptureTemporalAuthority(
				providerAcceptance = providerAcceptance,
				authorizationEffect = authorizationEffect,
				sessionRunEffect = sessionRunEffect,
			),
		)
		return ActivityCaptureAcquisitionAuthority(
			captureAuthority = exactCaptureAuthority,
			historicalConfiguration = ActivityHistoricalAcquisitionConfiguration(
				identity = ActivityAcquisitionConfigurationIdentity(
					sourceInstanceId = exactCaptureAuthority.sourceInstanceId,
					registrationGeneration = exactCaptureAuthority.registrationGeneration,
					configurationRevision = exactCaptureAuthority.configurationRevision,
					physicalConfigurationFingerprint =
						exactCaptureAuthority.physicalConfigurationFingerprint,
					authorizationRevision = exactCaptureAuthority.authorizationRevision,
					authorizationFingerprint = exactCaptureAuthority.authorizationFingerprint,
				),
				providerAcceptance = providerAcceptance,
				authorizationEffect = authorizationEffect,
				sessionRunEffect = sessionRunEffect,
				maximumObservationAgeNanos = maximumObservationAgeNanos,
				sampledClassificationPolicy = sampledPolicy,
			),
		)
	}

	private fun authority(
		purposeMask: Long = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
	) = ActivityCaptureAuthority(
		logicalTrackingId = LogicalTrackingId("tracking-1"),
		serviceRunId = ServiceRunId("run-1"),
		sourceInstanceId = SourceInstanceId("activity-provider"),
		registrationGeneration = 3L,
		configurationRevision = 5L,
		physicalConfigurationFingerprint = "activity-physical",
		authorizationRevision = 7L,
		authorizationFingerprint = "activity-eligibility",
		purposeEligibilityMask = purposeMask,
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

	private fun event(
		payload: SourcePayload = transition(StableActivityTypeCode.WALKING),
		purposeMask: Long = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		authorizationRevision: Long = 7L,
		providerTime: Long = 1_000L,
		receivedTime: Long = 1_100L,
	) = AdmittedSourceEvent(
		eventId = SourceEventId("activity-event-1"),
		admissionOrdinal = 29L,
		evidence = SourceEvidenceCandidate(
			providerDedupKey = "activity-event-1",
			logicalTrackingId = LogicalTrackingId("tracking-1"),
			serviceRunId = ServiceRunId("run-1"),
			source = SourceKind.ACTIVITY,
			sourceInstanceId = SourceInstanceId("activity-provider"),
			registrationGeneration = 3L,
			physicalConfigurationFingerprint = "activity-physical",
			authorizationRevision = authorizationRevision,
			registrationPurposeEligibilityMask = purposeMask,
			registrationEligibilityFingerprint = "activity-eligibility",
			sourceSequence = 31L,
			configRevision = 5L,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = "boot-1",
			observedElapsedRealtimeNanos = providerTime,
			receivedElapsedRealtimeNanos = receivedTime,
			wallTimeMs = 2_000L,
			wallTimeUncertaintyMs = 5L,
			capturedCollectedDataEpoch = 23L,
			sourcePolicyRevision = 11L,
			captureConsentEpoch = 13L,
			sessionManifestRevision = 17L,
			lifecycleLeaseGeneration = 19L,
			acquiredAtMs = 2_000L,
			quality = SourceQuality(),
			payloadVersion = 1,
			payload = payload,
		),
	)

	private fun transition(
		activity: Int,
		providerTime: Long = 1_000L,
	) = ActivityTransitionPayload(
		activityType = activity,
		transitionType = 0,
		providerElapsedRealtimeNanos = providerTime,
	)

	private fun sampled(confidence: Int) = ActivityRecognitionPayload(
		activityType = StableActivityTypeCode.RUNNING,
		confidencePercent = confidence,
		providerElapsedRealtimeNanos = 1_000L,
	)

	private fun reference() = ActivityCapturedObservationReference(
		sourceEventId = SourceEventId("activity-event-1"),
		admissionOrdinal = 29L,
		sourceSequence = 31L,
		providerElapsedRealtimeNanos = 1_000L,
		receivedElapsedRealtimeNanos = 1_100L,
	)

	private fun rejected(reason: ActivityCaptureAdmissionRejection) =
		ActivityCaptureAdmissionResult.Rejected(reason)
}
