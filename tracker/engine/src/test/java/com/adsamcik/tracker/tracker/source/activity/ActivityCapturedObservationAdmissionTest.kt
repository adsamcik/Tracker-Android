package com.adsamcik.tracker.tracker.source.activity

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
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
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test

private val TRANSITION_ACTIVITY_PLAN = ActivityPlan(
	revision = 5L,
	mode = ActivityMode.TRANSITIONS_ONLY,
	desiredDetectionLatencyMs = 1L,
	confidenceThresholdPercent = 75,
	transitionTypes = setOf(0, 1),
)
private val SAMPLED_ACTIVITY_PLAN = TRANSITION_ACTIVITY_PLAN.copy(
	mode = ActivityMode.CONTINUOUS_RECOGNITION,
)
private val TRANSITION_PLAN_FINGERPRINT =
	TRANSITION_ACTIVITY_PLAN.physicalConfigurationFingerprint()
private val SAMPLED_PLAN_FINGERPRINT = SAMPLED_ACTIVITY_PLAN.physicalConfigurationFingerprint()

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
				receivedTime = 1_001_000L,
			),
			acquisitionAuthority(),
		)
		val stale = ActivityCapturedObservationAdmission.admit(
			event(
				payload = transition(StableActivityTypeCode.STILL),
				providerTime = 1_000L,
				receivedTime = 1_001_001L,
			),
			acquisitionAuthority(),
		)

		(fresh as ActivityCaptureAdmissionResult.Captured).observation.activity shouldBe
			CapturedActivityType.STILL
		stale shouldBe rejected(ActivityCaptureAdmissionRejection.STALE_PROVIDER_TIME)
	}

	@Test
	fun `transition capture does not require sampled-classification authority`() {
		val result = ActivityCapturedObservationAdmission.admit(
			event(payload = transition(StableActivityTypeCode.WALKING)),
			acquisitionAuthority(),
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
			acquisitionAuthority(),
		)

		result shouldBe rejected(
			ActivityCaptureAdmissionRejection.SAMPLED_CLASSIFICATION_NOT_DIRECTLY_REQUESTED,
		)
	}

	@Test
	fun `direct sampled detail retains its exact confidence and bounded coverage`() {
		val result = ActivityCapturedObservationAdmission.admit(
			event(
				payload = sampled(confidence = 80),
				physicalFingerprint = SAMPLED_PLAN_FINGERPRINT,
			),
			acquisitionAuthority(activityPlan = SAMPLED_ACTIVITY_PLAN),
		)

		val observation = (result as ActivityCaptureAdmissionResult.Captured).observation as
			ActivityCapturedObservation.SampledClassification
		observation.confidencePercent shouldBe 80
		observation.coverageEndExclusiveElapsedRealtimeNanos shouldBe 1_001_000L
		observation.reference.observationKind shouldBe
			ActivityCapturedObservationKind.SAMPLED_CLASSIFICATION
		observation.reference.observedActivity shouldBe CapturedActivityType.RUNNING
		observation.reference.confidencePercent shouldBe 80
		observation.reference.coverageEndExclusiveElapsedRealtimeNanos shouldBe 1_001_000L
	}

	@Test
	fun `sampled detail below the direct threshold is rejected`() {
		val result = ActivityCapturedObservationAdmission.admit(
			event(
				payload = sampled(confidence = 74),
				physicalFingerprint = SAMPLED_PLAN_FINGERPRINT,
			),
			acquisitionAuthority(activityPlan = SAMPLED_ACTIVITY_PLAN),
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
				physicalFingerprint = SAMPLED_PLAN_FINGERPRINT,
			),
			acquisitionAuthority(
				activityPlan = SAMPLED_ACTIVITY_PLAN,
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
		val encoded = SourcePlanCodec().encode(TRANSITION_ACTIVITY_PLAN)

		shouldThrow<IllegalArgumentException> {
			ActivityCaptureAcquisitionAuthority(
				captureAuthority = captureAuthority,
				historicalConfiguration = ActivityHistoricalAcquisitionConfiguration.fromSerializedPlan(
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
					desiredPlanPayloadVersion = 1,
					desiredPlanPayloadChecksum = encoded.checksum,
					desiredPlanPayload = encoded.bytes,
				),
			)
		}
	}

	@Test
	fun `freshness confidence and sampled horizon come only from canonical serialized plan`() {
		val encoded = SourcePlanCodec().encode(SAMPLED_ACTIVITY_PLAN)
		val historical = ActivityHistoricalAcquisitionConfiguration.fromSerializedPlan(
			identity = acquisitionIdentity(SAMPLED_ACTIVITY_PLAN),
			providerAcceptance = ActivityProviderTimeInterval(0L, 10_000_000L),
			authorizationEffect = ActivityProviderTimeInterval(0L, 10_000_000L),
			sessionRunEffect = ActivityProviderTimeInterval(0L, 10_000_000L),
			desiredPlanPayloadVersion = 1,
			desiredPlanPayloadChecksum = encoded.checksum,
			desiredPlanPayload = encoded.bytes,
		)

		historical.maximumObservationAgeNanos shouldBe 1_000_000L
		historical.sampledClassificationPolicy shouldBe
			ActivitySampledClassificationPolicy.DirectCaptureDetail(75, 1_000_000L)
		shouldThrow<IllegalArgumentException> {
			ActivityHistoricalAcquisitionConfiguration.fromSerializedPlan(
				identity = acquisitionIdentity(SAMPLED_ACTIVITY_PLAN),
				providerAcceptance = ActivityProviderTimeInterval(0L, 10_000_000L),
				authorizationEffect = ActivityProviderTimeInterval(0L, 10_000_000L),
				sessionRunEffect = ActivityProviderTimeInterval(0L, 10_000_000L),
				desiredPlanPayloadVersion = 1,
				desiredPlanPayloadChecksum = "caller-broadened-thresholds",
				desiredPlanPayload = encoded.bytes,
			)
		}
	}

	private fun acquisitionAuthority(
		captureAuthority: ActivityCaptureAuthority = authority(),
		activityPlan: ActivityPlan = TRANSITION_ACTIVITY_PLAN,
		providerAcceptance: ActivityProviderTimeInterval =
			ActivityProviderTimeInterval(0L, 10_000_000L),
		authorizationEffect: ActivityProviderTimeInterval =
			ActivityProviderTimeInterval(0L, 10_000_000L),
		sessionRunEffect: ActivityProviderTimeInterval =
			ActivityProviderTimeInterval(0L, 10_000_000L),
	): ActivityCaptureAcquisitionAuthority {
		val encodedPlan = SourcePlanCodec().encode(activityPlan)
		val exactCaptureAuthority = captureAuthority.copy(
			physicalConfigurationFingerprint = activityPlan.physicalConfigurationFingerprint(),
			temporalAuthority = ActivityCaptureTemporalAuthority(
				providerAcceptance = providerAcceptance,
				authorizationEffect = authorizationEffect,
				sessionRunEffect = sessionRunEffect,
			),
		)
		return ActivityCaptureAcquisitionAuthority(
			captureAuthority = exactCaptureAuthority,
			historicalConfiguration = ActivityHistoricalAcquisitionConfiguration.fromSerializedPlan(
				identity = acquisitionIdentity(activityPlan, exactCaptureAuthority),
				providerAcceptance = providerAcceptance,
				authorizationEffect = authorizationEffect,
				sessionRunEffect = sessionRunEffect,
				desiredPlanPayloadVersion = 1,
				desiredPlanPayloadChecksum = encodedPlan.checksum,
				desiredPlanPayload = encodedPlan.bytes,
			),
		)
	}

	private fun acquisitionIdentity(
		activityPlan: ActivityPlan,
		captureAuthority: ActivityCaptureAuthority = authority(),
	) = ActivityAcquisitionConfigurationIdentity(
		sourceInstanceId = captureAuthority.sourceInstanceId,
		registrationGeneration = captureAuthority.registrationGeneration,
		configurationRevision = captureAuthority.configurationRevision,
		physicalConfigurationFingerprint = activityPlan.physicalConfigurationFingerprint(),
		authorizationRevision = captureAuthority.authorizationRevision,
		authorizationFingerprint = captureAuthority.authorizationFingerprint,
	)

	private fun authority(
		purposeMask: Long = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
	) = ActivityCaptureAuthority(
		logicalTrackingId = LogicalTrackingId("tracking-1"),
		serviceRunId = ServiceRunId("run-1"),
		sourceInstanceId = SourceInstanceId("activity-provider"),
		registrationGeneration = 3L,
		configurationRevision = 5L,
		physicalConfigurationFingerprint = TRANSITION_PLAN_FINGERPRINT,
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
			providerAcceptance = ActivityProviderTimeInterval(0L, 10_000_000L),
			authorizationEffect = ActivityProviderTimeInterval(0L, 10_000_000L),
			sessionRunEffect = ActivityProviderTimeInterval(0L, 10_000_000L),
		),
	)

	private fun event(
		payload: SourcePayload = transition(StableActivityTypeCode.WALKING),
		purposeMask: Long = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		authorizationRevision: Long = 7L,
		providerTime: Long = 1_000L,
		receivedTime: Long = 1_100L,
		physicalFingerprint: String = TRANSITION_PLAN_FINGERPRINT,
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
			physicalConfigurationFingerprint = physicalFingerprint,
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
		observationKind = ActivityCapturedObservationKind.TRANSITION,
		observedActivity = CapturedActivityType.WALKING,
		transitionChange = ActivityTransitionChange.ENTER,
		confidencePercent = null,
		coverageEndExclusiveElapsedRealtimeNanos = null,
	)

	private fun rejected(reason: ActivityCaptureAdmissionRejection) =
		ActivityCaptureAdmissionResult.Rejected(reason)
}
