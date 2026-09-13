package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind

internal data class WifiProviderTimeInterval(
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

/** Exact observed-time limits that must all authorize a captured Wi-Fi observation. */
internal data class WifiCaptureTemporalAuthority(
	val providerAcceptance: WifiProviderTimeInterval,
	val authorizationEffect: WifiProviderTimeInterval,
	val sessionRunEffect: WifiProviderTimeInterval,
) {
	val capturedStartInclusiveNanos: Long = maxOf(
		providerAcceptance.startInclusiveNanos,
		authorizationEffect.startInclusiveNanos,
		sessionRunEffect.startInclusiveNanos,
	)

	val capturedEndExclusiveNanos: Long = minOf(
		providerAcceptance.endExclusiveNanos,
		authorizationEffect.endExclusiveNanos,
		sessionRunEffect.endExclusiveNanos,
	)

	init {
		require(capturedEndExclusiveNanos > capturedStartInclusiveNanos) {
			"Wi-Fi capture authority must have a non-empty observed-time intersection"
		}
	}

	fun contains(providerTimeNanos: Long): Boolean =
		providerTimeNanos >= capturedStartInclusiveNanos &&
			providerTimeNanos < capturedEndExclusiveNanos
}

/**
 * Immutable historical authority for one identity-free Wi-Fi fact.
 *
 * The full manifest source sets and segment/run binding are retained so a Wi-Fi-only fact does not
 * depend on Location or a sample-count heuristic to prove that it belongs to captured history.
 */
internal data class WifiCaptureAuthority(
	val logicalTrackingId: LogicalTrackingId,
	val serviceRunId: ServiceRunId,
	val sessionSegmentId: Long,
	val capturedSources: Set<SourceKind>,
	val controlSources: Set<SourceKind>,
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
	val zoneId: String,
	val temporalAuthority: WifiCaptureTemporalAuthority,
	val maximumObservationAgeNanos: Long,
) {
	init {
		require(sessionSegmentId > 0L)
		require(SourceKind.WIFI in capturedSources) {
			"Captured Wi-Fi authority requires Wi-Fi in the immutable capture set"
		}
		require(capturedSources.intersect(controlSources).isEmpty()) {
			"A manifest source cannot be both captured and control-only"
		}
		require(registrationGeneration > 0L)
		require(configurationRevision >= 0L)
		require(physicalConfigurationFingerprint.isNotBlank())
		require(authorizationRevision > 0L)
		require(authorizationFingerprint.isNotBlank())
		require(purposeEligibilityMask and SourceBrokerPurpose.ALL_MASK == purposeEligibilityMask)
		require(purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L) {
			"Captured Wi-Fi authority requires SESSION_CAPTURE eligibility"
		}
		require(sourcePolicyRevision > 0L)
		require(captureConsentEpoch >= 0L)
		require(sessionManifestRevision > 0L)
		require(lifecycleLeaseGeneration > 0L)
		require(collectedDataEpoch >= 0L)
		require(clockDomainId.isNotBlank())
		require(zoneId.isNotBlank())
		require(maximumObservationAgeNanos >= 0L)
	}
}

/** Stable destination identity. Corrections replace a revision; later callbacks create new facts. */
internal data class WifiCapturedFactIdentity(
	val sourceDeliveryIdentity: SourceDeliveryIdentity,
	val logicalTrackingId: LogicalTrackingId,
	val serviceRunId: ServiceRunId,
	val sessionSegmentId: Long,
	val sessionManifestRevision: Long,
	val collectedDataEpoch: Long,
) {
	init {
		require(sessionSegmentId > 0L)
		require(sessionManifestRevision > 0L)
		require(collectedDataEpoch >= 0L)
	}
}

/** One linear semantic replacement of a stable provider-delivery fact. */
internal data class WifiCapturedFactMutation(
	val identity: WifiCapturedFactIdentity,
	val semanticRevision: Long,
	val supersedesSemanticRevision: Long?,
) {
	init {
		require(semanticRevision > 0L)
		if (semanticRevision == 1L) {
			require(supersedesSemanticRevision == null)
		} else {
			require(supersedesSemanticRevision == semanticRevision - 1L) {
				"A Wi-Fi correction must supersede the immediately preceding semantic revision"
			}
		}
	}
}

internal data class WifiBandMix(
	val twoPointFourGhzCount: Int,
	val fiveGhzCount: Int,
	val sixGhzCount: Int,
	val otherCount: Int,
) {
	init {
		require(twoPointFourGhzCount >= 0)
		require(fiveGhzCount >= 0)
		require(sixGhzCount >= 0)
		require(otherCount >= 0)
	}

	val totalCount: Int
		get() = listOf(
			twoPointFourGhzCount,
			fiveGhzCount,
			sixGhzCount,
			otherCount,
		).fold(0) { total, count -> Math.addExact(total, count) }
}

/** Raw radio identities and per-access-point signal values are intentionally absent. */
internal data class WifiSignalQualitySummary(
	val observationCount: Int,
	val strongestSignalLevelDbm: Int,
	val weakestSignalLevelDbm: Int,
	val signalLevelSumDbm: Long,
) {
	init {
		require(observationCount > 0)
		require(strongestSignalLevelDbm >= weakestSignalLevelDbm)
	}
}

/** The first rollout tier can answer only identity-free aggregate questions. */
internal data class WifiIdentityFreeAggregate(
	val observationCount: Int,
	val bandMix: WifiBandMix,
	val signalQuality: WifiSignalQualitySummary?,
) {
	init {
		require(observationCount >= 0)
		require(bandMix.totalCount == observationCount)
		require((observationCount == 0) == (signalQuality == null))
		require(signalQuality == null || signalQuality.observationCount == observationCount)
	}
}

internal enum class WifiAvailability {
	AVAILABLE,
	CONFIRMED_EMPTY,
}

internal enum class WifiCoverageCompleteness {
	COMPLETE,
	PARTIAL,
}

/** Compact source coverage; it contains counts and time bounds, never AP identity. */
internal data class WifiCoverageEvidence(
	val providerIntervalStartElapsedRealtimeNanos: Long,
	val providerIntervalEndElapsedRealtimeNanos: Long,
	val submittedResultCount: Int,
	val acceptedResultCount: Int,
	val staleResultCount: Int,
	val clockUnverifiableResultCount: Int,
	val completeness: WifiCoverageCompleteness,
) {
	init {
		require(providerIntervalStartElapsedRealtimeNanos >= 0L)
		require(providerIntervalEndElapsedRealtimeNanos >= providerIntervalStartElapsedRealtimeNanos)
		require(submittedResultCount >= 0)
		require(acceptedResultCount >= 0)
		require(staleResultCount >= 0)
		require(clockUnverifiableResultCount >= 0)
		require(
			Math.addExact(
				acceptedResultCount,
				Math.addExact(staleResultCount, clockUnverifiableResultCount),
			) == submittedResultCount,
		)
		require(
			completeness == WifiCoverageCompleteness.COMPLETE ||
				staleResultCount > 0 || clockUnverifiableResultCount > 0,
		)
	}
}

internal data class WifiAggregateFactReference(
	val identity: WifiCapturedFactIdentity,
	val semanticRevision: Long,
) {
	init {
		require(semanticRevision > 0L)
	}
}

internal sealed interface WifiCapturedFact {
	val mutation: WifiCapturedFactMutation
	val authority: WifiCaptureAuthority
	val observedWallTimeMs: Long
	val wallTimeUncertaintyMs: Long
	val availability: WifiAvailability
	val coverage: WifiCoverageEvidence

	data class Aggregate(
		override val mutation: WifiCapturedFactMutation,
		override val authority: WifiCaptureAuthority,
		override val observedWallTimeMs: Long,
		override val wallTimeUncertaintyMs: Long,
		override val availability: WifiAvailability,
		override val coverage: WifiCoverageEvidence,
		val aggregate: WifiIdentityFreeAggregate,
	) : WifiCapturedFact {
		init {
			require(observedWallTimeMs >= 0L)
			require(wallTimeUncertaintyMs >= 0L)
			require(availability == WifiAvailability.AVAILABLE)
			require(aggregate.observationCount > 0)
		}
	}

	data class CoverageOnly(
		override val mutation: WifiCapturedFactMutation,
		override val authority: WifiCaptureAuthority,
		override val observedWallTimeMs: Long,
		override val wallTimeUncertaintyMs: Long,
		override val availability: WifiAvailability,
		override val coverage: WifiCoverageEvidence,
		val reusesAggregate: WifiAggregateFactReference?,
	) : WifiCapturedFact {
		init {
			require(observedWallTimeMs >= 0L)
			require(wallTimeUncertaintyMs >= 0L)
			require(
				(availability == WifiAvailability.AVAILABLE) == (reusesAggregate != null),
			) {
				"Only a fresh unchanged observation may reuse a prior aggregate"
			}
			require(
				availability != WifiAvailability.CONFIRMED_EMPTY ||
					coverage.acceptedResultCount == 0,
			)
		}
	}
}

/** Exact prior fact state used only for replay/correction checks and one-hop aggregate reuse. */
internal data class WifiReusableFact(
	val reference: WifiAggregateFactReference,
	val authority: WifiCaptureAuthority,
	val availability: WifiAvailability,
	val aggregate: WifiIdentityFreeAggregate?,
) {
	init {
		require((availability == WifiAvailability.AVAILABLE) == (aggregate != null))
		require(aggregate == null || aggregate.observationCount > 0)
	}
}
