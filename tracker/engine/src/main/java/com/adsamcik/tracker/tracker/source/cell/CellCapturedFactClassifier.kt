package com.adsamcik.tracker.tracker.source.cell

import com.adsamcik.tracker.shared.base.database.data.CellWalCaptureAuthority
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierFailure
import com.adsamcik.tracker.shared.base.database.data.CellWalChildQualification
import com.adsamcik.tracker.shared.base.database.data.CellWalProviderInterval
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind

internal enum class CellObservationOrigin {
	/** Durable WAL proves a provider callback, but v1 does not retain change-vs-refresh origin. */
	PROVIDER_CALLBACK,
	CHANGE_CALLBACK,
	REFRESH_RESULT_CALLBACK,
	GET_ALL_CELL_INFO_CACHE,
	REFRESH_REQUEST,
	RECEIPT_ONLY,
}

internal enum class CellProviderOutcome {
	DELIVERED,
	ABSENT,
	FAILED,
	PERMISSION_LIMITED,
	OS_LIMITED,
}

/** Exact admitted WAL identity and captured authority for one decoded Cell provider delivery. */
internal data class CellWalObservationEvidence(
	val sourceEventId: SourceEventId,
	val sourceKind: SourceKind,
	val sourceAdmissionOrdinal: Long,
	val walIntegrityIdentity: String,
	val providerDedupKey: String?,
	val sourceDeliveryIdentity: SourceDeliveryIdentity?,
	val payloadChecksum: String,
	val deliveryUnitIndex: Int,
	val deliveryUnitCount: Int,
	val logicalTrackingId: LogicalTrackingId,
	val serviceRunId: ServiceRunId,
	val sourceInstanceId: SourceInstanceId,
	val registrationGeneration: Long,
	val configurationRevision: Long,
	val physicalConfigurationFingerprint: String,
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
	val purposeEligibilityMask: Long,
	val sourceSequence: Long,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val sessionManifestRevision: Long,
	val lifecycleLeaseGeneration: Long,
	val capturedCollectedDataEpoch: Long,
	val activityAutomationEpoch: Long?,
	val planAttribution: PlanAttribution,
	val clock: CellDurableClockEvidence,
	val receivedWallTimeMs: Long?,
	val acquiredAtMs: Long,
	val qualityFlags: Long,
	val qualityConfidence: Float?,
	val payloadVersion: Int,
	val payloadBytes: List<Byte>,
	val createdAtMs: Long,
	val capturedAuthority: CellCaptureAuthority,
) {
	fun calculatedWalIntegrityIdentity(): String = toWalEntity().calculatedIntegrityIdentity()

	internal fun toWalEntity(): SourceEventWalEntity = SourceEventWalEntity(
		admissionOrdinal = sourceAdmissionOrdinal,
		eventId = sourceEventId.value,
		providerDedupKey = providerDedupKey,
		deliveryIdentity = sourceDeliveryIdentity?.value,
		deliveryUnitIndex = deliveryUnitIndex,
		deliveryUnitCount = deliveryUnitCount,
		logicalTrackingId = logicalTrackingId.value,
		serviceRunId = serviceRunId.value,
		sourceKind = sourceKind.stableCode,
		sourceInstanceId = sourceInstanceId.value,
		registrationGeneration = registrationGeneration,
		physicalConfigurationFingerprint = physicalConfigurationFingerprint,
		authorizationRevision = authorizationRevision,
		authorizationPurposeEligibilityMask = purposeEligibilityMask,
		authorizationFingerprint = authorizationFingerprint,
		sourceSequence = sourceSequence,
		configRevision = configurationRevision,
		planAttribution = planAttribution.ordinal,
		clockDomainId = clock.clockDomainId,
		observedElapsedNanos = clock.observedElapsedRealtimeNanos,
		observedIntervalStartNanos = clock.observedIntervalStartElapsedRealtimeNanos,
		receivedElapsedNanos = clock.receivedElapsedRealtimeNanos,
		receivedWallTimeMs = receivedWallTimeMs,
		wallTimeMs = clock.observedWallTimeMs,
		wallTimeUncertaintyMs = clock.wallTimeUncertaintyMs,
		capturedCollectedDataEpoch = capturedCollectedDataEpoch,
		activityAutomationEpoch = activityAutomationEpoch,
		sourcePolicyRevision = sourcePolicyRevision,
		captureConsentEpoch = captureConsentEpoch,
		sessionManifestRevision = sessionManifestRevision,
		lifecycleLeaseGeneration = lifecycleLeaseGeneration,
		acquiredAtMs = acquiredAtMs,
		qualityFlags = qualityFlags,
		qualityConfidence = qualityConfidence,
		payloadVersion = payloadVersion,
		payload = payloadBytes.toByteArray(),
		payloadChecksum = payloadChecksum,
		integrityIdentity = walIntegrityIdentity,
		createdAtMs = createdAtMs,
	)
}

