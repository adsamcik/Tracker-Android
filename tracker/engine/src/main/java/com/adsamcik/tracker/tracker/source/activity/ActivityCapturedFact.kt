package com.adsamcik.tracker.tracker.source.activity

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId

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

	fun contains(interval: ActivityProviderTimeInterval): Boolean =
		interval.startInclusiveNanos >= startInclusiveNanos &&
			interval.endExclusiveNanos <= endExclusiveNanos
}

/** Exact physical, authorization, and run-time limits retained with every captured fact. */
internal data class ActivityCaptureTemporalAuthority(
	val providerAcceptance: ActivityProviderTimeInterval,
	val authorizationEffect: ActivityProviderTimeInterval,
	val sessionRunEffect: ActivityProviderTimeInterval,
) {
	val capturedIntersection = ActivityProviderTimeInterval(
		startInclusiveNanos = maxOf(
			providerAcceptance.startInclusiveNanos,
			authorizationEffect.startInclusiveNanos,
			sessionRunEffect.startInclusiveNanos,
		),
		endExclusiveNanos = minOf(
			providerAcceptance.endExclusiveNanos,
			authorizationEffect.endExclusiveNanos,
			sessionRunEffect.endExclusiveNanos,
		),
	)
}

/** Immutable capture authority copied from one authorization-homogeneous Activity WAL unit. */
internal data class ActivityCaptureAuthority(
	val logicalTrackingId: LogicalTrackingId,
	val serviceRunId: ServiceRunId,
	val sourceInstanceId: SourceInstanceId,
	val registrationGeneration: Long,
	val configurationRevision: Long,
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
	val temporalAuthority: ActivityCaptureTemporalAuthority,
) {
	init {
		require(registrationGeneration > 0L)
		require(configurationRevision >= 0L)
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
internal enum class ActivityCapturedObservationKind {
	TRANSITION,
	SAMPLED_CLASSIFICATION,
}

internal data class ActivityCapturedObservationReference(
	val sourceEventId: SourceEventId,
	val admissionOrdinal: Long,
	val sourceSequence: Long,
	val providerElapsedRealtimeNanos: Long,
	val receivedElapsedRealtimeNanos: Long,
	val observationKind: ActivityCapturedObservationKind,
	val observedActivity: CapturedActivityType,
	val transitionChange: ActivityTransitionChange?,
	val confidencePercent: Int?,
	val coverageEndExclusiveElapsedRealtimeNanos: Long?,
) {
	init {
		require(admissionOrdinal > 0L)
		require(sourceSequence > 0L)
		require(providerElapsedRealtimeNanos >= 0L)
		require(receivedElapsedRealtimeNanos >= providerElapsedRealtimeNanos)
		when (observationKind) {
			ActivityCapturedObservationKind.TRANSITION -> {
				require(transitionChange != null)
				require(confidencePercent == null)
				require(coverageEndExclusiveElapsedRealtimeNanos == null)
			}
			ActivityCapturedObservationKind.SAMPLED_CLASSIFICATION -> {
				require(transitionChange == null)
				require(confidencePercent != null && confidencePercent in 0..100)
				require(
					coverageEndExclusiveElapsedRealtimeNanos != null &&
						coverageEndExclusiveElapsedRealtimeNanos > providerElapsedRealtimeNanos,
				)
			}
		}
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

internal fun isCompatibleActivityRefinement(
	coarse: CapturedActivityType,
	detail: CapturedActivityType,
): Boolean = when (coarse) {
	CapturedActivityType.ON_FOOT -> detail == CapturedActivityType.WALKING ||
		detail == CapturedActivityType.RUNNING
	CapturedActivityType.UNKNOWN -> detail != CapturedActivityType.UNKNOWN
	else -> false
}

/** EXIT invalidation is narrower than positive coarse-state refinement. */
internal fun isCompatibleActivityNegativeBoundary(
	exited: CapturedActivityType,
	sampled: CapturedActivityType,
): Boolean = exited == sampled ||
	(exited == CapturedActivityType.ON_FOOT &&
		(sampled == CapturedActivityType.WALKING || sampled == CapturedActivityType.RUNNING))

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
	) : ActivityCapturedObservation {
		init {
			require(observedWallTimeMs >= 0L)
			require(wallTimeUncertaintyMs >= 0L)
			require(reference.observationKind == ActivityCapturedObservationKind.TRANSITION)
			require(reference.observedActivity == activity)
			require(reference.transitionChange == change)
		}
	}

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
			require(observedWallTimeMs >= 0L)
			require(wallTimeUncertaintyMs >= 0L)
			require(confidencePercent in 0..100)
			require(
				coverageEndExclusiveElapsedRealtimeNanos > reference.providerElapsedRealtimeNanos,
			)
			require(
				reference.observationKind ==
					ActivityCapturedObservationKind.SAMPLED_CLASSIFICATION,
			)
			require(reference.observedActivity == activity)
			require(reference.confidencePercent == confidencePercent)
			require(
				reference.coverageEndExclusiveElapsedRealtimeNanos ==
					coverageEndExclusiveElapsedRealtimeNanos,
			)
		}
	}
}

