package com.adsamcik.tracker.tracker.source.cell

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
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

internal enum class CellAvailability { AVAILABLE, CONFIRMED_EMPTY }
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
	val observedSubscriptionCount: Int,
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
		require(observedSubscriptionCount >= 0)
		require(
			when (subscriptionCompleteness) {
				CellSubscriptionCompleteness.COMPLETE ->
					expectedSubscriptionCount != null &&
						observedSubscriptionCount == expectedSubscriptionCount
				CellSubscriptionCompleteness.PARTIAL ->
					expectedSubscriptionCount != null &&
						observedSubscriptionCount < expectedSubscriptionCount
				CellSubscriptionCompleteness.UNKNOWN -> expectedSubscriptionCount == null
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

internal sealed interface CellCapturedFact {
	val mutation: CellCapturedFactMutation
	val authority: CellCaptureAuthority
	val observedWallTimeMs: Long
	val wallTimeUncertaintyMs: Long
	val availability: CellAvailability
	val coverage: CellCoverageEvidence

	data class Aggregate(
		override val mutation: CellCapturedFactMutation,
		override val authority: CellCaptureAuthority,
		override val observedWallTimeMs: Long,
		override val wallTimeUncertaintyMs: Long,
		override val availability: CellAvailability,
		override val coverage: CellCoverageEvidence,
		val aggregate: CellIdentityFreeAggregate,
	) : CellCapturedFact {
		init {
			validateCellFactBinding(mutation, authority, coverage)
			require(observedWallTimeMs >= 0L)
			require(wallTimeUncertaintyMs >= 0L)
			require(availability == CellAvailability.AVAILABLE)
			require(coverage.acceptedChildCount == aggregate.observationCount)
		}
	}

	data class CoverageOnly(
		override val mutation: CellCapturedFactMutation,
		override val authority: CellCaptureAuthority,
		override val observedWallTimeMs: Long,
		override val wallTimeUncertaintyMs: Long,
		override val availability: CellAvailability,
		override val coverage: CellCoverageEvidence,
		val reusesAggregate: CellAggregateFactReference?,
	) : CellCapturedFact {
		init {
			validateCellFactBinding(mutation, authority, coverage)
			require(observedWallTimeMs >= 0L)
			require(wallTimeUncertaintyMs >= 0L)
			require((availability == CellAvailability.AVAILABLE) == (reusesAggregate != null))
			reusesAggregate?.let { aggregateReference ->
				validateCellFactIdentityBinding(aggregateReference.identity, authority)
				if (aggregateReference.identity == mutation.identity) {
					require(aggregateReference.semanticRevision < mutation.semanticRevision)
				}
			}
			require(
				availability != CellAvailability.CONFIRMED_EMPTY ||
					(coverage.submittedChildCount == 0 && coverage.acceptedChildCount == 0),
			)
		}
	}
}

/** Exact effective state used for replay, one-hop reuse, and correction-base validation. */
internal data class CellReusableFact(
	val reference: CellAggregateFactReference,
	val authority: CellCaptureAuthority,
	val availability: CellAvailability,
	val coverage: CellCoverageEvidence,
	val aggregate: CellIdentityFreeAggregate?,
	val aggregateOwnerReference: CellAggregateFactReference?,
) {
	init {
		require((availability == CellAvailability.AVAILABLE) == (aggregate != null))
		require((aggregate != null) == (aggregateOwnerReference != null))
		validateCellFactIdentityBinding(reference.identity, authority)
		aggregateOwnerReference?.let { validateCellFactIdentityBinding(it.identity, authority) }
	}
}

private fun validateCellFactBinding(
	mutation: CellCapturedFactMutation,
	authority: CellCaptureAuthority,
	coverage: CellCoverageEvidence,
) {
	validateCellFactIdentityBinding(mutation.identity, authority)
	require(coverage.providerIntervalStartElapsedRealtimeNanos in authority.temporalAuthority)
	require(coverage.providerIntervalEndElapsedRealtimeNanos in authority.temporalAuthority)
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
