package com.adsamcik.tracker.tracker.source.activity

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.ADAPTIVE_PROVIDER_ITEM_MAXIMUM_AGE_MS
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.StableActivityTypeCode
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint

private const val NANOS_PER_MILLISECOND = 1_000_000L

/** Stable identity of the historical provider configuration supplying capture thresholds. */
internal data class ActivityAcquisitionConfigurationIdentity(
	val sourceInstanceId: SourceInstanceId,
	val registrationGeneration: Long,
	val configurationRevision: Long,
	val physicalConfigurationFingerprint: String,
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
) {
	init {
		require(registrationGeneration > 0L)
		require(configurationRevision >= 0L)
		require(physicalConfigurationFingerprint.isNotBlank())
		require(authorizationRevision > 0L)
		require(authorizationFingerprint.isNotBlank())
	}
}

/**
 * Policy values decoded from the exact immutable historical plan payload.
 *
 * Callers cannot supply freshness, confidence, or coverage thresholds separately from the
 * canonical serialized plan that produced the physical registration fingerprint.
 */
internal class ActivityHistoricalAcquisitionConfiguration private constructor(
	val identity: ActivityAcquisitionConfigurationIdentity,
	val providerAcceptance: ActivityProviderTimeInterval,
	val authorizationEffect: ActivityProviderTimeInterval,
	val sessionRunEffect: ActivityProviderTimeInterval,
	val desiredPlanPayloadVersion: Int,
	val desiredPlanPayloadChecksum: String,
	val activityPlan: ActivityPlan,
) {
	val maximumObservationAgeNanos: Long = requireNotNull(
		activityPlan.capturedObservationMaximumAgeNanosOrNull(),
	)
	val sampledClassificationPolicy: ActivitySampledClassificationPolicy =
		activityPlan.capturedSampledClassificationPolicy()

	init {
		require(desiredPlanPayloadVersion in setOf(
			SOURCE_PLAN_PAYLOAD_VERSION, SourcePlanCodec.ADAPTIVE_ACTIVITY_FORMAT_VERSION))
		require(desiredPlanPayloadChecksum.isNotBlank())
		require(activityPlan.revision == identity.configurationRevision)
		require(activityPlan.enabled)
		require(activityPlan.physicalConfigurationFingerprint() ==
			identity.physicalConfigurationFingerprint)
		require(activityPlan.confidenceThresholdPercent in 0..100)
	}

	companion object {
		fun fromSerializedPlan(
			identity: ActivityAcquisitionConfigurationIdentity,
			providerAcceptance: ActivityProviderTimeInterval,
			authorizationEffect: ActivityProviderTimeInterval,
			sessionRunEffect: ActivityProviderTimeInterval,
			desiredPlanPayloadVersion: Int,
			desiredPlanPayloadChecksum: String,
			desiredPlanPayload: ByteArray,
			planCodec: SourcePlanCodec = SourcePlanCodec(),
		): ActivityHistoricalAcquisitionConfiguration {
			require(desiredPlanPayloadVersion in setOf(
				SOURCE_PLAN_PAYLOAD_VERSION, SourcePlanCodec.ADAPTIVE_ACTIVITY_FORMAT_VERSION))
			val decoded = runCatching { planCodec.decode(desiredPlanPayload) }.getOrNull()
			val activityPlan = decoded as? ActivityPlan
				?: throw IllegalArgumentException("Historical Activity plan payload is unsupported")
			val canonical = planCodec.encode(activityPlan)
			require(canonical.formatVersion == desiredPlanPayloadVersion)
			require(desiredPlanPayload.contentEquals(canonical.bytes)) {
				"Historical Activity plan payload is not canonical"
			}
			require(desiredPlanPayloadChecksum == canonical.checksum) {
				"Historical Activity plan checksum does not match its payload"
			}
			return ActivityHistoricalAcquisitionConfiguration(
				identity = identity,
				providerAcceptance = providerAcceptance,
				authorizationEffect = authorizationEffect,
				sessionRunEffect = sessionRunEffect,
				desiredPlanPayloadVersion = desiredPlanPayloadVersion,
				desiredPlanPayloadChecksum = desiredPlanPayloadChecksum,
				activityPlan = activityPlan,
			)
		}

		private const val SOURCE_PLAN_PAYLOAD_VERSION = 1
	}
}

internal fun ActivityPlan.capturedObservationMaximumAgeNanosOrNull(): Long? =
	(if (adaptiveProfileVersion == 1) {
		ADAPTIVE_PROVIDER_ITEM_MAXIMUM_AGE_MS
	} else {
		desiredDetectionLatencyMs
	}).takeIf { latencyMs ->
		latencyMs > 0L && latencyMs <= Long.MAX_VALUE / NANOS_PER_MILLISECOND
	}?.times(NANOS_PER_MILLISECOND)

