package com.adsamcik.tracker.tracker.source.activity

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.StableActivityTypeCode

internal data class ActivityProviderTimeInterval(
	val startInclusiveNanos: Long,
	val endExclusiveNanos: Long,
) {
	init {
		require(startInclusiveNanos >= 0L)
		require(endExclusiveNanos > startInclusiveNanos)
	}

	operator fun contains(providerTimeNanos: Long): Boolean =
		providerTimeNanos >= startInclusiveNanos && providerTimeNanos < endExclusiveNanos
}

/** Stable identity of the historical provider configuration supplying capture thresholds. */
internal data class ActivityAcquisitionConfigurationIdentity(
	val sourceInstanceId: String,
	val registrationGeneration: Long,
	val configurationRevision: Long,
	val physicalConfigurationFingerprint: String,
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
) {
	init {
		require(sourceInstanceId.isNotBlank())
		require(registrationGeneration > 0L)
		require(configurationRevision >= 0L)
		require(physicalConfigurationFingerprint.isNotBlank())
		require(authorizationRevision > 0L)
		require(authorizationFingerprint.isNotBlank())
	}
}

/** Policy values are inseparable from the historical configuration that authorized them. */
internal data class ActivityHistoricalAcquisitionConfiguration(
	val identity: ActivityAcquisitionConfigurationIdentity,
	val providerAcceptance: ActivityProviderTimeInterval,
	val authorizationEffect: ActivityProviderTimeInterval,
	val sessionRunEffect: ActivityProviderTimeInterval,
	val maximumObservationAgeNanos: Long,
	val sampledClassificationPolicy: ActivitySampledClassificationPolicy,
) {
	init {
		require(maximumObservationAgeNanos >= 0L)
	}
}

