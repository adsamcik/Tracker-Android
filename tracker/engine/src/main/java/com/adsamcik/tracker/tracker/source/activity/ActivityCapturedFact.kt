package com.adsamcik.tracker.tracker.source.activity

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId

/** Immutable capture authority copied from one authorization-homogeneous Activity WAL unit. */
internal data class ActivityCaptureAuthority(
	val logicalTrackingId: LogicalTrackingId,
	val serviceRunId: ServiceRunId,
	val sourceInstanceId: SourceInstanceId,
	val registrationGeneration: Long,
	val physicalConfigurationFingerprint: String,
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
	val purposeEligibilityMask: Long,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val sessionManifestRevision: Long,
	val lifecycleLeaseGeneration: Long,
	val collectedDataEpoch: Long,
	val clockDomainId: String,
) {
	init {
		require(registrationGeneration > 0L)
		require(physicalConfigurationFingerprint.isNotBlank())
		require(authorizationRevision > 0L)
		require(authorizationFingerprint.isNotBlank())
		require(purposeEligibilityMask and SourceBrokerPurpose.ALL_MASK == purposeEligibilityMask)
		require(purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L) {
			"Captured Activity authority requires SESSION_CAPTURE eligibility"
		}
		require(sourcePolicyRevision > 0L)
		require(captureConsentEpoch >= 0L)
		require(sessionManifestRevision > 0L)
		require(lifecycleLeaseGeneration > 0L)
		require(collectedDataEpoch >= 0L)
		require(clockDomainId.isNotBlank())
	}
}

/** Exact durable provider observation that contributed to a captured Activity product. */
internal data class ActivityCapturedObservationReference(
	val sourceEventId: SourceEventId,
	val admissionOrdinal: Long,
	val sourceSequence: Long,
	val providerElapsedRealtimeNanos: Long,
	val receivedElapsedRealtimeNanos: Long,
) {
	init {
		require(admissionOrdinal > 0L)
		require(sourceSequence > 0L)
		require(providerElapsedRealtimeNanos >= 0L)
		require(receivedElapsedRealtimeNanos >= providerElapsedRealtimeNanos)
	}
}

internal enum class CapturedActivityType(val isKnownActive: Boolean, val isKnownInactive: Boolean) {
	STILL(isKnownActive = false, isKnownInactive = true),
	WALKING(isKnownActive = true, isKnownInactive = false),
	RUNNING(isKnownActive = true, isKnownInactive = false),
	ON_BICYCLE(isKnownActive = true, isKnownInactive = false),
	IN_VEHICLE(isKnownActive = true, isKnownInactive = false),
	ON_FOOT(isKnownActive = true, isKnownInactive = false),
	TILTING(isKnownActive = false, isKnownInactive = false),
	UNKNOWN(isKnownActive = false, isKnownInactive = false),
}

internal enum class ActivityTransitionChange { ENTER, EXIT }

/** A source-qualified Activity observation that is independently authorized for captured history. */
internal sealed interface ActivityCapturedObservation {
	val reference: ActivityCapturedObservationReference
	val authority: ActivityCaptureAuthority
	val activity: CapturedActivityType
	val observedWallTimeMs: Long
	val wallTimeUncertaintyMs: Long

	data class Transition(
		override val reference: ActivityCapturedObservationReference,
		override val authority: ActivityCaptureAuthority,
		override val activity: CapturedActivityType,
		override val observedWallTimeMs: Long,
		override val wallTimeUncertaintyMs: Long,
		val change: ActivityTransitionChange,
	) : ActivityCapturedObservation

	data class SampledClassification(
		override val reference: ActivityCapturedObservationReference,
		override val authority: ActivityCaptureAuthority,
		override val activity: CapturedActivityType,
		override val observedWallTimeMs: Long,
		override val wallTimeUncertaintyMs: Long,
		val confidencePercent: Int,
		/** Explicit direct-capture validity ceiling; never inferred from receipt time. */
		val coverageEndExclusiveElapsedRealtimeNanos: Long,
	) : ActivityCapturedObservation {
		init {
			require(confidencePercent in 0..100)
			require(
				coverageEndExclusiveElapsedRealtimeNanos > reference.providerElapsedRealtimeNanos,
			)
		}
	}
}

internal enum class ActivityBandMechanism {
	TRANSITION,
	SAMPLED_CLASSIFICATION,
}

/** Confidence is typed because Activity Transitions do not expose a percentage. */
internal sealed interface ActivityBandConfidence {
	data object TransitionSignal : ActivityBandConfidence

	data class Sampled(
		val minimumPercent: Int,
		val maximumPercent: Int,
		val observationCount: Int,
	) : ActivityBandConfidence {
		init {
			require(minimumPercent in 0..100)
			require(maximumPercent in minimumPercent..100)
			require(observationCount > 0)
		}
	}
}

/** Stable logical range key. Activity corrections replace this range rather than add to it. */
internal data class ActivityCapturedFactKey(
	val authority: ActivityCaptureAuthority,
	val intervalStartElapsedRealtimeNanos: Long,
	val intervalEndExclusiveElapsedRealtimeNanos: Long,
) {
	init {
		require(intervalStartElapsedRealtimeNanos >= 0L)
		require(intervalEndExclusiveElapsedRealtimeNanos > intervalStartElapsedRealtimeNanos)
	}
}