internal data class CellDurableClockEvidence(
	val clockDomainId: String,
	val observedIntervalStartElapsedRealtimeNanos: Long,
	val observedElapsedRealtimeNanos: Long,
	val receivedElapsedRealtimeNanos: Long,
	val observedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
)

/** Count-only proof that distinct provider groups were observed, without SIM or slot identity. */
internal data class CellSubscriptionGroupProof(
	val expectedGroupCount: Int,
	val observedGroupSizeHistogram: Map<Int, Int>,
) {
	init {
		require(expectedGroupCount >= 0)
		require(observedGroupSizeHistogram.size <= MAX_CELL_SUBSCRIPTIONS_PER_DELIVERY)
		require(observedGroupSizeHistogram.all { (groupSize, frequency) ->
			groupSize > 0 && frequency > 0
		})
	}

	val observedGroupCount: Long
		get() = observedGroupSizeHistogram.values.sumOf { it.toLong() }

	val representedChildCount: Long
		get() = observedGroupSizeHistogram.entries.sumOf { (groupSize, frequency) ->
			groupSize.toLong() * frequency.toLong()
		}
}

/**
 * One source-local classification request. Subscription coverage is count-only: neither Android
 * subscription IDs nor stable slot ordinals are accepted by this contract.
 */
internal data class CellObservationInput(
	val origin: CellObservationOrigin,
	val outcome: CellProviderOutcome,
	val walEvidence: CellWalObservationEvidence?,
)

internal enum class CellAbsentReason { PROVIDER_ABSENT, OPERATIONAL_ONLY }
internal enum class CellFailureReason { PROVIDER_FAILED }
internal enum class CellClockUnverifiableReason {
	DELIVERY_CLOCK_DOMAIN_MISSING_OR_MISMATCHED,
	CHILD_CLOCK_DOMAIN_MISMATCH,
	PROVIDER_TIME_MISSING,
	PROVIDER_TIME_IN_FUTURE,
	WALL_TIME_MAPPING_UNDERFLOW,
}
internal enum class CellSourceUnverifiableReason {
	DELIVERY_IDENTITY_MISSING,
	PROVIDER_CONFIRMED_EMPTY_PROOF_UNAVAILABLE,
}
internal enum class CellFactRejection {
	IDENTITY_BEARING_INPUT,
	DELIVERY_IDENTITY_COLLISION,
	CAPTURE_AUTHORITY_MISMATCH,
	WAL_PROVENANCE_UNVERIFIABLE,
	WAL_INTEGRITY_UNVERIFIABLE,
	PAYLOAD_INTEGRITY_UNVERIFIABLE,
	PAYLOAD_DECODE_FAILED,
	PROVIDER_SEMANTICS_MISMATCH,
	CHILD_AUTHORITY_MISMATCH,
	INVALID_CORRECTION_BASE,
	INVALID_SUBSCRIPTION_COUNTS,
	PROCESSING_LIMIT_EXCEEDED,
}
internal enum class CellReplayReason { EXACT_DELIVERY, CORRECTION_NO_OP }