internal fun ActivityPlan.capturedCoverageHorizonNanosOrNull(): Long? =
    desiredDetectionLatencyMs.takeIf { it > 0L && it <= Long.MAX_VALUE / NANOS_PER_MILLISECOND }
        ?.times(NANOS_PER_MILLISECOND)

private fun ActivityPlan.capturedSampledClassificationPolicy():
	ActivitySampledClassificationPolicy = when (mode) {
		ActivityMode.CONTINUOUS_RECOGNITION -> ActivitySampledClassificationPolicy.DirectCaptureDetail(
			minimumConfidencePercent = confidenceThresholdPercent,
			maximumCoverageAfterObservationNanos =
				requireNotNull(capturedCoverageHorizonNanosOrNull()),
		)
		ActivityMode.TRANSITIONS_ONLY,
		ActivityMode.OFF,
		-> ActivitySampledClassificationPolicy.NotDirectlyRequested
	}

/** Exact physical/authorization contract resolved before a WAL row can become captured Activity. */
internal data class ActivityCaptureAcquisitionAuthority(
	val captureAuthority: ActivityCaptureAuthority,
	val historicalConfiguration: ActivityHistoricalAcquisitionConfiguration,
) {
	init {
		val expectedIdentity = ActivityAcquisitionConfigurationIdentity(
			sourceInstanceId = captureAuthority.sourceInstanceId,
			registrationGeneration = captureAuthority.registrationGeneration,
			configurationRevision = captureAuthority.configurationRevision,
			physicalConfigurationFingerprint =
				captureAuthority.physicalConfigurationFingerprint,
			authorizationRevision = captureAuthority.authorizationRevision,
			authorizationFingerprint = captureAuthority.authorizationFingerprint,
		)
		require(historicalConfiguration.identity == expectedIdentity) {
			"Capture thresholds must belong to the exact historical acquisition identity"
		}
		require(
			captureAuthority.temporalAuthority == ActivityCaptureTemporalAuthority(
				providerAcceptance = historicalConfiguration.providerAcceptance,
				authorizationEffect = historicalConfiguration.authorizationEffect,
				sessionRunEffect = historicalConfiguration.sessionRunEffect,
			),
		) { "Capture authority must retain the exact historical temporal limits" }
	}
}

/** Sampling is accepted only when an exact direct capture plan supplies its product thresholds. */
internal sealed interface ActivitySampledClassificationPolicy {
	data object NotDirectlyRequested : ActivitySampledClassificationPolicy

	data class DirectCaptureDetail(
		val minimumConfidencePercent: Int,
		val maximumCoverageAfterObservationNanos: Long,
	) : ActivitySampledClassificationPolicy {
		init {
			require(minimumConfidencePercent in 0..100)
			require(maximumCoverageAfterObservationNanos > 0L)
		}
	}
}

internal enum class ActivityCaptureAdmissionRejection {
	NOT_ACTIVITY,
	CONTROL_ONLY,
	INCOMPLETE_CAPTURE_AUTHORITY,
	ACQUISITION_AUTHORITY_MISMATCH,
	OUTSIDE_PROVIDER_ACCEPTANCE,
	OUTSIDE_AUTHORIZATION_EFFECT,
	OUTSIDE_SESSION_RUN_EFFECT,
	MALFORMED_PROVIDER_TIME,
	STALE_PROVIDER_TIME,
	WALL_TIME_UNVERIFIABLE,
	UNSUPPORTED_ACTIVITY,
	UNSUPPORTED_TRANSITION,
	SAMPLED_CLASSIFICATION_NOT_DIRECTLY_REQUESTED,
	MALFORMED_SAMPLED_CONFIDENCE,
	SAMPLED_CLASSIFICATION_BELOW_THRESHOLD,
	SAMPLED_COVERAGE_OVERFLOW,
}

internal sealed interface ActivityCaptureAdmissionResult {
	data class Captured(
		val observation: ActivityCapturedObservation,
	) : ActivityCaptureAdmissionResult

	data class Rejected(
		val reason: ActivityCaptureAdmissionRejection,
	) : ActivityCaptureAdmissionResult
}