/** Exact physical/authorization contract resolved before a WAL row can become captured Activity. */
internal data class ActivityCaptureAcquisitionAuthority(
	val captureAuthority: ActivityCaptureAuthority,
	val historicalConfiguration: ActivityHistoricalAcquisitionConfiguration,
) {
	init {
		val expectedIdentity = ActivityAcquisitionConfigurationIdentity(
			sourceInstanceId = captureAuthority.sourceInstanceId.value,
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
		if (evidence.source != SourceKind.ACTIVITY) return rejected(ActivityCaptureAdmissionRejection.NOT_ACTIVITY)
		if (evidence.registrationPurposeEligibilityMask and
			SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L
		) return rejected(ActivityCaptureAdmissionRejection.CONTROL_ONLY)

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
		if (logicalTrackingId == null || serviceRunId == null || physicalFingerprint == null ||
			authorizationRevision == null || authorizationFingerprint == null ||
			configurationRevision == null || sourcePolicyRevision == null || captureConsentEpoch == null ||
			manifestRevision == null || leaseGeneration == null ||
			evidence.planAttribution != PlanAttribution.CAPTURED_REGISTRATION ||
			evidence.registrationGeneration <= 0L || evidence.sourceSequence <= 0L ||
			evidence.registrationPurposeEligibilityMask and
				SourceBrokerPurpose.ALL_MASK != evidence.registrationPurposeEligibilityMask
		) return rejected(ActivityCaptureAdmissionRejection.INCOMPLETE_CAPTURE_AUTHORITY)

		val candidateAuthority = ActivityCaptureAuthority(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = evidence.sourceInstanceId,
			registrationGeneration = evidence.registrationGeneration,
			configurationRevision = configurationRevision,
			physicalConfigurationFingerprint = physicalFingerprint,
			authorizationRevision = authorizationRevision,
			authorizationFingerprint = authorizationFingerprint,
			purposeEligibilityMask = evidence.registrationPurposeEligibilityMask,
			sourcePolicyRevision = sourcePolicyRevision,
			captureConsentEpoch = captureConsentEpoch,
			sessionManifestRevision = manifestRevision,
			lifecycleLeaseGeneration = leaseGeneration,
			collectedDataEpoch = evidence.capturedCollectedDataEpoch,
			clockDomainId = evidence.clockDomainId,
		)
		if (candidateAuthority != acquisitionAuthority.captureAuthority) {
			return rejected(ActivityCaptureAdmissionRejection.ACQUISITION_AUTHORITY_MISMATCH)
		}

		val providerTime = event.providerTimeOrNull()
		if (providerTime == null || providerTime != evidence.observedElapsedRealtimeNanos ||
			providerTime > evidence.receivedElapsedRealtimeNanos
		) return rejected(ActivityCaptureAdmissionRejection.MALFORMED_PROVIDER_TIME)
		val historicalConfiguration = acquisitionAuthority.historicalConfiguration
		if (providerTime !in historicalConfiguration.providerAcceptance) {
			return rejected(ActivityCaptureAdmissionRejection.OUTSIDE_PROVIDER_ACCEPTANCE)
		}
		if (providerTime !in historicalConfiguration.authorizationEffect) {
			return rejected(ActivityCaptureAdmissionRejection.OUTSIDE_AUTHORIZATION_EFFECT)
		}
		if (providerTime !in historicalConfiguration.sessionRunEffect) {
			return rejected(ActivityCaptureAdmissionRejection.OUTSIDE_SESSION_RUN_EFFECT)
		}
		if (evidence.receivedElapsedRealtimeNanos - providerTime >
			historicalConfiguration.maximumObservationAgeNanos
		) return rejected(ActivityCaptureAdmissionRejection.STALE_PROVIDER_TIME)

		val wallTimeMs = evidence.wallTimeMs
		val wallTimeUncertaintyMs = evidence.wallTimeUncertaintyMs
		if (wallTimeMs == null || wallTimeMs < 0L || wallTimeUncertaintyMs == null) {
			return rejected(ActivityCaptureAdmissionRejection.WALL_TIME_UNVERIFIABLE)
		}
		val activity = event.activityTypeOrNull()
			?: return rejected(ActivityCaptureAdmissionRejection.UNSUPPORTED_ACTIVITY)
		val reference = ActivityCapturedObservationReference(
			sourceEventId = event.eventId,
			admissionOrdinal = event.admissionOrdinal,
			sourceSequence = evidence.sourceSequence,
			providerElapsedRealtimeNanos = providerTime,
			receivedElapsedRealtimeNanos = evidence.receivedElapsedRealtimeNanos,
		)
		val observation = when (val payload = evidence.payload) {
			is ActivityTransitionPayload -> {
				val change = when (payload.transitionType) {
					TRANSITION_ENTER -> ActivityTransitionChange.ENTER
					TRANSITION_EXIT -> ActivityTransitionChange.EXIT
					else -> return rejected(ActivityCaptureAdmissionRejection.UNSUPPORTED_TRANSITION)
				}
				ActivityCapturedObservation.Transition(
					reference,
					candidateAuthority,
					activity,
					wallTimeMs,
					wallTimeUncertaintyMs,
					change,
				)
			}
			is ActivityRecognitionPayload -> {
				val policy = historicalConfiguration.sampledClassificationPolicy
				if (policy !is ActivitySampledClassificationPolicy.DirectCaptureDetail) {
					return rejected(
						ActivityCaptureAdmissionRejection.SAMPLED_CLASSIFICATION_NOT_DIRECTLY_REQUESTED,
					)
				}
				if (payload.confidencePercent !in 0..100) {
					return rejected(ActivityCaptureAdmissionRejection.MALFORMED_SAMPLED_CONFIDENCE)
				}
				if (payload.confidencePercent < policy.minimumConfidencePercent) {
					return rejected(ActivityCaptureAdmissionRejection.SAMPLED_CLASSIFICATION_BELOW_THRESHOLD)
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
				ActivityCapturedObservation.SampledClassification(
					reference,
					candidateAuthority,
					activity,
					wallTimeMs,
					wallTimeUncertaintyMs,
					payload.confidencePercent,
					coverageEnd,
				)
			}
			else -> return rejected(ActivityCaptureAdmissionRejection.NOT_ACTIVITY)
		}
		return ActivityCaptureAdmissionResult.Captured(observation)
	}

	private fun rejected(reason: ActivityCaptureAdmissionRejection) =
		ActivityCaptureAdmissionResult.Rejected(reason)

	private fun AdmittedSourceEvent<out SourcePayload>.providerTimeOrNull(): Long? =
		when (val payload = evidence.payload) {
			is ActivityTransitionPayload -> payload.providerElapsedRealtimeNanos
			is ActivityRecognitionPayload -> payload.providerElapsedRealtimeNanos
			else -> null
		}

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
		if (this <= Long.MAX_VALUE - other) this + other else null

	private const val TRANSITION_ENTER = 0
	private const val TRANSITION_EXIT = 1
}