internal sealed interface CellCapturedFactClassification {
	data class FreshChanged(val fact: CellCapturedFact.Aggregate) : CellCapturedFactClassification
	data class FreshUnchanged(val fact: CellCapturedFact.CoverageOnly) : CellCapturedFactClassification
	data class Replay(
		val reference: CellAggregateFactReference,
		val reason: CellReplayReason,
	) : CellCapturedFactClassification
	data class Absent(val reason: CellAbsentReason) : CellCapturedFactClassification
	data class Stale(val rejectedChildCount: Int) : CellCapturedFactClassification
	data class Failed(val reason: CellFailureReason) : CellCapturedFactClassification
	data object PermissionLimited : CellCapturedFactClassification
	data object OsLimited : CellCapturedFactClassification
	data class ClockUnverifiable(
		val reason: CellClockUnverifiableReason,
	) : CellCapturedFactClassification
	data class SourceUnverifiable(
		val reason: CellSourceUnverifiableReason,
	) : CellCapturedFactClassification
	data class Rejected(val reason: CellFactRejection) : CellCapturedFactClassification
}

/** Bounded deterministic Cell classification before a source-local writer persists any fact. */
internal object CellCapturedFactClassifier {
	fun classify(
		input: CellObservationInput,
		authority: CellCaptureAuthority,
		semanticRevision: Long = 1L,
		supersedesSemanticRevision: Long? = null,
		priorFact: CellReusableFact? = null,
		correctionBase: CellReusableFact? = null,
	): CellCapturedFactClassification {
		when (input.outcome) {
			CellProviderOutcome.ABSENT -> return CellCapturedFactClassification.Absent(
				CellAbsentReason.PROVIDER_ABSENT,
			)
			CellProviderOutcome.FAILED -> return CellCapturedFactClassification.Failed(
				CellFailureReason.PROVIDER_FAILED,
			)
			CellProviderOutcome.PERMISSION_LIMITED ->
				return CellCapturedFactClassification.PermissionLimited
			CellProviderOutcome.OS_LIMITED -> return CellCapturedFactClassification.OsLimited
			CellProviderOutcome.DELIVERED -> Unit
		}
		if (!input.origin.isProviderCallback) {
			return CellCapturedFactClassification.Absent(CellAbsentReason.OPERATIONAL_ONLY)
		}
		val wal = input.walEvidence ?: return CellCapturedFactClassification.SourceUnverifiable(
			CellSourceUnverifiableReason.DELIVERY_IDENTITY_MISSING,
		)
		if (wal.capturedAuthority != authority) {
			return CellCapturedFactClassification.Rejected(
				CellFactRejection.CAPTURE_AUTHORITY_MISMATCH,
			)
		}
		val authenticated = when (val authentication = CellWalCarrierAuthenticator.authenticate(
			wal.toWalEntity(),
			authority.toWalCaptureAuthority(),
		)) {
			is CellWalCarrierAuthentication.Authenticated -> authentication.carrier
			is CellWalCarrierAuthentication.Rejected ->
				return walAuthenticationRejection(
					authentication.reason,
					wal.sourceDeliveryIdentity,
					priorFact,
				)
		}
		val deliveryIdentity = requireNotNull(wal.sourceDeliveryIdentity)
		val qualification = requireNotNull(authenticated.childQualification)
		val evidenceBinding = wal.evidenceBinding(SourceDeliveryIdentity(authenticated.deliveryIdentity))
		val identity = authority.factIdentity(deliveryIdentity)
		val mutation = runCatching {
			CellCapturedFactMutation(identity, semanticRevision, supersedesSemanticRevision)
		}.getOrElse {
			return CellCapturedFactClassification.Rejected(
				CellFactRejection.INVALID_CORRECTION_BASE,
			)
		}
		if (!hasValidCorrectionBase(mutation, authority, correctionBase)) {
			return CellCapturedFactClassification.Rejected(
				CellFactRejection.INVALID_CORRECTION_BASE,
			)
		}

		return classifyChildren(
			qualification = qualification,
			authority = authority,
			mutation = mutation,
			evidenceBinding = evidenceBinding,
			priorFact = priorFact,
			correctionBase = correctionBase,
		)
	}

