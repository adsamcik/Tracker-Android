package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.time.ZoneId

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
 * Versioned source-local contract for interpreting Android Wi-Fi result children.
 *
 * These bounds are part of the qualifier, not mutable acquisition knobs. A historical fact binds
 * to one named contract so a caller cannot widen or narrow the accepted result set.
 */
internal enum class WifiIdentityFreeResultContract(
	val maximumAccessPointCount: Int,
	val minimumFrequencyMhz: Int,
	val maximumFrequencyMhz: Int,
	val minimumSignalLevelDbm: Int,
	val maximumSignalLevelDbm: Int,
) {
	ANDROID_SCAN_RESULTS_V1(
		maximumAccessPointCount = 64,
		minimumFrequencyMhz = 2_400,
		maximumFrequencyMhz = 7_125,
		minimumSignalLevelDbm = -200,
		maximumSignalLevelDbm = 0,
	),
	;

	fun accepts(frequencyMhz: Int, signalLevelDbm: Int): Boolean =
		frequencyMhz in minimumFrequencyMhz..maximumFrequencyMhz &&
			signalLevelDbm in minimumSignalLevelDbm..maximumSignalLevelDbm
}

/** Exact source-local acquisition inputs retained by the historical fact. */
internal data class WifiHistoricalAcquisitionConfiguration(
	val maximumObservationAgeNanos: Long,
	val resultContract: WifiIdentityFreeResultContract,
) {
	init {
		require(maximumObservationAgeNanos >= 0L)
	}
}

/** Exact immutable `source_desired_plan` row used to qualify this observation. */
internal data class WifiSerializedAcquisitionPlanEvidence(
	val payloadVersion: Int,
	val payloadBytes: List<Byte>,
	val payloadChecksum: String,
)

/** Exact applied provider registration that produced the callback, not the latest mutable pointer. */
internal data class WifiAppliedRegistrationEvidence(
	val desiredRevision: Long,
	val appliedRevision: Long?,
	val sourceInstanceId: SourceInstanceId?,
	val registrationGeneration: Long?,
	val appliedAtElapsedRealtimeNanos: Long?,
	val physicalConfigurationFingerprint: String?,
	val clockDomainId: String?,
	val capturedCollectedDataEpoch: Long?,
)

/** Current deletion authority is deliberately not part of immutable captured authority. */
internal data class WifiDeletionAuthority(
	val currentCollectedDataEpoch: Long,
	val retainedFromWallTimeMs: Long?,
	val currentScopeDeletionGeneration: Long = 0L,
) {
	init {
		require(currentCollectedDataEpoch >= 0L)
		require(retainedFromWallTimeMs == null || retainedFromWallTimeMs >= 0L)
		require(currentScopeDeletionGeneration >= 0L)
	}
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
	val acquisitionConfiguration: WifiHistoricalAcquisitionConfiguration,
	val serializedAcquisitionPlan: WifiSerializedAcquisitionPlanEvidence,
	val appliedRegistration: WifiAppliedRegistrationEvidence,
	val scopeDeletionGeneration: Long = 0L,
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
		require(LOWERCASE_SHA_256.matches(authorizationFingerprint)) {
			"Captured Wi-Fi authority requires a broker authorization fingerprint"
		}
		require(purposeEligibilityMask and SourceBrokerPurpose.ALL_MASK == purposeEligibilityMask)
		require(purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L) {
			"Captured Wi-Fi authority requires SESSION_CAPTURE eligibility"
		}
		require(sourcePolicyRevision > 0L)
		require(captureConsentEpoch >= 0L)
		require(sessionManifestRevision > 0L)
		require(lifecycleLeaseGeneration > 0L)
		require(collectedDataEpoch >= 0L)
		require(scopeDeletionGeneration >= 0L)
		require(clockDomainId.isNotBlank())
		require(zoneId.isNotBlank() && runCatching { ZoneId.of(zoneId) }.isSuccess) {
			"Captured Wi-Fi authority requires a valid stored zone"
		}
	}

	private companion object {
		val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
	}
}