/** Pure capture admission. It cannot turn a control-only Activity callback into product history. */
internal object ActivityCapturedObservationAdmission {
	fun admit(
		event: AdmittedSourceEvent<out SourcePayload>,
		acquisitionAuthority: ActivityCaptureAcquisitionAuthority,
	): ActivityCaptureAdmissionResult {
		val evidence = event.evidence
		val captureRejection = when {
			evidence.source != SourceKind.ACTIVITY -> ActivityCaptureAdmissionRejection.NOT_ACTIVITY
			evidence.registrationPurposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ->
				ActivityCaptureAdmissionRejection.CONTROL_ONLY
			else -> null
		}
		if (captureRejection != null) {
			return rejected(captureRejection)
		}

		val candidateAuthority = event.captureAuthorityOrNull(acquisitionAuthority.captureAuthority.temporalAuthority)
			?: return rejected(ActivityCaptureAdmissionRejection.INCOMPLETE_CAPTURE_AUTHORITY)
		if (candidateAuthority != acquisitionAuthority.captureAuthority) {
			return rejected(ActivityCaptureAdmissionRejection.ACQUISITION_AUTHORITY_MISMATCH)
		}

		return admitTimedObservation(event, candidateAuthority, acquisitionAuthority.historicalConfiguration)
	}

	private fun admitTimedObservation(
		event: AdmittedSourceEvent<out SourcePayload>,
		candidateAuthority: ActivityCaptureAuthority,
		historicalConfiguration: ActivityHistoricalAcquisitionConfiguration,
	): ActivityCaptureAdmissionResult {
		val evidence = event.evidence
		val providerTime = event.providerTimeOrNull()
		if (providerTime == null || providerTime != evidence.observedElapsedRealtimeNanos ||
			providerTime > evidence.receivedElapsedRealtimeNanos
		) {
			return rejected(ActivityCaptureAdmissionRejection.MALFORMED_PROVIDER_TIME)
		}
		historicalConfiguration.temporalRejection(providerTime, evidence.receivedElapsedRealtimeNanos)
			?.let { return rejected(it) }
		val wallTimeMs = evidence.wallTimeMs
		val wallTimeUncertaintyMs = evidence.wallTimeUncertaintyMs
		if (wallTimeMs == null || wallTimeMs < 0L || wallTimeUncertaintyMs == null) {
			return rejected(ActivityCaptureAdmissionRejection.WALL_TIME_UNVERIFIABLE)
		}
		return captureActivityPayload(event, candidateAuthority, historicalConfiguration, providerTime)
	}

	private fun captureActivityPayload(
		event: AdmittedSourceEvent<out SourcePayload>,
		candidateAuthority: ActivityCaptureAuthority,
		historicalConfiguration: ActivityHistoricalAcquisitionConfiguration,
		providerTime: Long,
	): ActivityCaptureAdmissionResult {
		val evidence = event.evidence
		val activity = event.activityTypeOrNull()
			?: return rejected(ActivityCaptureAdmissionRejection.UNSUPPORTED_ACTIVITY)
		return when (val payload = evidence.payload) {
			is ActivityTransitionPayload -> captureTransition(event, candidateAuthority, activity, providerTime, payload)
			is ActivityRecognitionPayload -> captureSampledClassification(
				event, candidateAuthority, activity, providerTime, payload, historicalConfiguration,
			)
			else -> rejected(ActivityCaptureAdmissionRejection.NOT_ACTIVITY)
		}
	}

	// Project only the complete run binding, provider identity and admitted envelope.
	private fun AdmittedSourceEvent<out SourcePayload>.captureAuthorityOrNull(
		temporalAuthority: ActivityCaptureTemporalAuthority,
	): ActivityCaptureAuthority? {
		val evidence = evidence
		val logicalTrackingId = evidence.logicalTrackingId
		val serviceRunId = evidence.serviceRunId
		val physicalFingerprint = evidence.physicalConfigurationFingerprint
		val authorizationRevision = evidence.authorizationRevision
		val authorizationFingerprint = evidence.registrationEligibilityFingerprint
		val configurationRevision = evidence.configRevision
		val sourcePolicyRevision = evidence.sourcePolicyRevision
		val captureConsentEpoch = evidence.captureConsentEpoch
		val manifestRevision = evidence.sessionManifestRevision
		val leaseGeneration = evidence.lifecycleLeaseGeneration
		if (hasIncompleteCaptureRunIdentity() || hasIncompleteCaptureProviderIdentity()) {
			return null
		}
		if (evidence.planAttribution != PlanAttribution.CAPTURED_REGISTRATION ||
			evidence.registrationGeneration <= 0L || evidence.sourceSequence < 0L ||
			evidence.registrationPurposeEligibilityMask and
				SourceBrokerPurpose.ALL_MASK != evidence.registrationPurposeEligibilityMask
		) {
			return null
		}

		return ActivityCaptureAuthority(
			logicalTrackingId = requireNotNull(logicalTrackingId),
			serviceRunId = requireNotNull(serviceRunId),
			sourceInstanceId = evidence.sourceInstanceId,
			registrationGeneration = evidence.registrationGeneration,
			configurationRevision = requireNotNull(configurationRevision),
			physicalConfigurationFingerprint = requireNotNull(physicalFingerprint),
			authorizationRevision = requireNotNull(authorizationRevision),
			authorizationFingerprint = requireNotNull(authorizationFingerprint),
			purposeEligibilityMask = evidence.registrationPurposeEligibilityMask,
			sourcePolicyRevision = requireNotNull(sourcePolicyRevision),
			captureConsentEpoch = requireNotNull(captureConsentEpoch),
			sessionManifestRevision = requireNotNull(manifestRevision),
			lifecycleLeaseGeneration = requireNotNull(leaseGeneration),
			collectedDataEpoch = evidence.capturedCollectedDataEpoch,
			clockDomainId = evidence.clockDomainId,
			temporalAuthority = temporalAuthority,
		)
	}