	private fun classifyChildren(
		qualification: CellWalChildQualification,
		authority: CellCaptureAuthority,
		mutation: CellCapturedFactMutation,
		evidenceBinding: CellCapturedEvidenceBinding,
		priorFact: CellReusableFact?,
		correctionBase: CellReusableFact?,
	): CellCapturedFactClassification {
		if (qualification.acceptedChildCount == 0) return qualification.noAcceptedResult()
		val aggregate = requireNotNull(qualification.aggregate)
		val coverage = CellCoverageEvidence(
			providerIntervalStartElapsedRealtimeNanos =
				requireNotNull(qualification.coverageIntervalStartNanos),
			providerIntervalEndElapsedRealtimeNanos =
				requireNotNull(qualification.coverageIntervalEndNanos),
			submittedChildCount = qualification.submittedChildCount,
			acceptedChildCount = qualification.acceptedChildCount,
			staleChildCount = qualification.staleChildCount,
			futureTimeChildCount = qualification.futureTimeChildCount,
			missingTimeChildCount = 0,
			clockUnverifiableChildCount = 0,
			authorityMismatchChildCount = 0,
			unsupportedTechnologyChildCount = qualification.unsupportedTechnologyChildCount,
			expectedSubscriptionCount = null,
			observedSubscriptionCount = null,
			subscriptionCompleteness = CellSubscriptionCompleteness.UNKNOWN,
			childCompleteness = if (qualification.isComplete) {
				CellChildCompleteness.COMPLETE
			} else {
				CellChildCompleteness.PARTIAL
			},
		)
		return classifyCandidate(
			mutation = mutation,
			authority = authority,
			evidenceBinding = evidenceBinding,
			observedWallTimeMs = requireNotNull(qualification.observedWallTimeMs),
			wallTimeUncertaintyMs = evidenceBinding.wallTimeUncertaintyMs,
			availability = CellAvailability.AVAILABLE,
			coverage = coverage,
			aggregate = CellIdentityFreeAggregate(
				observationCount = aggregate.observationCount,
				registeredObservationCount = aggregate.registeredObservationCount,
				technologyMix = CellTechnologyMix(
					mapOf(
						CellRadioTechnology.GSM to aggregate.gsmCount,
						CellRadioTechnology.CDMA to aggregate.cdmaCount,
						CellRadioTechnology.WCDMA to aggregate.wcdmaCount,
						CellRadioTechnology.TDSCDMA to aggregate.tdscdmaCount,
						CellRadioTechnology.LTE to aggregate.lteCount,
						CellRadioTechnology.NR to aggregate.nrCount,
					).filterValues { it > 0 },
				),
				signalQuality = CellSignalQualityDistribution(
					unknownCount = aggregate.qualityUnknownCount,
					noneOrUnknownCount = aggregate.qualityNoneOrUnknownCount,
					poorCount = aggregate.qualityPoorCount,
					moderateCount = aggregate.qualityModerateCount,
					goodCount = aggregate.qualityGoodCount,
					greatCount = aggregate.qualityGreatCount,
				),
				weakPeriod = CellWeakPeriodEvidence(
					weakObservationCount = aggregate.weakObservationCount,
					knownQualityObservationCount = aggregate.knownQualityObservationCount,
					allKnownQualityIsWeak = aggregate.allKnownQualityIsWeak,
				),
			),
			priorFact = priorFact,
			correctionBase = correctionBase,
		)
	}