internal data class ActivityCapturedBand(
	val key: ActivityCapturedFactKey,
	val activity: CapturedActivityType,
	val mechanism: ActivityBandMechanism,
	val confidence: ActivityBandConfidence,
	val evidence: List<ActivityCapturedObservationReference>,
) {
	init {
		require(evidence.isNotEmpty())
		require(evidence.distinctBy { it.sourceEventId } == evidence) {
			"Captured Activity evidence cannot repeat a durable event identity"
		}
		require(
			(mechanism == ActivityBandMechanism.TRANSITION) ==
				(confidence is ActivityBandConfidence.TransitionSignal),
		) { "Activity mechanism and confidence type must agree" }
	}

	val durationNanos: Long
		get() = key.intervalEndExclusiveElapsedRealtimeNanos -
			key.intervalStartElapsedRealtimeNanos
}

internal enum class ActivityCoverageGapReason {
	NO_QUALIFIED_EVIDENCE,
	PROVIDER_DISCONTINUITY,
	AUTHORIZATION_DISCONTINUITY,
	PROCESS_OR_REBOOT_DISCONTINUITY,
	SOURCE_REJECTED_EVIDENCE,
}

internal data class ActivityCoverageGap(
	val intervalStartElapsedRealtimeNanos: Long,
	val intervalEndExclusiveElapsedRealtimeNanos: Long,
	val reason: ActivityCoverageGapReason,
) {
	init {
		require(intervalStartElapsedRealtimeNanos >= 0L)
		require(intervalEndExclusiveElapsedRealtimeNanos > intervalStartElapsedRealtimeNanos)
	}
}

internal enum class ActivityCoverage { NONE, PARTIAL, COMPLETE }

/** Truthful active-time components. [completeActiveDurationNanos] is null across any uncertainty. */
internal data class ActivityActiveTime(
	val knownActiveDurationNanos: Long,
	val knownInactiveDurationNanos: Long,
	val unknownActivityDurationNanos: Long,
	val unobservedDurationNanos: Long,
) {
	init {
		require(knownActiveDurationNanos >= 0L)
		require(knownInactiveDurationNanos >= 0L)
		require(unknownActivityDurationNanos >= 0L)
		require(unobservedDurationNanos >= 0L)
	}

	val completeActiveDurationNanos: Long?
		get() = knownActiveDurationNanos.takeIf {
			unknownActivityDurationNanos == 0L && unobservedDurationNanos == 0L
		}
}

/** One exact-authorization coalescing result. Bands and gaps tile the complete requested window. */
internal data class ActivityCapturedWindow(
	val authority: ActivityCaptureAuthority,
	val intervalStartElapsedRealtimeNanos: Long,
	val intervalEndExclusiveElapsedRealtimeNanos: Long,
	val bands: List<ActivityCapturedBand>,
	val gaps: List<ActivityCoverageGap>,
	val exactDuplicateCount: Int,
	val semanticDuplicateCount: Int,
	val unchangedEvidenceCount: Int,
) {
	init {
		require(intervalStartElapsedRealtimeNanos >= 0L)
		require(intervalEndExclusiveElapsedRealtimeNanos > intervalStartElapsedRealtimeNanos)
		require(exactDuplicateCount >= 0)
		require(semanticDuplicateCount >= 0)
		require(unchangedEvidenceCount >= 0)
		require(bands.all { it.key.authority == authority })
		require(bands.zipWithNext().all { (left, right) ->
			left.key.intervalEndExclusiveElapsedRealtimeNanos <=
				right.key.intervalStartElapsedRealtimeNanos
		})
		require(gaps.zipWithNext().all { (left, right) ->
			left.intervalEndExclusiveElapsedRealtimeNanos <=
				right.intervalStartElapsedRealtimeNanos
		})
		val tiled = bands.map { band ->
			band.key.intervalStartElapsedRealtimeNanos to
				band.key.intervalEndExclusiveElapsedRealtimeNanos
		} + gaps.map { gap ->
			gap.intervalStartElapsedRealtimeNanos to gap.intervalEndExclusiveElapsedRealtimeNanos
		}
		val ordered = tiled.sortedBy { it.first }
		require(ordered.isNotEmpty())
		require(ordered.first().first == intervalStartElapsedRealtimeNanos)
		require(ordered.last().second == intervalEndExclusiveElapsedRealtimeNanos)
		require(ordered.zipWithNext().all { (left, right) -> left.second == right.first }) {
			"Captured Activity bands and gaps must tile the requested window exactly"
		}
	}

	val coverage: ActivityCoverage
		get() = when {
			bands.isEmpty() -> ActivityCoverage.NONE
			gaps.isEmpty() -> ActivityCoverage.COMPLETE
			else -> ActivityCoverage.PARTIAL
		}

	val activeTime: ActivityActiveTime
		get() = ActivityActiveTime(
			knownActiveDurationNanos = bands.filter { it.activity.isKnownActive }
				.sumOf(ActivityCapturedBand::durationNanos),
			knownInactiveDurationNanos = bands.filter { it.activity.isKnownInactive }
				.sumOf(ActivityCapturedBand::durationNanos),
			unknownActivityDurationNanos = bands.filter { band ->
				!band.activity.isKnownActive && !band.activity.isKnownInactive
			}.sumOf(ActivityCapturedBand::durationNanos),
			unobservedDurationNanos = gaps.sumOf { gap ->
				gap.intervalEndExclusiveElapsedRealtimeNanos -
					gap.intervalStartElapsedRealtimeNanos
			},
		)
}