	private fun AdmittedSourceEvent<out SourcePayload>.hasIncompleteCaptureRunIdentity(): Boolean =
		evidence.logicalTrackingId == null || evidence.serviceRunId == null ||
			evidence.sessionManifestRevision == null || evidence.lifecycleLeaseGeneration == null

	private fun AdmittedSourceEvent<out SourcePayload>.hasIncompleteCaptureProviderIdentity(): Boolean =
		evidence.physicalConfigurationFingerprint == null || evidence.authorizationRevision == null ||
			evidence.registrationEligibilityFingerprint == null || evidence.configRevision == null ||
			evidence.sourcePolicyRevision == null || evidence.captureConsentEpoch == null

	private fun ActivityHistoricalAcquisitionConfiguration.temporalRejection(
		providerTime: Long,
		receivedElapsedRealtimeNanos: Long,
	): ActivityCaptureAdmissionRejection? = when {
		providerTime !in providerAcceptance -> ActivityCaptureAdmissionRejection.OUTSIDE_PROVIDER_ACCEPTANCE
		providerTime !in authorizationEffect -> ActivityCaptureAdmissionRejection.OUTSIDE_AUTHORIZATION_EFFECT
		providerTime !in sessionRunEffect -> ActivityCaptureAdmissionRejection.OUTSIDE_SESSION_RUN_EFFECT
		receivedElapsedRealtimeNanos - providerTime > maximumObservationAgeNanos ->
			ActivityCaptureAdmissionRejection.STALE_PROVIDER_TIME
		else -> null
	}

	private fun captureTransition(
		event: AdmittedSourceEvent<out SourcePayload>,
		candidateAuthority: ActivityCaptureAuthority,
		activity: CapturedActivityType,
		providerTime: Long,
		payload: ActivityTransitionPayload,
	): ActivityCaptureAdmissionResult {
		val evidence = event.evidence
		val wallTimeMs = requireNotNull(evidence.wallTimeMs)
		val wallTimeUncertaintyMs = requireNotNull(evidence.wallTimeUncertaintyMs)
		val change = when (payload.transitionType) {
			TRANSITION_ENTER -> ActivityTransitionChange.ENTER
			TRANSITION_EXIT -> ActivityTransitionChange.EXIT
			else -> return rejected(ActivityCaptureAdmissionRejection.UNSUPPORTED_TRANSITION)
		}
		val observation = ActivityCapturedObservation.Transition(
			reference = ActivityCapturedObservationReference(
				sourceEventId = event.eventId,
				admissionOrdinal = event.admissionOrdinal,
				sourceSequence = evidence.sourceSequence,
				providerElapsedRealtimeNanos = providerTime,
				receivedElapsedRealtimeNanos = evidence.receivedElapsedRealtimeNanos,
				observationKind = ActivityCapturedObservationKind.TRANSITION,
				observedActivity = activity,
				transitionChange = change,
				confidencePercent = null,
				coverageEndExclusiveElapsedRealtimeNanos = null,
			),
			authority = candidateAuthority,
			activity = activity,
			observedWallTimeMs = wallTimeMs,
			wallTimeUncertaintyMs = wallTimeUncertaintyMs,
			change = change,
		)
		return ActivityCaptureAdmissionResult.Captured(observation)
	}