	private fun classifyCandidate(
		mutation: CellCapturedFactMutation,
		authority: CellCaptureAuthority,
		evidenceBinding: CellCapturedEvidenceBinding,
		observedWallTimeMs: Long,
		wallTimeUncertaintyMs: Long,
		availability: CellAvailability,
		coverage: CellCoverageEvidence,
		aggregate: CellIdentityFreeAggregate,
		priorFact: CellReusableFact?,
		correctionBase: CellReusableFact?,
	): CellCapturedFactClassification {
		val productEffect = CellCapturedProductEffect(
			evidenceBinding = evidenceBinding,
			observedWallTimeMs = observedWallTimeMs,
			wallTimeUncertaintyMs = wallTimeUncertaintyMs,
			availability = availability,
			coverage = coverage,
			aggregate = aggregate,
		)
		if (mutation.semanticRevision == 1L &&
			priorFact?.reference?.identity?.sourceDeliveryIdentity ==
			mutation.identity.sourceDeliveryIdentity
		) {
			return if (priorFact.reference.identity == mutation.identity &&
				priorFact.authority == authority && priorFact.productEffect == productEffect
			) {
				CellCapturedFactClassification.Replay(
					priorFact.reference,
					CellReplayReason.EXACT_DELIVERY,
				)
			} else {
				CellCapturedFactClassification.Rejected(
					CellFactRejection.DELIVERY_IDENTITY_COLLISION,
				)
			}
		}
		if (correctionBase != null && correctionBase.productEffect == productEffect) {
			return CellCapturedFactClassification.Replay(
				correctionBase.reference,
				CellReplayReason.CORRECTION_NO_OP,
			)
		}
		val reusablePrior: CellReusableFact.DirectAggregateOwner? = when {
			// A correction advances this logical fact's cursor, so its superseded revision can no
			// longer be a current direct owner. Keep every effective correction self-contained.
			mutation.semanticRevision > 1L -> null
			else -> (priorFact as? CellReusableFact.DirectAggregateOwner)?.takeIf {
				it.reference.identity != mutation.identity &&
					it.authority.temporalAuthority.isImmutableForAggregateReuse &&
					authority.canReuseAggregateFrom(it.authority) &&
					it.productEffect.aggregate == aggregate
			}
		}
		if (reusablePrior != null) {
			return CellCapturedFactClassification.FreshUnchanged(
				CellCapturedFact.CoverageOnly.create(
					mutation = mutation,
					authority = authority,
					evidenceBinding = evidenceBinding,
					observedWallTimeMs = observedWallTimeMs,
					wallTimeUncertaintyMs = wallTimeUncertaintyMs,
					availability = CellAvailability.AVAILABLE,
					coverage = coverage,
					aggregate = aggregate,
					reusesAggregate = reusablePrior,
				),
			)
		}
		return CellCapturedFactClassification.FreshChanged(
			CellCapturedFact.Aggregate(
				mutation,
				authority,
				evidenceBinding,
				observedWallTimeMs,
				wallTimeUncertaintyMs,
				CellAvailability.AVAILABLE,
				coverage,
				aggregate,
			),
		)
	}

	private fun CellWalChildQualification.noAcceptedResult(): CellCapturedFactClassification {
		return when {
			futureTimeChildCount > 0 -> CellCapturedFactClassification.ClockUnverifiable(
				CellClockUnverifiableReason.PROVIDER_TIME_IN_FUTURE,
			)
			unsupportedTechnologyChildCount > 0 ->
				CellCapturedFactClassification.OsLimited
			else -> CellCapturedFactClassification.Stale(submittedChildCount)
		}
	}

	private fun hasValidCorrectionBase(
		mutation: CellCapturedFactMutation,
		authority: CellCaptureAuthority,
		correctionBase: CellReusableFact?,
	): Boolean = when {
		mutation.semanticRevision == 1L -> correctionBase == null
		correctionBase == null -> false
		correctionBase.reference.identity != mutation.identity -> false
		correctionBase.reference.semanticRevision != mutation.supersedesSemanticRevision -> false
		!authority.isExactSettlementOf(correctionBase.authority) -> false
		else -> true
	}

	private fun CellCaptureAuthority.factIdentity(deliveryIdentity: SourceDeliveryIdentity) =
		CellCapturedFactIdentity(
			sourceDeliveryIdentity = deliveryIdentity,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sessionSegmentId = sessionSegmentId,
			sessionManifestRevision = sessionManifestRevision,
			collectedDataEpoch = collectedDataEpoch,
			scopeDeletionGeneration = scopeDeletionGeneration,
		)