internal enum class ActivityBandMechanism {
	TRANSITION,
	SAMPLED_REFINEMENT,
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

/** Stable source/window identity; derived band boundaries are deliberately not part of it. */
internal data class ActivityCapturedWindowIdentity(
	val authority: ActivityCaptureAuthority,
	val intervalStartElapsedRealtimeNanos: Long,
	val intervalEndExclusiveElapsedRealtimeNanos: Long,
) {
	init {
		require(intervalStartElapsedRealtimeNanos >= 0L)
		require(intervalEndExclusiveElapsedRealtimeNanos > intervalStartElapsedRealtimeNanos)
	}
}

/** One semantic replacement of all child fragments in a stable source window. */
internal data class ActivityCapturedWindowMutation(
	val identity: ActivityCapturedWindowIdentity,
	val semanticRevision: Long,
	val supersedesSemanticRevision: Long?,
) {
	init {
		require(semanticRevision > 0L)
		require(
			if (semanticRevision == 1L) supersedesSemanticRevision == null
			else supersedesSemanticRevision == semanticRevision - 1L,
		)
	}
}

/** Stable child identity within one atomic window mutation. */
internal data class ActivityCapturedFactKey(
	val mutation: ActivityCapturedWindowMutation,
	val fragmentOrdinal: Int,
) {
	init {
		require(fragmentOrdinal >= 0)
	}
}

internal enum class ActivityWallTimeBoundaryKind {
	EXACT_PROVIDER_OBSERVATION,
	SAME_CLOCK_EXTRAPOLATION,
}

internal data class ActivityWallTimeDerivationAuthority(
	val kind: ActivityWallTimeBoundaryKind,
	val anchorSourceEventId: SourceEventId,
	val anchorProviderElapsedRealtimeNanos: Long,
	val clockDomainId: String,
) {
	init {
		require(anchorProviderElapsedRealtimeNanos >= 0L)
		require(clockDomainId.isNotBlank())
	}
}

internal data class ActivityDerivedWallTimeBoundary(
	val wallTimeMs: Long,
	val uncertaintyMs: Long,
	val authority: ActivityWallTimeDerivationAuthority,
) {
	init {
		require(wallTimeMs >= 0L)
		require(uncertaintyMs >= 0L)
	}
}

internal enum class ActivityWallTimeContinuity {
	SAME_ANCHOR,
	CONSISTENT_WITHIN_UNCERTAINTY,
	DISCONTINUITY_DETECTED,
}

/** Derived display-time range with its exact provider-clock anchors and continuity verdict. */
internal data class ActivityDerivedWallTimeRange(
	val startInclusive: ActivityDerivedWallTimeBoundary,
	val endExclusive: ActivityDerivedWallTimeBoundary,
	val continuity: ActivityWallTimeContinuity,
) {
	init {
		require(
			continuity != ActivityWallTimeContinuity.SAME_ANCHOR ||
				startInclusive.authority.anchorSourceEventId ==
				endExclusive.authority.anchorSourceEventId,
		)
	}
}

internal data class ActivityCapturedBand(
	val key: ActivityCapturedFactKey,
	val intervalStartElapsedRealtimeNanos: Long,
	val intervalEndExclusiveElapsedRealtimeNanos: Long,
	val wallTimeRange: ActivityDerivedWallTimeRange,
	val activity: CapturedActivityType,
	val mechanism: ActivityBandMechanism,
	val refinedTransitionActivity: CapturedActivityType?,
	val confidence: ActivityBandConfidence,
	val evidence: List<ActivityCapturedObservationReference>,
) {
	init {
		require(intervalStartElapsedRealtimeNanos >= 0L)
		require(intervalEndExclusiveElapsedRealtimeNanos > intervalStartElapsedRealtimeNanos)
		require(evidence.isNotEmpty())
		require(evidence.distinctBy { it.sourceEventId } == evidence) {
			"Captured Activity evidence cannot repeat a durable event identity"
		}
		val evidenceIds = evidence.mapTo(mutableSetOf()) { it.sourceEventId }
		require(wallTimeRange.startInclusive.authority.anchorSourceEventId in evidenceIds)
		require(wallTimeRange.endExclusive.authority.anchorSourceEventId in evidenceIds)
		require(
			wallTimeRange.startInclusive.authority.clockDomainId ==
				key.mutation.identity.authority.clockDomainId,
		)
		require(
			wallTimeRange.endExclusive.authority.clockDomainId ==
				key.mutation.identity.authority.clockDomainId,
		)
		if (wallTimeRange.startInclusive.authority.kind ==
			ActivityWallTimeBoundaryKind.EXACT_PROVIDER_OBSERVATION
		) require(
			wallTimeRange.startInclusive.authority.anchorProviderElapsedRealtimeNanos ==
				intervalStartElapsedRealtimeNanos,
		)
		if (wallTimeRange.endExclusive.authority.kind ==
			ActivityWallTimeBoundaryKind.EXACT_PROVIDER_OBSERVATION
		) require(
			wallTimeRange.endExclusive.authority.anchorProviderElapsedRealtimeNanos ==
				intervalEndExclusiveElapsedRealtimeNanos,
		)
		val sampledEvidence = evidence.filter {
			it.observationKind == ActivityCapturedObservationKind.SAMPLED_CLASSIFICATION
		}
		val transitionEvidence = evidence.filter {
			it.observationKind == ActivityCapturedObservationKind.TRANSITION
		}
		when (mechanism) {
			ActivityBandMechanism.TRANSITION -> {
				require(confidence is ActivityBandConfidence.TransitionSignal)
				require(refinedTransitionActivity == null)
				require(sampledEvidence.isEmpty())
				require(transitionEvidence.any {
					it.observedActivity == activity &&
						it.transitionChange == ActivityTransitionChange.ENTER
				})
			}
			ActivityBandMechanism.SAMPLED_REFINEMENT -> {
				require(confidence is ActivityBandConfidence.Sampled)
				require(refinedTransitionActivity != null)
				require(isCompatibleActivityRefinement(refinedTransitionActivity, activity))
				require(sampledEvidence.any { it.observedActivity == activity })
				require(transitionEvidence.any {
					it.observedActivity == refinedTransitionActivity &&
						it.transitionChange == ActivityTransitionChange.ENTER
				})
			}
			ActivityBandMechanism.SAMPLED_CLASSIFICATION -> {
				require(confidence is ActivityBandConfidence.Sampled)
				require(refinedTransitionActivity == null)
				require(sampledEvidence.any { it.observedActivity == activity })
			}
		}
		if (confidence is ActivityBandConfidence.Sampled) {
			val evidenceConfidence = sampledEvidence.map { requireNotNull(it.confidencePercent) }
			require(evidenceConfidence.isNotEmpty())
			require(confidence.minimumPercent == evidenceConfidence.minOrNull())
			require(confidence.maximumPercent == evidenceConfidence.maxOrNull())
			require(confidence.observationCount == evidenceConfidence.size)
			require(sampledEvidence.all { reference ->
				reference.observedActivity == activity &&
					reference.providerElapsedRealtimeNanos < intervalEndExclusiveElapsedRealtimeNanos &&
					requireNotNull(reference.coverageEndExclusiveElapsedRealtimeNanos) >
						intervalStartElapsedRealtimeNanos
			})
		}
	}

	val durationNanos: Long
		get() = intervalEndExclusiveElapsedRealtimeNanos -
			intervalStartElapsedRealtimeNanos
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
	val mutation: ActivityCapturedWindowMutation,
	val bands: List<ActivityCapturedBand>,
	val gaps: List<ActivityCoverageGap>,
	val exactDuplicateCount: Int,
	val semanticDuplicateCount: Int,
	val unchangedEvidenceCount: Int,
) {
	val authority: ActivityCaptureAuthority
		get() = mutation.identity.authority

	val intervalStartElapsedRealtimeNanos: Long
		get() = mutation.identity.intervalStartElapsedRealtimeNanos

	val intervalEndExclusiveElapsedRealtimeNanos: Long
		get() = mutation.identity.intervalEndExclusiveElapsedRealtimeNanos

	init {
		require(exactDuplicateCount >= 0)
		require(semanticDuplicateCount >= 0)
		require(unchangedEvidenceCount >= 0)
		require(bands.all { it.key.mutation == mutation })
		require(bands.map { it.key.fragmentOrdinal } == bands.indices.toList())
		require(bands.zipWithNext().all { (left, right) ->
			left.intervalEndExclusiveElapsedRealtimeNanos <=
				right.intervalStartElapsedRealtimeNanos
		})
		require(gaps.zipWithNext().all { (left, right) ->
			left.intervalEndExclusiveElapsedRealtimeNanos <=
				right.intervalStartElapsedRealtimeNanos
		})
		val tiled = bands.map { band ->
			band.intervalStartElapsedRealtimeNanos to
				band.intervalEndExclusiveElapsedRealtimeNanos
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