	private fun captureSampledClassification(
		event: AdmittedSourceEvent<out SourcePayload>,
		candidateAuthority: ActivityCaptureAuthority,
		activity: CapturedActivityType,
		providerTime: Long,
		payload: ActivityRecognitionPayload,
		historicalConfiguration: ActivityHistoricalAcquisitionConfiguration,
	): ActivityCaptureAdmissionResult {
		val evidence = event.evidence
		val wallTimeMs = requireNotNull(evidence.wallTimeMs)
		val wallTimeUncertaintyMs = requireNotNull(evidence.wallTimeUncertaintyMs)
		val policy = historicalConfiguration.sampledClassificationPolicy
		if (policy !is ActivitySampledClassificationPolicy.DirectCaptureDetail) {
			return rejected(
				ActivityCaptureAdmissionRejection.SAMPLED_CLASSIFICATION_NOT_DIRECTLY_REQUESTED,
			)
		}
		val confidenceRejection = when {
			payload.confidencePercent !in 0..100 ->
				ActivityCaptureAdmissionRejection.MALFORMED_SAMPLED_CONFIDENCE
			payload.confidencePercent < policy.minimumConfidencePercent ->
				ActivityCaptureAdmissionRejection.SAMPLED_CLASSIFICATION_BELOW_THRESHOLD
			else -> null
		}
		if (confidenceRejection != null) {
			return rejected(confidenceRejection)
		}
		val configuredCoverageEnd = providerTime.checkedAdd(
			policy.maximumCoverageAfterObservationNanos,
		) ?: return rejected(ActivityCaptureAdmissionRejection.SAMPLED_COVERAGE_OVERFLOW)
		val coverageEnd = minOf(
			configuredCoverageEnd,
			historicalConfiguration.providerAcceptance.endExclusiveNanos,
			historicalConfiguration.authorizationEffect.endExclusiveNanos,
			historicalConfiguration.sessionRunEffect.endExclusiveNanos,
		)
		val observation = ActivityCapturedObservation.SampledClassification(
			reference = ActivityCapturedObservationReference(
				sourceEventId = event.eventId,
				admissionOrdinal = event.admissionOrdinal,
				sourceSequence = evidence.sourceSequence,
				providerElapsedRealtimeNanos = providerTime,
				receivedElapsedRealtimeNanos = evidence.receivedElapsedRealtimeNanos,
				observationKind =
					ActivityCapturedObservationKind.SAMPLED_CLASSIFICATION,
				observedActivity = activity,
				transitionChange = null,
				confidencePercent = payload.confidencePercent,
				coverageEndExclusiveElapsedRealtimeNanos = coverageEnd,
			),
			authority = candidateAuthority,
			activity = activity,
			observedWallTimeMs = wallTimeMs,
			wallTimeUncertaintyMs = wallTimeUncertaintyMs,
			confidencePercent = payload.confidencePercent,
			coverageEndExclusiveElapsedRealtimeNanos = coverageEnd,
		)
		return ActivityCaptureAdmissionResult.Captured(observation)
	}

	private fun rejected(reason: ActivityCaptureAdmissionRejection) =
		ActivityCaptureAdmissionResult.Rejected(reason)

	private const val TRANSITION_ENTER = 0
	private const val TRANSITION_EXIT = 1
}

private fun AdmittedSourceEvent<out SourcePayload>.providerTimeOrNull(): Long? =
	when (val payload = evidence.payload) {
		is ActivityTransitionPayload -> payload.providerElapsedRealtimeNanos
		is ActivityRecognitionPayload -> payload.providerElapsedRealtimeNanos
		else -> null
	}

@Suppress("CyclomaticComplexMethod") // Keep this fixed field or enum projection together.
private fun AdmittedSourceEvent<out SourcePayload>.activityTypeOrNull(): CapturedActivityType? {
	val code = when (val payload = evidence.payload) {
		is ActivityTransitionPayload -> payload.activityType
		is ActivityRecognitionPayload -> payload.activityType
		else -> return null
	}
	return when (code) {
		StableActivityTypeCode.STILL -> CapturedActivityType.STILL
		StableActivityTypeCode.WALKING -> CapturedActivityType.WALKING
		StableActivityTypeCode.RUNNING -> CapturedActivityType.RUNNING
		StableActivityTypeCode.ON_BICYCLE -> CapturedActivityType.ON_BICYCLE
		StableActivityTypeCode.IN_VEHICLE -> CapturedActivityType.IN_VEHICLE
		StableActivityTypeCode.ON_FOOT -> CapturedActivityType.ON_FOOT
		StableActivityTypeCode.TILTING -> CapturedActivityType.TILTING
		StableActivityTypeCode.UNKNOWN -> CapturedActivityType.UNKNOWN
		else -> null
	}
}

private fun Long.checkedAdd(other: Long): Long? =
	if (this <= Long.MAX_VALUE - other) {
		this + other
	} else {
		null
	}