	private fun CellWalObservationEvidence.evidenceBinding(
		canonicalDeliveryIdentity: SourceDeliveryIdentity,
	) = CellCapturedEvidenceBinding(
		sourceEventId = sourceEventId,
		sourceKind = sourceKind,
		sourceDeliveryIdentity = canonicalDeliveryIdentity,
		sourceAdmissionOrdinal = sourceAdmissionOrdinal,
		walIntegrityIdentity = walIntegrityIdentity,
		payloadChecksum = payloadChecksum,
		deliveryUnitIndex = deliveryUnitIndex,
		deliveryUnitCount = deliveryUnitCount,
		sourceSequence = sourceSequence,
		planAttribution = planAttribution,
		payloadVersion = payloadVersion,
		canonicalProviderSemanticsDigest = canonicalDeliveryIdentity.value,
		capturedAuthority = capturedAuthority,
		observedIntervalStartElapsedRealtimeNanos =
			clock.observedIntervalStartElapsedRealtimeNanos,
		observedElapsedRealtimeNanos = clock.observedElapsedRealtimeNanos,
		receivedElapsedRealtimeNanos = clock.receivedElapsedRealtimeNanos,
		observedWallTimeMs = clock.observedWallTimeMs,
		wallTimeUncertaintyMs = clock.wallTimeUncertaintyMs,
		acquiredAtMs = acquiredAtMs,
		createdAtMs = createdAtMs,
		qualityFlags = qualityFlags,
		qualityConfidence = qualityConfidence,
	)

	private fun CellCaptureAuthority.toWalCaptureAuthority() = CellWalCaptureAuthority(
		logicalTrackingId = logicalTrackingId.value,
		serviceRunId = serviceRunId.value,
		sourceInstanceId = sourceInstanceId.value,
		registrationGeneration = registrationGeneration,
		configurationRevision = configurationRevision,
		physicalConfigurationFingerprint = physicalConfigurationFingerprint,
		authorizationRevision = authorizationRevision,
		authorizationFingerprint = authorizationFingerprint,
		purposeEligibilityMask = purposeEligibilityMask,
		sourcePolicyRevision = sourcePolicyRevision,
		captureConsentEpoch = captureConsentEpoch,
		manifestRevision = sessionManifestRevision,
		lifecycleLeaseGeneration = lifecycleLeaseGeneration,
		collectedDataEpoch = collectedDataEpoch,
		clockDomainId = clockDomainId,
		zoneId = zoneId,
		structuralEpochDay = structuralEpochDay,
		providerAcceptance = temporalAuthority.providerAcceptance.toWalInterval(),
		authorizationEffect = temporalAuthority.authorizationEffect.toWalInterval(),
		consentEffect = temporalAuthority.consentEffect.toWalInterval(),
		sessionRunEffect = temporalAuthority.sessionRunEffect.toWalInterval(),
		deletionEffect = temporalAuthority.deletionEffect.toWalInterval(),
		maximumObservationAgeNanos = maximumObservationAgeNanos,
	)

	private fun CellProviderTimeInterval.toWalInterval() = CellWalProviderInterval(
		startInclusiveNanos,
		endExclusiveNanos,
	)

