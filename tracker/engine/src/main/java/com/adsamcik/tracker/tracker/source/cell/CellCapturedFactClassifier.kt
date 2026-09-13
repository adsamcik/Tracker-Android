package com.adsamcik.tracker.tracker.source.cell

import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.cellProviderDeliveryIdentity
import java.security.MessageDigest

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

/** A minimized callback child. Raw Cell or subscription identifiers cannot enter this contract. */
internal data class CellProviderChild(
	val containsProhibitedIdentity: Boolean,
	val radioTechnology: CellRadioTechnology?,
	val registered: Boolean,
	val signalQualityLevel: Int?,
	val providerTimestampNanos: Long?,
	val authority: CellChildAuthority,
) {
	init {
		require(signalQualityLevel == null || signalQualityLevel in MIN_SIGNAL_LEVEL..MAX_SIGNAL_LEVEL)
	}
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
	val acquiredAtMs: Long,
	val qualityFlags: Long,
	val qualityConfidence: Float?,
	val payloadVersion: Int,
	val payloadBytes: List<Byte>,
	val createdAtMs: Long,
	val capturedAuthority: CellCaptureAuthority,
) {
	fun calculatedWalIntegrityIdentity(): String = asWalEntity().calculatedIntegrityIdentity()

	private fun asWalEntity(): SourceEventWalEntity = SourceEventWalEntity(
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
	private val payloadCodec = DefaultSourcePayloadCodec()

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
		if (!wal.hasValidWalProvenance()) {
			return CellCapturedFactClassification.Rejected(
				CellFactRejection.WAL_PROVENANCE_UNVERIFIABLE,
			)
		}
		if (!wal.matchesCapturedAuthority(authority)) {
			return CellCapturedFactClassification.Rejected(
				CellFactRejection.CAPTURE_AUTHORITY_MISMATCH,
			)
		}
		val payload = decodeWalPayload(wal) ?: return walPayloadRejection(wal)
		if (!wal.clock.isValidFor(authority) || !payload.providerTimelineMatches(wal.clock)) {
			return CellCapturedFactClassification.ClockUnverifiable(
				CellClockUnverifiableReason.DELIVERY_CLOCK_DOMAIN_MISSING_OR_MISMATCHED,
			)
		}
		if (payload.subscriptionId != null || payload.refreshOutcome != CellRefreshOutcome.CALLBACK) {
			return CellCapturedFactClassification.Rejected(
				CellFactRejection.PROVIDER_SEMANTICS_MISMATCH,
			)
		}
		if (payload.observations.isEmpty()) {
			return CellCapturedFactClassification.SourceUnverifiable(
				CellSourceUnverifiableReason.PROVIDER_CONFIRMED_EMPTY_PROOF_UNAVAILABLE,
			)
		}
		if (payload.observations.size > MAX_CELL_CHILDREN_PER_DELIVERY) {
			return CellCapturedFactClassification.Rejected(
				CellFactRejection.PROCESSING_LIMIT_EXCEEDED,
			)
		}
		val decodedChildren = payload.observations.map { observation ->
			CellProviderChild(
				containsProhibitedIdentity = observation.identifierToken.isNotEmpty(),
				radioTechnology = observation.radioType.toCellRadioTechnologyOrNull(),
				registered = observation.registered,
				signalQualityLevel = observation.signalLevelDbm.toQualityLevel(),
				providerTimestampNanos = observation.providerTimestampNanos,
				authority = authority.childAuthority,
			)
		}
		if (wal.clock.clockDomainId != authority.clockDomainId) {
			return CellCapturedFactClassification.ClockUnverifiable(
				CellClockUnverifiableReason.DELIVERY_CLOCK_DOMAIN_MISSING_OR_MISMATCHED,
			)
		}
		if (decodedChildren.any(CellProviderChild::containsProhibitedIdentity)) {
			return CellCapturedFactClassification.Rejected(
				CellFactRejection.IDENTITY_BEARING_INPUT,
			)
		}
		val deliveryIdentity = wal.sourceDeliveryIdentity
			?: return CellCapturedFactClassification.SourceUnverifiable(
				CellSourceUnverifiableReason.DELIVERY_IDENTITY_MISSING,
			)
		val canonicalDeliveryIdentity = runCatching {
			cellProviderDeliveryIdentity(wal.clock.clockDomainId, payload.observations)
		}.getOrNull() ?: return CellCapturedFactClassification.Rejected(
			CellFactRejection.PROVIDER_SEMANTICS_MISMATCH,
		)
		if (canonicalDeliveryIdentity != deliveryIdentity) {
			return if (priorFact?.reference?.identity?.sourceDeliveryIdentity == deliveryIdentity) {
				CellCapturedFactClassification.Rejected(
					CellFactRejection.DELIVERY_IDENTITY_COLLISION,
				)
			} else {
				CellCapturedFactClassification.Rejected(
					CellFactRejection.PROVIDER_SEMANTICS_MISMATCH,
				)
			}
		}
		val evidenceBinding = wal.evidenceBinding(canonicalDeliveryIdentity)
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
			children = decodedChildren,
			clock = wal.clock,
			authority = authority,
			mutation = mutation,
			evidenceBinding = evidenceBinding,
			priorFact = priorFact,
			correctionBase = correctionBase,
		)
	}

	private fun classifyChildren(
		children: List<CellProviderChild>,
		clock: CellDurableClockEvidence,
		authority: CellCaptureAuthority,
		mutation: CellCapturedFactMutation,
		evidenceBinding: CellCapturedEvidenceBinding,
		priorFact: CellReusableFact?,
		correctionBase: CellReusableFact?,
	): CellCapturedFactClassification {
		val classified = children.map { child ->
			ClassifiedChild(child, authority.classifyChild(child, clock.receivedElapsedRealtimeNanos))
		}
		val accepted = classified.filter { it.classification == ChildClassification.FRESH }
		if (accepted.isEmpty()) return classified.noAcceptedResult()

		val providerTimes = accepted.map { requireNotNull(it.child.providerTimestampNanos) }
		val observedWallTimeMs = observedWallTimeMs(
			providerTimeNanos = requireNotNull(providerTimes.maxOrNull()),
			clock = clock,
		) ?: return CellCapturedFactClassification.ClockUnverifiable(
			CellClockUnverifiableReason.WALL_TIME_MAPPING_UNDERFLOW,
		)
		val coverage = coverage(
			classified = classified,
			providerStartNanos = requireNotNull(providerTimes.minOrNull()),
			providerEndNanos = requireNotNull(providerTimes.maxOrNull()),
		)
		val aggregate = identityFreeAggregate(accepted.map(ClassifiedChild::child))
		return classifyCandidate(
			mutation = mutation,
			authority = authority,
			evidenceBinding = evidenceBinding,
			observedWallTimeMs = observedWallTimeMs,
			wallTimeUncertaintyMs = clock.wallTimeUncertaintyMs,
			availability = CellAvailability.AVAILABLE,
			coverage = coverage,
			aggregate = aggregate,
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

	private fun CellCaptureAuthority.classifyChild(
		child: CellProviderChild,
		receivedElapsedRealtimeNanos: Long,
	): ChildClassification {
		if (child.authority.clockDomainId != clockDomainId) {
			return ChildClassification.CLOCK_UNVERIFIABLE
		}
		if (child.authority != childAuthority) return ChildClassification.AUTHORITY_MISMATCH
		val timeClassification = classifyProviderTime(
			child.providerTimestampNanos,
			receivedElapsedRealtimeNanos,
		)
		if (timeClassification != ChildClassification.FRESH) return timeClassification
		return if (child.radioTechnology == null) {
			ChildClassification.UNSUPPORTED_TECHNOLOGY
		} else {
			ChildClassification.FRESH
		}
	}

	private fun CellCaptureAuthority.classifyProviderTime(
		providerTimeNanos: Long?,
		receivedElapsedRealtimeNanos: Long,
	): ChildClassification {
		if (providerTimeNanos == null || providerTimeNanos <= 0L) {
			return ChildClassification.MISSING_TIME
		}
		if (providerTimeNanos > receivedElapsedRealtimeNanos) {
			return ChildClassification.FUTURE_TIME
		}
		if (providerTimeNanos !in temporalAuthority ||
			receivedElapsedRealtimeNanos - providerTimeNanos > maximumObservationAgeNanos
		) {
			return ChildClassification.STALE
		}
		return ChildClassification.FRESH
	}

	private fun coverage(
		classified: List<ClassifiedChild>,
		providerStartNanos: Long,
		providerEndNanos: Long,
	): CellCoverageEvidence {
		return CellCoverageEvidence(
			providerIntervalStartElapsedRealtimeNanos = providerStartNanos,
			providerIntervalEndElapsedRealtimeNanos = providerEndNanos,
			submittedChildCount = classified.size,
			acceptedChildCount = classified.count(ChildClassification.FRESH),
			staleChildCount = classified.count(ChildClassification.STALE),
			futureTimeChildCount = classified.count(ChildClassification.FUTURE_TIME),
			missingTimeChildCount = classified.count(ChildClassification.MISSING_TIME),
			clockUnverifiableChildCount = classified.count(ChildClassification.CLOCK_UNVERIFIABLE),
			authorityMismatchChildCount = classified.count(ChildClassification.AUTHORITY_MISMATCH),
			unsupportedTechnologyChildCount = classified.count(
				ChildClassification.UNSUPPORTED_TECHNOLOGY,
			),
			expectedSubscriptionCount = null,
			observedSubscriptionCount = null,
			subscriptionCompleteness = CellSubscriptionCompleteness.UNKNOWN,
			childCompleteness = if (
				classified.all { it.classification == ChildClassification.FRESH }
			) {
				CellChildCompleteness.COMPLETE
			} else {
				CellChildCompleteness.PARTIAL
			},
		)
	}

	private fun identityFreeAggregate(children: List<CellProviderChild>): CellIdentityFreeAggregate {
		val technologies = children.groupingBy { requireNotNull(it.radioTechnology) }
			.eachCount()
		val quality = children.groupingBy(CellProviderChild::signalQualityLevel).eachCount()
		val distribution = CellSignalQualityDistribution(
			unknownCount = quality[null] ?: 0,
			noneOrUnknownCount = quality[0] ?: 0,
			poorCount = quality[1] ?: 0,
			moderateCount = quality[2] ?: 0,
			goodCount = quality[3] ?: 0,
			greatCount = quality[4] ?: 0,
		)
		return CellIdentityFreeAggregate(
			observationCount = children.size,
			registeredObservationCount = children.count(CellProviderChild::registered),
			technologyMix = CellTechnologyMix(technologies),
			signalQuality = distribution,
			weakPeriod = CellWeakPeriodEvidence(
				weakObservationCount = distribution.weakCount,
				knownQualityObservationCount = distribution.knownCount,
				allKnownQualityIsWeak = distribution.knownCount > 0 &&
					distribution.weakCount == distribution.knownCount,
			),
		)
	}

	private fun List<ClassifiedChild>.noAcceptedResult(): CellCapturedFactClassification {
		val missing = count(ChildClassification.MISSING_TIME)
		val future = count(ChildClassification.FUTURE_TIME)
		return when {
			count(ChildClassification.CLOCK_UNVERIFIABLE) > 0 ->
				CellCapturedFactClassification.ClockUnverifiable(
					CellClockUnverifiableReason.CHILD_CLOCK_DOMAIN_MISMATCH,
				)
			missing > 0 -> CellCapturedFactClassification.ClockUnverifiable(
				CellClockUnverifiableReason.PROVIDER_TIME_MISSING,
			)
			future > 0 -> CellCapturedFactClassification.ClockUnverifiable(
				CellClockUnverifiableReason.PROVIDER_TIME_IN_FUTURE,
			)
			count(ChildClassification.AUTHORITY_MISMATCH) > 0 ->
				CellCapturedFactClassification.Rejected(CellFactRejection.CHILD_AUTHORITY_MISMATCH)
			count(ChildClassification.UNSUPPORTED_TECHNOLOGY) > 0 ->
				CellCapturedFactClassification.OsLimited
			else -> CellCapturedFactClassification.Stale(size)
		}
	}

	private fun List<ClassifiedChild>.count(classification: ChildClassification): Int =
		count { it.classification == classification }

	private fun observedWallTimeMs(
		providerTimeNanos: Long,
		clock: CellDurableClockEvidence,
	): Long? {
		val ageNanos = clock.observedElapsedRealtimeNanos - providerTimeNanos
		if (ageNanos < 0L) return null
		val ageMs = ageNanos / NANOS_PER_MILLISECOND
		return clock.observedWallTimeMs.takeIf { it >= ageMs }?.minus(ageMs)
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

	private fun CellWalObservationEvidence.hasValidWalProvenance(): Boolean =
		sourceKind == SourceKind.CELL && sourceAdmissionOrdinal > 0L &&
			providerDedupKey == null &&
			deliveryUnitCount > 0 && deliveryUnitIndex in 0 until deliveryUnitCount &&
			sourceSequence >= 0L && activityAutomationEpoch == null &&
			planAttribution == PlanAttribution.CAPTURED_REGISTRATION && acquiredAtMs >= 0L &&
			createdAtMs >= 0L &&
			(qualityConfidence == null || qualityConfidence in 0f..1f)

	private fun CellWalObservationEvidence.matchesCapturedAuthority(
		authority: CellCaptureAuthority,
	): Boolean = capturedAuthority == authority && logicalTrackingId == authority.logicalTrackingId &&
		serviceRunId == authority.serviceRunId && sourceInstanceId == authority.sourceInstanceId &&
		registrationGeneration == authority.registrationGeneration &&
		configurationRevision == authority.configurationRevision &&
		physicalConfigurationFingerprint == authority.physicalConfigurationFingerprint &&
		authorizationRevision == authority.authorizationRevision &&
		authorizationFingerprint == authority.authorizationFingerprint &&
		purposeEligibilityMask == authority.purposeEligibilityMask &&
		sourcePolicyRevision == authority.sourcePolicyRevision &&
		captureConsentEpoch == authority.captureConsentEpoch &&
		sessionManifestRevision == authority.sessionManifestRevision &&
		lifecycleLeaseGeneration == authority.lifecycleLeaseGeneration &&
		capturedCollectedDataEpoch == authority.collectedDataEpoch &&
		clock.clockDomainId == authority.clockDomainId

	private fun CellDurableClockEvidence.isValidFor(authority: CellCaptureAuthority): Boolean =
		clockDomainId == authority.clockDomainId && observedIntervalStartElapsedRealtimeNanos > 0L &&
		observedElapsedRealtimeNanos >= observedIntervalStartElapsedRealtimeNanos &&
		receivedElapsedRealtimeNanos >= observedElapsedRealtimeNanos &&
		observedWallTimeMs >= 0L && wallTimeUncertaintyMs >= 0L &&
		wallBelongsToStructuralDay(authority, observedWallTimeMs)

	private fun CellSnapshotPayload.providerTimelineMatches(
		clock: CellDurableClockEvidence,
	): Boolean {
		val providerTimes = observations.mapNotNull { it.providerTimestampNanos }
		if (providerTimes.isEmpty()) return true
		return providerTimes.minOrNull() == clock.observedIntervalStartElapsedRealtimeNanos &&
			providerTimes.maxOrNull() == clock.observedElapsedRealtimeNanos
	}

	private fun wallBelongsToStructuralDay(
		authority: CellCaptureAuthority,
		wallTimeMs: Long,
	): Boolean {
		val zone = java.time.ZoneId.of(authority.zoneId)
		val date = java.time.LocalDate.ofEpochDay(authority.structuralEpochDay)
		val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
		val end = date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
		return wallTimeMs in start until end
	}

	private fun decodeWalPayload(wal: CellWalObservationEvidence): CellSnapshotPayload? {
		if (!LOWERCASE_SHA_256.matches(wal.walIntegrityIdentity) ||
			!LOWERCASE_SHA_256.matches(wal.payloadChecksum)
		) return null
		val bytes = wal.payloadBytes.toByteArray()
		if (bytes.sha256() != wal.payloadChecksum ||
			wal.calculatedWalIntegrityIdentity() != wal.walIntegrityIdentity ||
			wal.payloadVersion != CELL_PAYLOAD_VERSION
		) return null
		return runCatching {
			payloadCodec.decode(SourceKind.CELL, wal.payloadVersion, bytes) as? CellSnapshotPayload
		}.getOrNull()
	}

	private fun walPayloadRejection(wal: CellWalObservationEvidence): CellCapturedFactClassification {
		if (!LOWERCASE_SHA_256.matches(wal.walIntegrityIdentity) ||
			wal.calculatedWalIntegrityIdentity() != wal.walIntegrityIdentity
		) {
			return CellCapturedFactClassification.Rejected(
				CellFactRejection.WAL_INTEGRITY_UNVERIFIABLE,
			)
		}
		val bytes = wal.payloadBytes.toByteArray()
		if (!LOWERCASE_SHA_256.matches(wal.payloadChecksum) || bytes.sha256() != wal.payloadChecksum) {
			return CellCapturedFactClassification.Rejected(
				CellFactRejection.PAYLOAD_INTEGRITY_UNVERIFIABLE,
			)
		}
		return CellCapturedFactClassification.Rejected(CellFactRejection.PAYLOAD_DECODE_FAILED)
	}

	private fun String.toCellRadioTechnologyOrNull(): CellRadioTechnology? =
		CellRadioTechnology.entries.singleOrNull { it.name == uppercase() }

	private fun Int?.toQualityLevel(): Int? = when {
		this == null || this == Int.MAX_VALUE -> null
		this <= -120 -> 0
		this <= -110 -> 1
		this <= -100 -> 2
		this <= -90 -> 3
		else -> 4
	}

	private val CellObservationOrigin.isProviderCallback: Boolean
		get() = this == CellObservationOrigin.PROVIDER_CALLBACK ||
			this == CellObservationOrigin.CHANGE_CALLBACK ||
			this == CellObservationOrigin.REFRESH_RESULT_CALLBACK

	private data class ClassifiedChild(
		val child: CellProviderChild,
		val classification: ChildClassification,
	)

	private enum class ChildClassification {
		FRESH,
		STALE,
		FUTURE_TIME,
		MISSING_TIME,
		CLOCK_UNVERIFIABLE,
		AUTHORITY_MISMATCH,
		UNSUPPORTED_TECHNOLOGY,
	}
}

internal const val MAX_CELL_CHILDREN_PER_DELIVERY = 256
internal const val MAX_CELL_SUBSCRIPTIONS_PER_DELIVERY = 8
private const val MIN_SIGNAL_LEVEL = 0
private const val MAX_SIGNAL_LEVEL = 4
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val CELL_PAYLOAD_VERSION = 1
private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
	.digest(this)
	.joinToString(separator = "") { byte -> "%02x".format(byte) }
