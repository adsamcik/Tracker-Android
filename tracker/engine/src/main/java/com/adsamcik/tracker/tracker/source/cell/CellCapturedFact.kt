package com.adsamcik.tracker.tracker.source.cell

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.time.ZoneId

internal data class CellProviderTimeInterval(
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

/** Every observed-time authority that must cover one Cell child independently. */
internal data class CellCaptureTemporalAuthority(
	val providerAcceptance: CellProviderTimeInterval,
	val authorizationEffect: CellProviderTimeInterval,
	val consentEffect: CellProviderTimeInterval,
	val sessionRunEffect: CellProviderTimeInterval,
	val deletionEffect: CellProviderTimeInterval,
) {
	val capturedStartInclusiveNanos: Long = maxOf(
		providerAcceptance.startInclusiveNanos,
		authorizationEffect.startInclusiveNanos,
		consentEffect.startInclusiveNanos,
		sessionRunEffect.startInclusiveNanos,
		deletionEffect.startInclusiveNanos,
	)
	val capturedEndExclusiveNanos: Long = minOf(
		providerAcceptance.endExclusiveNanos,
		authorizationEffect.endExclusiveNanos,
		consentEffect.endExclusiveNanos,
		sessionRunEffect.endExclusiveNanos,
		deletionEffect.endExclusiveNanos,
	)

	init {
		require(capturedEndExclusiveNanos > capturedStartInclusiveNanos) {
			"Cell capture authority must have a non-empty observed-time intersection"
		}
	}

	operator fun contains(providerTimeNanos: Long): Boolean =
		providerTimeNanos >= capturedStartInclusiveNanos &&
			providerTimeNanos < capturedEndExclusiveNanos

	/**
	 * Aggregate ownership may be shared only after every historical boundary is immutable. An open
	 * component can still settle later even when another finite component already bounds the
	 * effective intersection; that settlement advances the owner's revision and would strand a
	 * coverage-only fact at the obsolete revision.
	 */
	val isImmutableForAggregateReuse: Boolean
		get() = providerAcceptance.endExclusiveNanos != Long.MAX_VALUE &&
			authorizationEffect.endExclusiveNanos != Long.MAX_VALUE &&
			consentEffect.endExclusiveNanos != Long.MAX_VALUE &&
			sessionRunEffect.endExclusiveNanos != Long.MAX_VALUE &&
			deletionEffect.endExclusiveNanos != Long.MAX_VALUE
}

/** Immutable authority copied by the adapter onto each callback child before classification. */
internal data class CellChildAuthority(
	val sourceInstanceId: SourceInstanceId,
	val registrationGeneration: Long,
	val configurationRevision: Long,
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val sessionManifestRevision: Long,
	val lifecycleLeaseGeneration: Long,
	val collectedDataEpoch: Long,
	val scopeDeletionGeneration: Long,
	val clockDomainId: String,
) {
	init {
		require(registrationGeneration > 0L)
		require(configurationRevision >= 0L)
		require(authorizationRevision > 0L)
		require(AUTHORIZATION_FINGERPRINT.matches(authorizationFingerprint))
		require(sourcePolicyRevision > 0L)
		require(captureConsentEpoch >= 0L)
		require(sessionManifestRevision > 0L)
		require(lifecycleLeaseGeneration > 0L)
		require(collectedDataEpoch >= 0L)
		require(scopeDeletionGeneration >= 0L)
		require(clockDomainId.isNotBlank())
	}
}

/** Exact session, broker, consent, deletion, clock, and civil-day authority for Cell facts. */
internal data class CellCaptureAuthority(
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
	val scopeDeletionGeneration: Long,
	val clockDomainId: String,
	val zoneId: String,
	val structuralEpochDay: Long,
	val temporalAuthority: CellCaptureTemporalAuthority,
	val maximumObservationAgeNanos: Long,
) {
	val childAuthority = CellChildAuthority(
		sourceInstanceId = sourceInstanceId,
		registrationGeneration = registrationGeneration,
		configurationRevision = configurationRevision,
		authorizationRevision = authorizationRevision,
		authorizationFingerprint = authorizationFingerprint,
		sourcePolicyRevision = sourcePolicyRevision,
		captureConsentEpoch = captureConsentEpoch,
		sessionManifestRevision = sessionManifestRevision,
		lifecycleLeaseGeneration = lifecycleLeaseGeneration,
		collectedDataEpoch = collectedDataEpoch,
		scopeDeletionGeneration = scopeDeletionGeneration,
		clockDomainId = clockDomainId,
	)

	init {
		require(sessionSegmentId > 0L)
		require(SourceKind.CELL in capturedSources)
		require(capturedSources.intersect(controlSources).isEmpty())
		require(physicalConfigurationFingerprint.isNotBlank())
		require(purposeEligibilityMask and SourceBrokerPurpose.ALL_MASK == purposeEligibilityMask)
		require(purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L)
		require(zoneId.isNotBlank() && runCatching { ZoneId.of(zoneId) }.isSuccess)
		require(maximumObservationAgeNanos >= 0L)
	}
}

/** Stable product identity. Physical registration state is authority, not source-native identity. */
internal data class CellCapturedFactIdentity(
	val sourceDeliveryIdentity: SourceDeliveryIdentity,
	val logicalTrackingId: LogicalTrackingId,
	val serviceRunId: ServiceRunId,
	val sessionSegmentId: Long,
	val sessionManifestRevision: Long,
	val collectedDataEpoch: Long,
	val scopeDeletionGeneration: Long,
) {
	init {
		require(sessionSegmentId > 0L)
		require(sessionManifestRevision > 0L)
		require(collectedDataEpoch >= 0L)
		require(scopeDeletionGeneration >= 0L)
	}
}

/** One strict linear correction chain for a source-native Cell delivery. */
internal data class CellCapturedFactMutation(
	val identity: CellCapturedFactIdentity,
	val semanticRevision: Long,
	val supersedesSemanticRevision: Long?,
) {
	init {
		require(semanticRevision > 0L)
		if (semanticRevision == 1L) {
			require(supersedesSemanticRevision == null)
		} else {
			require(supersedesSemanticRevision == semanticRevision - 1L)
		}
	}
}

internal enum class CellRadioTechnology { GSM, CDMA, WCDMA, TDSCDMA, LTE, NR }

internal data class CellTechnologyMix(val counts: Map<CellRadioTechnology, Int>) {
	init {
		require(counts.keys.all { it in CellRadioTechnology.entries })
		require(counts.values.all { it > 0 })
	}

	val totalCount: Int
		get() = counts.values.fold(0) { total, count -> Math.addExact(total, count) }
}

/** Android-normalized quality levels; no technology-specific raw radio identifiers are retained. */
internal data class CellSignalQualityDistribution(
	val unknownCount: Int,
	val noneOrUnknownCount: Int,
	val poorCount: Int,
	val moderateCount: Int,
	val goodCount: Int,
	val greatCount: Int,
) {
	init {
		require(
			listOf(
				unknownCount,
				noneOrUnknownCount,
				poorCount,
				moderateCount,
				goodCount,
				greatCount,
			).all { it >= 0 },
		)
	}

	val totalCount: Int
		get() = listOf(
			unknownCount,
			noneOrUnknownCount,
			poorCount,
			moderateCount,
			goodCount,
			greatCount,
		).fold(0) { total, count -> Math.addExact(total, count) }

	val weakCount: Int
		get() = Math.addExact(noneOrUnknownCount, poorCount)
	val knownCount: Int
		get() = totalCount - unknownCount
}

/** A fact-local input for composing weak periods without inventing quality for unknown samples. */
internal data class CellWeakPeriodEvidence(
	val weakObservationCount: Int,
	val knownQualityObservationCount: Int,
	val allKnownQualityIsWeak: Boolean,
) {
	init {
		require(weakObservationCount >= 0)
		require(knownQualityObservationCount >= weakObservationCount)
		require(allKnownQualityIsWeak == (
			knownQualityObservationCount > 0 && weakObservationCount == knownQualityObservationCount
		))
	}
}

/** Identity-free Cell product aggregate. */
internal data class CellIdentityFreeAggregate(
	val observationCount: Int,
	val registeredObservationCount: Int,
	val technologyMix: CellTechnologyMix,
	val signalQuality: CellSignalQualityDistribution,
	val weakPeriod: CellWeakPeriodEvidence,
) {
	init {
		require(observationCount > 0)
		require(registeredObservationCount in 0..observationCount)
		require(technologyMix.totalCount == observationCount)
		require(signalQuality.totalCount == observationCount)
		require(weakPeriod.weakObservationCount == signalQuality.weakCount)
		require(weakPeriod.knownQualityObservationCount == signalQuality.knownCount)
	}
}

internal enum class CellAvailability { AVAILABLE }
internal enum class CellSubscriptionCompleteness { COMPLETE, PARTIAL, UNKNOWN }
internal enum class CellChildCompleteness { COMPLETE, PARTIAL }

/** Compact counts and provider-time bounds; stable SIM or tower identity cannot enter this type. */
internal data class CellCoverageEvidence(
	val providerIntervalStartElapsedRealtimeNanos: Long,
	val providerIntervalEndElapsedRealtimeNanos: Long,
	val submittedChildCount: Int,
	val acceptedChildCount: Int,
	val staleChildCount: Int,
	val futureTimeChildCount: Int,
	val missingTimeChildCount: Int,
	val clockUnverifiableChildCount: Int,
	val authorityMismatchChildCount: Int,
	val unsupportedTechnologyChildCount: Int,
	val expectedSubscriptionCount: Int?,
	val observedSubscriptionCount: Int?,
	val subscriptionCompleteness: CellSubscriptionCompleteness,
	val childCompleteness: CellChildCompleteness,
) {
	init {
		require(providerIntervalStartElapsedRealtimeNanos > 0L)
		require(providerIntervalEndElapsedRealtimeNanos >= providerIntervalStartElapsedRealtimeNanos)
		val counts = listOf(
			acceptedChildCount,
			staleChildCount,
			futureTimeChildCount,
			missingTimeChildCount,
			clockUnverifiableChildCount,
			authorityMismatchChildCount,
			unsupportedTechnologyChildCount,
		)
		require(submittedChildCount >= 0 && counts.all { it >= 0 })
		require(counts.fold(0) { total, count -> Math.addExact(total, count) } == submittedChildCount)
		require(expectedSubscriptionCount == null || expectedSubscriptionCount >= 0)
		require(observedSubscriptionCount == null || observedSubscriptionCount >= 0)
		require(
			when (subscriptionCompleteness) {
				CellSubscriptionCompleteness.COMPLETE ->
					expectedSubscriptionCount != null &&
						observedSubscriptionCount != null &&
						observedSubscriptionCount == expectedSubscriptionCount
				CellSubscriptionCompleteness.PARTIAL ->
					expectedSubscriptionCount != null &&
						observedSubscriptionCount != null &&
						observedSubscriptionCount < expectedSubscriptionCount
				CellSubscriptionCompleteness.UNKNOWN ->
					expectedSubscriptionCount == null && observedSubscriptionCount == null
			},
		)
		require(
			(childCompleteness == CellChildCompleteness.COMPLETE) ==
				(submittedChildCount == acceptedChildCount),
		)
	}

	val isPartialMultiSim: Boolean
		get() = subscriptionCompleteness == CellSubscriptionCompleteness.PARTIAL
}

internal data class CellAggregateFactReference(
	val identity: CellCapturedFactIdentity,
	val semanticRevision: Long,
) {
	init {
		require(semanticRevision > 0L)
	}
}

/** Immutable WAL and canonical decoded-provider binding for collision-safe replay. */
internal data class CellCapturedEvidenceBinding(
	val sourceEventId: SourceEventId,
	val sourceKind: SourceKind,
	val sourceDeliveryIdentity: SourceDeliveryIdentity,
	val sourceAdmissionOrdinal: Long,
	val walIntegrityIdentity: String,
	val payloadChecksum: String,
	val deliveryUnitIndex: Int,
	val deliveryUnitCount: Int,
	val sourceSequence: Long,
	val planAttribution: PlanAttribution,
	val payloadVersion: Int,
	val canonicalProviderSemanticsDigest: String,
	val capturedAuthority: CellCaptureAuthority,
	val observedIntervalStartElapsedRealtimeNanos: Long,
	val observedElapsedRealtimeNanos: Long,
	val receivedElapsedRealtimeNanos: Long,
	val observedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val acquiredAtMs: Long,
	val createdAtMs: Long,
	val qualityFlags: Long,
	val qualityConfidence: Float?,
) {
	init {
		require(sourceKind == SourceKind.CELL)
		require(sourceAdmissionOrdinal > 0L)
		require(LOWERCASE_SHA_256.matches(walIntegrityIdentity))
		require(LOWERCASE_SHA_256.matches(payloadChecksum))
		require(deliveryUnitCount > 0)
		require(deliveryUnitIndex in 0 until deliveryUnitCount)
		require(sourceSequence >= 0L)
		require(planAttribution == PlanAttribution.CAPTURED_REGISTRATION)
		require(payloadVersion > 0)
		require(LOWERCASE_SHA_256.matches(canonicalProviderSemanticsDigest))
		require(canonicalProviderSemanticsDigest == sourceDeliveryIdentity.value)
		require(observedIntervalStartElapsedRealtimeNanos > 0L)
		require(observedElapsedRealtimeNanos >= observedIntervalStartElapsedRealtimeNanos)
		require(receivedElapsedRealtimeNanos >= observedElapsedRealtimeNanos)
		require(observedWallTimeMs >= 0L)
		require(wallTimeUncertaintyMs >= 0L)
		require(acquiredAtMs >= 0L)
		require(createdAtMs >= 0L)
		require(qualityConfidence == null || qualityConfidence in 0f..1f)
	}
}

/** Complete effective fact value; exact replay must match every field, not only its aggregate. */
internal data class CellCapturedProductEffect(
	val evidenceBinding: CellCapturedEvidenceBinding,
	val observedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val availability: CellAvailability,
	val coverage: CellCoverageEvidence,
	val aggregate: CellIdentityFreeAggregate,
) {
	init {
		require(observedWallTimeMs >= 0L)
		require(wallTimeUncertaintyMs >= 0L)
		require(availability == CellAvailability.AVAILABLE)
		require(coverage.acceptedChildCount == aggregate.observationCount)
	}
}

internal sealed interface CellCapturedFact {
	val mutation: CellCapturedFactMutation
	val authority: CellCaptureAuthority
	val evidenceBinding: CellCapturedEvidenceBinding
	val observedWallTimeMs: Long
	val wallTimeUncertaintyMs: Long
	val availability: CellAvailability
	val coverage: CellCoverageEvidence

	data class Aggregate(
		override val mutation: CellCapturedFactMutation,
		override val authority: CellCaptureAuthority,
		override val evidenceBinding: CellCapturedEvidenceBinding,
		override val observedWallTimeMs: Long,
		override val wallTimeUncertaintyMs: Long,
		override val availability: CellAvailability,
		override val coverage: CellCoverageEvidence,
		val aggregate: CellIdentityFreeAggregate,
	) : CellCapturedFact {
		init {
			validateCellFactBinding(mutation, authority, evidenceBinding, coverage)
			require(observedWallTimeMs >= 0L)
			require(wallTimeUncertaintyMs >= 0L)
			require(availability == CellAvailability.AVAILABLE)
			require(coverage.acceptedChildCount == aggregate.observationCount)
		}
	}

	class CoverageOnly private constructor(
		override val mutation: CellCapturedFactMutation,
		override val authority: CellCaptureAuthority,
		override val evidenceBinding: CellCapturedEvidenceBinding,
		override val observedWallTimeMs: Long,
		override val wallTimeUncertaintyMs: Long,
		override val availability: CellAvailability,
		override val coverage: CellCoverageEvidence,
		val reusesAggregate: CellReusableFact.DirectAggregateOwner,
	) : CellCapturedFact {
		init {
			validateCellFactBinding(mutation, authority, evidenceBinding, coverage)
			require(observedWallTimeMs >= 0L)
			require(wallTimeUncertaintyMs >= 0L)
			require(availability == CellAvailability.AVAILABLE)
			require(authority.canReuseAggregateFrom(reusesAggregate.authority))
			require(reusesAggregate.authority.temporalAuthority.isImmutableForAggregateReuse)
			require(reusesAggregate.productEffect.aggregate.observationCount > 0)
			require(
				coverage.acceptedChildCount ==
					reusesAggregate.productEffect.aggregate.observationCount,
			)
		}

		companion object {
			fun create(
				mutation: CellCapturedFactMutation,
				authority: CellCaptureAuthority,
				evidenceBinding: CellCapturedEvidenceBinding,
				observedWallTimeMs: Long,
				wallTimeUncertaintyMs: Long,
				availability: CellAvailability,
				coverage: CellCoverageEvidence,
				aggregate: CellIdentityFreeAggregate,
				reusesAggregate: CellReusableFact.DirectAggregateOwner,
			): CoverageOnly {
				require(reusesAggregate.productEffect.aggregate == aggregate)
				return CoverageOnly(
					mutation = mutation,
					authority = authority,
					evidenceBinding = evidenceBinding,
					observedWallTimeMs = observedWallTimeMs,
					wallTimeUncertaintyMs = wallTimeUncertaintyMs,
					availability = availability,
					coverage = coverage,
					reusesAggregate = reusesAggregate,
				)
			}
		}
	}
}

/** Persisted prior state. A coverage-only fact can never become a second-hop aggregate owner. */
internal sealed interface CellReusableFact {
	val reference: CellAggregateFactReference
	val authority: CellCaptureAuthority
	val productEffect: CellCapturedProductEffect

	class DirectAggregateOwner private constructor(
		override val reference: CellAggregateFactReference,
		override val authority: CellCaptureAuthority,
		override val productEffect: CellCapturedProductEffect,
	) : CellReusableFact {
		init {
			validateCellFactIdentityBinding(reference.identity, authority)
			require(
				reference.identity.sourceDeliveryIdentity ==
					productEffect.evidenceBinding.sourceDeliveryIdentity,
			)
			require(productEffect.evidenceBinding.capturedAuthority == authority)
			require(
				productEffect.coverage.acceptedChildCount ==
					productEffect.aggregate.observationCount,
			)
		}

		companion object {
			fun from(fact: CellCapturedFact.Aggregate) = DirectAggregateOwner(
				reference = CellAggregateFactReference(
					fact.mutation.identity,
					fact.mutation.semanticRevision,
				),
				authority = fact.authority,
				productEffect = fact.productEffect,
			)
		}
	}

	data class CoverageOnly(
		override val reference: CellAggregateFactReference,
		override val authority: CellCaptureAuthority,
		override val productEffect: CellCapturedProductEffect,
		val directAggregateOwner: DirectAggregateOwner,
	) : CellReusableFact {
		init {
			// Historical rows may reference an owner revision whose formerly open authority has
			// since settled. The writer authenticates that exact revision through the complete
			// current owner lineage before reconstructing this read-only form. New facts still use
			// CellCapturedFact.CoverageOnly, which requires an immutable owner.
			validateCellFactIdentityBinding(reference.identity, authority)
			require(
				reference.identity.sourceDeliveryIdentity ==
					productEffect.evidenceBinding.sourceDeliveryIdentity,
			)
			require(productEffect.evidenceBinding.capturedAuthority == authority)
			require(authority.canReuseAggregateFrom(directAggregateOwner.authority))
			require(productEffect.aggregate == directAggregateOwner.productEffect.aggregate)
			require(
				productEffect.coverage.acceptedChildCount ==
					productEffect.aggregate.observationCount,
			)
		}
	}
}

private fun validateCellFactBinding(
	mutation: CellCapturedFactMutation,
	authority: CellCaptureAuthority,
	evidenceBinding: CellCapturedEvidenceBinding,
	coverage: CellCoverageEvidence,
) {
	validateCellFactIdentityBinding(mutation.identity, authority)
	require(mutation.identity.sourceDeliveryIdentity == evidenceBinding.sourceDeliveryIdentity)
	require(evidenceBinding.capturedAuthority == authority)
	require(coverage.providerIntervalStartElapsedRealtimeNanos in authority.temporalAuthority)
	require(coverage.providerIntervalEndElapsedRealtimeNanos in authority.temporalAuthority)
}

/** Physical provider/auth changes may reuse bytes only inside the same exact product authority. */
internal fun CellCaptureAuthority.canReuseAggregateFrom(other: CellCaptureAuthority): Boolean =
	logicalTrackingId == other.logicalTrackingId && serviceRunId == other.serviceRunId &&
		sessionSegmentId == other.sessionSegmentId && capturedSources == other.capturedSources &&
		controlSources == other.controlSources && purposeEligibilityMask == other.purposeEligibilityMask &&
		captureConsentEpoch == other.captureConsentEpoch &&
		collectedDataEpoch == other.collectedDataEpoch &&
		scopeDeletionGeneration == other.scopeDeletionGeneration && zoneId == other.zoneId &&
		structuralEpochDay == other.structuralEpochDay

/**
 * A persisted authority may be corrected only by closing bounds that were genuinely open when the
 * earlier revision was written. Starts and every non-temporal field remain immutable; a finite
 * bound can never move or reopen.
 */
internal fun CellCaptureAuthority.isExactSettlementOf(previous: CellCaptureAuthority): Boolean =
	previous.copy(temporalAuthority = temporalAuthority) == this &&
		temporalAuthority.providerAcceptance.isExactSettlementOf(
			previous.temporalAuthority.providerAcceptance,
		) &&
		temporalAuthority.authorizationEffect.isExactSettlementOf(
			previous.temporalAuthority.authorizationEffect,
		) &&
		temporalAuthority.consentEffect.isExactSettlementOf(
			previous.temporalAuthority.consentEffect,
		) &&
		temporalAuthority.sessionRunEffect.isExactSettlementOf(
			previous.temporalAuthority.sessionRunEffect,
		) &&
		temporalAuthority.deletionEffect.isExactSettlementOf(
			previous.temporalAuthority.deletionEffect,
		)

private fun CellProviderTimeInterval.isExactSettlementOf(
	previous: CellProviderTimeInterval,
): Boolean = startInclusiveNanos == previous.startInclusiveNanos &&
	(endExclusiveNanos == previous.endExclusiveNanos ||
		(previous.endExclusiveNanos == Long.MAX_VALUE && endExclusiveNanos < Long.MAX_VALUE))

internal val CellCapturedFact.productEffect: CellCapturedProductEffect
	get() = CellCapturedProductEffect(
		evidenceBinding = evidenceBinding,
		observedWallTimeMs = observedWallTimeMs,
		wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		availability = availability,
		coverage = coverage,
		aggregate = when (this) {
			is CellCapturedFact.Aggregate -> aggregate
			is CellCapturedFact.CoverageOnly -> reusesAggregate.productEffect.aggregate
		},
	)

internal fun CellCapturedFact.toReusableFact(): CellReusableFact = when (this) {
	is CellCapturedFact.Aggregate -> CellReusableFact.DirectAggregateOwner.from(this)
	is CellCapturedFact.CoverageOnly -> CellReusableFact.CoverageOnly(
		reference = CellAggregateFactReference(mutation.identity, mutation.semanticRevision),
		authority = authority,
		productEffect = productEffect,
		directAggregateOwner = reusesAggregate,
	)
}

private fun validateCellFactIdentityBinding(
	identity: CellCapturedFactIdentity,
	authority: CellCaptureAuthority,
) {
	require(identity.logicalTrackingId == authority.logicalTrackingId)
	require(identity.serviceRunId == authority.serviceRunId)
	require(identity.sessionSegmentId == authority.sessionSegmentId)
	require(identity.sessionManifestRevision == authority.sessionManifestRevision)
	require(identity.collectedDataEpoch == authority.collectedDataEpoch)
	require(identity.scopeDeletionGeneration == authority.scopeDeletionGeneration)
}

private val AUTHORIZATION_FINGERPRINT = Regex("[0-9a-f]{64}")
private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