	private fun walAuthenticationRejection(
		reason: CellWalCarrierFailure,
		deliveryIdentity: SourceDeliveryIdentity?,
		priorFact: CellReusableFact?,
	): CellCapturedFactClassification = when (reason) {
		CellWalCarrierFailure.WAL_INTEGRITY_MISMATCH ->
			CellCapturedFactClassification.Rejected(
				CellFactRejection.WAL_INTEGRITY_UNVERIFIABLE,
			)
		CellWalCarrierFailure.PAYLOAD_INTEGRITY_MISMATCH ->
			CellCapturedFactClassification.Rejected(
				CellFactRejection.PAYLOAD_INTEGRITY_UNVERIFIABLE,
			)
		CellWalCarrierFailure.CAPTURE_AUTHORITY_MISMATCH ->
			CellCapturedFactClassification.Rejected(CellFactRejection.CAPTURE_AUTHORITY_MISMATCH)
		CellWalCarrierFailure.EMPTY_PROVIDER_DELIVERY ->
			CellCapturedFactClassification.SourceUnverifiable(
				CellSourceUnverifiableReason.PROVIDER_CONFIRMED_EMPTY_PROOF_UNAVAILABLE,
			)
		CellWalCarrierFailure.TOO_MANY_CHILDREN,
		CellWalCarrierFailure.PAYLOAD_TOO_LARGE ->
			CellCapturedFactClassification.Rejected(CellFactRejection.PROCESSING_LIMIT_EXCEEDED)
		CellWalCarrierFailure.IDENTITY_BEARING_CHILD ->
			CellCapturedFactClassification.Rejected(CellFactRejection.IDENTITY_BEARING_INPUT)
		CellWalCarrierFailure.INVALID_PROVIDER_TIME,
		CellWalCarrierFailure.PROVIDER_TIMELINE_MISMATCH,
		CellWalCarrierFailure.STRUCTURAL_DAY_MISMATCH ->
			CellCapturedFactClassification.ClockUnverifiable(
				CellClockUnverifiableReason.DELIVERY_CLOCK_DOMAIN_MISSING_OR_MISMATCHED,
			)
		CellWalCarrierFailure.DELIVERY_IDENTITY_MISMATCH ->
			if (priorFact?.reference?.identity?.sourceDeliveryIdentity == deliveryIdentity) {
				CellCapturedFactClassification.Rejected(
					CellFactRejection.DELIVERY_IDENTITY_COLLISION,
				)
			} else {
				CellCapturedFactClassification.Rejected(
					CellFactRejection.PROVIDER_SEMANTICS_MISMATCH,
				)
			}
		CellWalCarrierFailure.UNSUPPORTED_PAYLOAD_VERSION,
		CellWalCarrierFailure.PAYLOAD_DECODE_FAILED,
		CellWalCarrierFailure.NON_CANONICAL_PAYLOAD ->
			CellCapturedFactClassification.Rejected(CellFactRejection.PAYLOAD_DECODE_FAILED)
		CellWalCarrierFailure.NON_CANONICAL_CHILD_ORDER ->
			CellCapturedFactClassification.Rejected(CellFactRejection.PROVIDER_SEMANTICS_MISMATCH)
		CellWalCarrierFailure.INCOMPLETE_CAPTURE_BINDING ->
			if (deliveryIdentity == null) {
				CellCapturedFactClassification.SourceUnverifiable(
					CellSourceUnverifiableReason.DELIVERY_IDENTITY_MISSING,
				)
			} else {
				CellCapturedFactClassification.Rejected(
					CellFactRejection.WAL_PROVENANCE_UNVERIFIABLE,
				)
			}
		CellWalCarrierFailure.WRONG_SOURCE,
		CellWalCarrierFailure.NON_CAPTURE_PURPOSE,
		CellWalCarrierFailure.PROVIDER_DEDUP_KEY_PRESENT,
		CellWalCarrierFailure.ACTIVITY_AUTOMATION_PRESENT,
		CellWalCarrierFailure.MALFORMED_PROVIDER_ENVELOPE ->
			CellCapturedFactClassification.Rejected(
				CellFactRejection.WAL_PROVENANCE_UNVERIFIABLE,
			)
		CellWalCarrierFailure.FACT_LINKAGE_MISMATCH,
		CellWalCarrierFailure.FACT_SEMANTICS_MISMATCH ->
			CellCapturedFactClassification.Rejected(CellFactRejection.PROVIDER_SEMANTICS_MISMATCH)
	}

	private val CellObservationOrigin.isProviderCallback: Boolean
		get() = this == CellObservationOrigin.PROVIDER_CALLBACK ||
			this == CellObservationOrigin.CHANGE_CALLBACK ||
			this == CellObservationOrigin.REFRESH_RESULT_CALLBACK

}

internal const val MAX_CELL_CHILDREN_PER_DELIVERY = 256
internal const val MAX_CELL_SUBSCRIPTIONS_PER_DELIVERY = 8