/** Stable destination identity. Corrections replace a revision; later callbacks create new facts. */
internal data class WifiCapturedFactIdentity(
	val sourceEventId: SourceEventId,
	val sourceAdmissionOrdinal: Long,
	val walIntegrityIdentity: String,
	val payloadChecksum: String,
	val sourceDeliveryIdentity: SourceDeliveryIdentity,
	val deliveryUnitIndex: Int,
	val logicalTrackingId: LogicalTrackingId,
	val serviceRunId: ServiceRunId,
	val sessionSegmentId: Long,
	val sessionManifestRevision: Long,
	val collectedDataEpoch: Long,
	val scopeDeletionGeneration: Long = 0L,
) {
	init {
		require(sourceAdmissionOrdinal > 0L)
		require(LOWERCASE_SHA_256.matches(walIntegrityIdentity))
		require(LOWERCASE_SHA_256.matches(payloadChecksum))
		require(deliveryUnitIndex >= 0)
		require(sessionSegmentId > 0L)
		require(sessionManifestRevision > 0L)
		require(collectedDataEpoch >= 0L)
		require(scopeDeletionGeneration >= 0L)
	}

	private companion object {
		val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
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
}

internal enum class WifiCoverageCompleteness {
	COMPLETE,
	PARTIAL,
}

/** Compact source coverage; it contains counts and time bounds, never AP identity. */
internal data class WifiCoverageEvidence(
	val providerIntervalStartElapsedRealtimeNanos: Long,
	val providerIntervalEndElapsedRealtimeNanos: Long,
	val receivedElapsedRealtimeNanos: Long,
	val submittedResultCount: Int,
	val acceptedResultCount: Int,
	val staleResultCount: Int,
	val clockUnverifiableResultCount: Int,
	val malformedResultCount: Int,
	val completeness: WifiCoverageCompleteness,
) {
	init {
		require(providerIntervalStartElapsedRealtimeNanos >= 0L)
		require(providerIntervalEndElapsedRealtimeNanos >= providerIntervalStartElapsedRealtimeNanos)
		require(receivedElapsedRealtimeNanos >= providerIntervalEndElapsedRealtimeNanos)
		require(submittedResultCount >= 0)
		require(acceptedResultCount >= 0)
		require(staleResultCount >= 0)
		require(clockUnverifiableResultCount >= 0)
		require(malformedResultCount >= 0)
		require(
			Math.addExact(
				acceptedResultCount,
				Math.addExact(
					staleResultCount,
					Math.addExact(clockUnverifiableResultCount, malformedResultCount),
				),
			) == submittedResultCount,
		)
		require(
			completeness == WifiCoverageCompleteness.COMPLETE ||
				staleResultCount > 0 || clockUnverifiableResultCount > 0 ||
				malformedResultCount > 0,
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

/** Identity-free binding to the exact admitted WAL unit and historical acquisition plan. */
internal data class WifiCapturedEvidenceBinding(
	val sourceEventId: SourceEventId,
	val sourceAdmissionOrdinal: Long,
	val walIntegrityIdentity: String,
	val payloadChecksum: String,
	val sourceDeliveryIdentity: SourceDeliveryIdentity,
	val deliveryUnitIndex: Int,
	val deliveryUnitCount: Int,
	val providerDedupKey: String?,
	val sourceSequence: Long,
	val planAttribution: PlanAttribution,
	val acquisitionPlanRevision: Long,
	val acquisitionPlanChecksum: String,
	val clockDomainId: String,
	val observedIntervalStartElapsedRealtimeNanos: Long,
	val observedElapsedRealtimeNanos: Long,
	val receivedElapsedRealtimeNanos: Long,
	val observedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val acquiredAtMs: Long,
	val qualityFlags: Long,
	val qualityConfidence: Float?,
) {
	init {
		require(sourceAdmissionOrdinal > 0L)
		require(LOWERCASE_SHA_256.matches(walIntegrityIdentity))
		require(LOWERCASE_SHA_256.matches(payloadChecksum))
		require(deliveryUnitCount > 0)
		require(deliveryUnitIndex in 0 until deliveryUnitCount)
		require(sourceSequence >= 0L)
		require(planAttribution == PlanAttribution.CAPTURED_REGISTRATION) {
			"Captured Wi-Fi evidence requires the exact applied registration"
		}
		require(acquisitionPlanRevision >= 0L)
		require(LOWERCASE_SHA_256.matches(acquisitionPlanChecksum))
		require(clockDomainId.isNotBlank())
		require(observedIntervalStartElapsedRealtimeNanos > 0L)
		require(observedElapsedRealtimeNanos >= observedIntervalStartElapsedRealtimeNanos)
		require(receivedElapsedRealtimeNanos >= observedElapsedRealtimeNanos)
		require(observedWallTimeMs >= 0L)
		require(wallTimeUncertaintyMs >= 0L)
		require(acquiredAtMs >= 0L)
		require(qualityConfidence == null || qualityConfidence in 0f..1f)
	}

	private companion object {
		val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
	}
}

/** Complete product effect used for exact replay and semantic no-op classification. */
internal data class WifiCapturedProductEffect(
	val evidenceBinding: WifiCapturedEvidenceBinding,
	val observedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val availability: WifiAvailability,
	val coverage: WifiCoverageEvidence,
	val aggregate: WifiIdentityFreeAggregate?,
) {
	init {
		require(observedWallTimeMs >= 0L)
		require(wallTimeUncertaintyMs >= 0L)
		require(availability == WifiAvailability.AVAILABLE)
		requireNotNull(aggregate)
		require(aggregate.observationCount > 0)
		require(coverage.acceptedResultCount == aggregate.observationCount) {
			"Wi-Fi product coverage must equal its identity-free aggregate count"
		}
	}
}

/**
 * Persisted prior state. Only [DirectAggregateOwner] may supply aggregate bytes to one new
 * coverage-only fact; a coverage-only fact cannot become another reuse owner.
 */
internal sealed interface WifiReusableFact {
	val reference: WifiAggregateFactReference
	val authority: WifiCaptureAuthority
	val productEffect: WifiCapturedProductEffect

	class DirectAggregateOwner private constructor(
		override val reference: WifiAggregateFactReference,
		override val authority: WifiCaptureAuthority,
		override val productEffect: WifiCapturedProductEffect,
	) : WifiReusableFact {
		init {
			require(productEffect.availability == WifiAvailability.AVAILABLE)
			requireNotNull(productEffect.aggregate)
			requireExactOwnerBinding(reference, authority, productEffect.evidenceBinding)
		}

		companion object {
			fun from(fact: WifiCapturedFact.Aggregate): DirectAggregateOwner =
				DirectAggregateOwner(
					reference = WifiAggregateFactReference(
						fact.mutation.identity,
						fact.mutation.semanticRevision,
					),
					authority = fact.authority,
					productEffect = fact.productEffect,
				)
		}
	}

	data class CoverageOnly(
		override val reference: WifiAggregateFactReference,
		override val authority: WifiCaptureAuthority,
		override val productEffect: WifiCapturedProductEffect,
		val directAggregateOwner: DirectAggregateOwner,
	) : WifiReusableFact {
		init {
			require(productEffect.availability == WifiAvailability.AVAILABLE)
			requireExactOwnerBinding(reference, authority, productEffect.evidenceBinding)
			val owner = directAggregateOwner
			require(owner.authority == authority)
			require(owner.productEffect.aggregate == productEffect.aggregate)
			require(
				productEffect.coverage.acceptedResultCount ==
					requireNotNull(owner.productEffect.aggregate).observationCount,
			)
			requireExactOwnerBinding(owner.reference, owner.authority, owner.productEffect.evidenceBinding)
		}
	}
}

internal sealed interface WifiCapturedFact {
	val mutation: WifiCapturedFactMutation
	val authority: WifiCaptureAuthority
	val evidenceBinding: WifiCapturedEvidenceBinding
	val observedWallTimeMs: Long
	val wallTimeUncertaintyMs: Long
	val availability: WifiAvailability
	val coverage: WifiCoverageEvidence

	data class Aggregate(
		override val mutation: WifiCapturedFactMutation,
		override val authority: WifiCaptureAuthority,
		override val evidenceBinding: WifiCapturedEvidenceBinding,
		override val observedWallTimeMs: Long,
		override val wallTimeUncertaintyMs: Long,
		override val availability: WifiAvailability,
		override val coverage: WifiCoverageEvidence,
		val aggregate: WifiIdentityFreeAggregate,
	) : WifiCapturedFact {
		init {
			requireWifiFactBinding(mutation, authority, evidenceBinding)
			require(observedWallTimeMs >= 0L)
			require(wallTimeUncertaintyMs >= 0L)
			require(availability == WifiAvailability.AVAILABLE)
			require(aggregate.observationCount > 0)
			require(coverage.acceptedResultCount == aggregate.observationCount) {
				"Wi-Fi aggregate count must equal accepted result coverage"
			}
		}
	}

	class CoverageOnly private constructor(
		override val mutation: WifiCapturedFactMutation,
		override val authority: WifiCaptureAuthority,
		override val evidenceBinding: WifiCapturedEvidenceBinding,
		override val observedWallTimeMs: Long,
		override val wallTimeUncertaintyMs: Long,
		override val availability: WifiAvailability,
		override val coverage: WifiCoverageEvidence,
		val reusesAggregate: WifiReusableFact.DirectAggregateOwner,
		observedAggregate: WifiIdentityFreeAggregate,
	) : WifiCapturedFact {
		init {
			requireWifiFactBinding(mutation, authority, evidenceBinding)
			require(observedWallTimeMs >= 0L)
			require(wallTimeUncertaintyMs >= 0L)
			require(availability == WifiAvailability.AVAILABLE)
			val owner = reusesAggregate
			require(owner.authority == authority)
			requireExactOwnerBinding(owner.reference, owner.authority, owner.productEffect.evidenceBinding)
			val ownerAggregate = requireNotNull(owner.productEffect.aggregate)
			require(observedAggregate == ownerAggregate) {
				"Wi-Fi coverage-only fact must exactly match its direct aggregate owner"
			}
			require(coverage.acceptedResultCount == observedAggregate.observationCount) {
				"Wi-Fi coverage-only count must equal the observed aggregate"
			}
		}

		companion object {
			fun fromExactAggregate(
				mutation: WifiCapturedFactMutation,
				authority: WifiCaptureAuthority,
				evidenceBinding: WifiCapturedEvidenceBinding,
				observedWallTimeMs: Long,
				wallTimeUncertaintyMs: Long,
				availability: WifiAvailability,
				coverage: WifiCoverageEvidence,
				observedAggregate: WifiIdentityFreeAggregate,
				reusesAggregate: WifiReusableFact.DirectAggregateOwner,
			): CoverageOnly = CoverageOnly(
				mutation = mutation,
				authority = authority,
				evidenceBinding = evidenceBinding,
				observedWallTimeMs = observedWallTimeMs,
				wallTimeUncertaintyMs = wallTimeUncertaintyMs,
				availability = availability,
				coverage = coverage,
				reusesAggregate = reusesAggregate,
				observedAggregate = observedAggregate,
			)
		}
	}
}

private fun requireExactOwnerBinding(
	reference: WifiAggregateFactReference,
	authority: WifiCaptureAuthority,
	evidence: WifiCapturedEvidenceBinding,
) {
	val identity = reference.identity
	require(identity.sourceEventId == evidence.sourceEventId)
	require(identity.sourceAdmissionOrdinal == evidence.sourceAdmissionOrdinal)
	require(identity.walIntegrityIdentity == evidence.walIntegrityIdentity)
	require(identity.payloadChecksum == evidence.payloadChecksum)
	require(identity.sourceDeliveryIdentity == evidence.sourceDeliveryIdentity)
	require(identity.deliveryUnitIndex == evidence.deliveryUnitIndex)
	require(identity.deliveryUnitIndex < evidence.deliveryUnitCount)
	require(identity.logicalTrackingId == authority.logicalTrackingId)
	require(identity.serviceRunId == authority.serviceRunId)
	require(identity.sessionSegmentId == authority.sessionSegmentId)
	require(identity.sessionManifestRevision == authority.sessionManifestRevision)
	require(identity.collectedDataEpoch == authority.collectedDataEpoch)
	require(identity.scopeDeletionGeneration == authority.scopeDeletionGeneration)
	require(evidence.acquisitionPlanRevision == authority.configurationRevision)
	require(evidence.acquisitionPlanChecksum == authority.serializedAcquisitionPlan.payloadChecksum)
	require(evidence.clockDomainId == authority.clockDomainId)
}

private fun requireWifiFactBinding(
	mutation: WifiCapturedFactMutation,
	authority: WifiCaptureAuthority,
	evidence: WifiCapturedEvidenceBinding,
) {
	val identity = mutation.identity
	require(identity.sourceEventId == evidence.sourceEventId)
	require(identity.sourceAdmissionOrdinal == evidence.sourceAdmissionOrdinal)
	require(identity.walIntegrityIdentity == evidence.walIntegrityIdentity)
	require(identity.payloadChecksum == evidence.payloadChecksum)
	require(identity.sourceDeliveryIdentity == evidence.sourceDeliveryIdentity)
	require(identity.deliveryUnitIndex == evidence.deliveryUnitIndex)
	require(identity.deliveryUnitIndex < evidence.deliveryUnitCount)
	require(identity.logicalTrackingId == authority.logicalTrackingId)
	require(identity.serviceRunId == authority.serviceRunId)
	require(identity.sessionSegmentId == authority.sessionSegmentId)
	require(identity.sessionManifestRevision == authority.sessionManifestRevision)
	require(identity.collectedDataEpoch == authority.collectedDataEpoch)
	require(identity.scopeDeletionGeneration == authority.scopeDeletionGeneration)
	require(evidence.acquisitionPlanRevision == authority.configurationRevision)
	require(evidence.acquisitionPlanChecksum == authority.serializedAcquisitionPlan.payloadChecksum)
	require(evidence.clockDomainId == authority.clockDomainId)
}

internal val WifiCapturedFact.productEffect: WifiCapturedProductEffect
	get() = WifiCapturedProductEffect(
		evidenceBinding = evidenceBinding,
		observedWallTimeMs = observedWallTimeMs,
		wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		availability = availability,
		coverage = coverage,
		aggregate = when (this) {
			is WifiCapturedFact.Aggregate -> aggregate
			is WifiCapturedFact.CoverageOnly -> reusesAggregate.productEffect.aggregate
		},
	)

internal fun WifiCapturedFact.toReusableFact(): WifiReusableFact {
	val reference = WifiAggregateFactReference(mutation.identity, mutation.semanticRevision)
	return when (this) {
		is WifiCapturedFact.Aggregate -> WifiReusableFact.DirectAggregateOwner.from(this)
		is WifiCapturedFact.CoverageOnly -> WifiReusableFact.CoverageOnly(
			reference = reference,
			authority = authority,
			productEffect = productEffect,
			directAggregateOwner = reusesAggregate,
		)
	}
}
