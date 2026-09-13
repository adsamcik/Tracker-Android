package com.adsamcik.tracker.tracker.source.cell

import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import java.security.MessageDigest

internal enum class CellObservationOrigin {
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
	val sourceDeliveryIdentity: SourceDeliveryIdentity,
	val sourceAdmissionOrdinal: Long,
	val walIntegrityIdentity: String,
	val payloadChecksum: String,
	val deliveryUnitIndex: Int,
	val deliveryUnitCount: Int,
	val sourceSequence: Long,
	val capturedAuthority: CellChildAuthority,
) {
	init {
		require(sourceAdmissionOrdinal > 0L)
		require(LOWERCASE_SHA_256.matches(walIntegrityIdentity))
		require(LOWERCASE_SHA_256.matches(payloadChecksum))
		require(deliveryUnitCount > 0)
		require(deliveryUnitIndex in 0 until deliveryUnitCount)
		require(sourceSequence >= 0L)
	}
}

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
	val clockDomainId: String?,
	val receivedElapsedRealtimeNanos: Long,
	val receivedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val subscriptionGroupProof: CellSubscriptionGroupProof?,
	val children: List<CellProviderChild>,
) {
	init {
		require(receivedElapsedRealtimeNanos >= 0L)
		require(receivedWallTimeMs >= 0L)
		require(wallTimeUncertaintyMs >= 0L)
	}
}

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
		if (input.children.isEmpty()) {
			return CellCapturedFactClassification.SourceUnverifiable(
				CellSourceUnverifiableReason.PROVIDER_CONFIRMED_EMPTY_PROOF_UNAVAILABLE,
			)
		}
		if (input.children.size > MAX_CELL_CHILDREN_PER_DELIVERY ||
			(input.subscriptionGroupProof?.expectedGroupCount ?: 0) >
			MAX_CELL_SUBSCRIPTIONS_PER_DELIVERY ||
			(input.subscriptionGroupProof?.observedGroupCount ?: 0L) >
			MAX_CELL_SUBSCRIPTIONS_PER_DELIVERY
		) {
			return CellCapturedFactClassification.Rejected(
				CellFactRejection.PROCESSING_LIMIT_EXCEEDED,
			)
		}
		val groupProof = input.subscriptionGroupProof
		if (groupProof != null && (
			groupProof.observedGroupCount > groupProof.expectedGroupCount.toLong() ||
				groupProof.representedChildCount != input.children.size.toLong()
			)
		) {
			return CellCapturedFactClassification.Rejected(
				CellFactRejection.INVALID_SUBSCRIPTION_COUNTS,
			)
		}
		if (input.clockDomainId != authority.clockDomainId) {
			return CellCapturedFactClassification.ClockUnverifiable(
				CellClockUnverifiableReason.DELIVERY_CLOCK_DOMAIN_MISSING_OR_MISMATCHED,
			)
		}
		if (input.children.any(CellProviderChild::containsProhibitedIdentity)) {
			return CellCapturedFactClassification.Rejected(
				CellFactRejection.IDENTITY_BEARING_INPUT,
			)
		}
		val wal = input.walEvidence ?: return CellCapturedFactClassification.SourceUnverifiable(
			CellSourceUnverifiableReason.DELIVERY_IDENTITY_MISSING,
		)
		if (wal.capturedAuthority != authority.childAuthority) {
			return CellCapturedFactClassification.Rejected(
				CellFactRejection.CAPTURE_AUTHORITY_MISMATCH,
			)
		}
		val deliveryIdentity = wal.sourceDeliveryIdentity
		val evidenceBinding = wal.evidenceBinding(input)
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
			input,
			authority,
			mutation,
			evidenceBinding,
			priorFact,
			correctionBase,
		)
	}

	private fun classifyChildren(
		input: CellObservationInput,
		authority: CellCaptureAuthority,
		mutation: CellCapturedFactMutation,
		evidenceBinding: CellCapturedEvidenceBinding,
		priorFact: CellReusableFact?,
		correctionBase: CellReusableFact?,
	): CellCapturedFactClassification {
		val classified = input.children.map { child ->
			ClassifiedChild(child, authority.classifyChild(child, input.receivedElapsedRealtimeNanos))
		}
		val accepted = classified.filter { it.classification == ChildClassification.FRESH }
		if (accepted.isEmpty()) return classified.noAcceptedResult()

		val providerTimes = accepted.map { requireNotNull(it.child.providerTimestampNanos) }
		val observedWallTimeMs = observedWallTimeMs(
			providerTimeNanos = requireNotNull(providerTimes.maxOrNull()),
			receivedElapsedRealtimeNanos = input.receivedElapsedRealtimeNanos,
			receivedWallTimeMs = input.receivedWallTimeMs,
		) ?: return CellCapturedFactClassification.ClockUnverifiable(
			CellClockUnverifiableReason.WALL_TIME_MAPPING_UNDERFLOW,
		)
		val coverage = coverage(
			input = input,
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
			wallTimeUncertaintyMs = input.wallTimeUncertaintyMs,
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
			mutation.semanticRevision > 1L ->
				(correctionBase as? CellReusableFact.DirectAggregateOwner)?.takeIf {
					authority.canReuseAggregateFrom(it.authority) &&
					it.productEffect.aggregate == aggregate
				}
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
		input: CellObservationInput,
		classified: List<ClassifiedChild>,
		providerStartNanos: Long,
		providerEndNanos: Long,
	): CellCoverageEvidence {
		val groupProof = input.subscriptionGroupProof
		val subscriptionCompleteness = when {
			groupProof == null -> CellSubscriptionCompleteness.UNKNOWN
			groupProof.observedGroupCount < groupProof.expectedGroupCount.toLong() ->
				CellSubscriptionCompleteness.PARTIAL
			else -> CellSubscriptionCompleteness.COMPLETE
		}
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
			expectedSubscriptionCount = groupProof?.expectedGroupCount,
			observedSubscriptionCount = groupProof?.observedGroupCount?.toInt(),
			subscriptionCompleteness = subscriptionCompleteness,
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
		receivedElapsedRealtimeNanos: Long,
		receivedWallTimeMs: Long,
	): Long? {
		val ageNanos = receivedElapsedRealtimeNanos - providerTimeNanos
		if (ageNanos < 0L) return null
		val ageMs = ageNanos / NANOS_PER_MILLISECOND
		return receivedWallTimeMs.takeIf { it >= ageMs }?.minus(ageMs)
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
		correctionBase.authority != authority -> false
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
		input: CellObservationInput,
	) = CellCapturedEvidenceBinding(
		sourceDeliveryIdentity = sourceDeliveryIdentity,
		sourceAdmissionOrdinal = sourceAdmissionOrdinal,
		walIntegrityIdentity = walIntegrityIdentity,
		payloadChecksum = payloadChecksum,
		deliveryUnitIndex = deliveryUnitIndex,
		deliveryUnitCount = deliveryUnitCount,
		sourceSequence = sourceSequence,
		canonicalProviderSemanticsDigest = input.canonicalProviderSemanticsDigest(),
		capturedAuthority = capturedAuthority,
	)

	private fun CellObservationInput.canonicalProviderSemanticsDigest(): String {
		val proof = subscriptionGroupProof?.let { groupProof ->
			listOf(groupProof.expectedGroupCount) + groupProof.observedGroupSizeHistogram.entries
				.sortedBy { it.key }
				.flatMap { (size, count) -> listOf(size, count) }
		}.orEmpty()
		val childSemantics = children.map { child ->
			listOf(
				child.containsProhibitedIdentity,
				child.radioTechnology?.name,
				child.registered,
				child.signalQualityLevel,
				child.providerTimestampNanos,
				child.authority.sourceInstanceId.value,
				child.authority.registrationGeneration,
				child.authority.configurationRevision,
				child.authority.authorizationRevision,
				child.authority.authorizationFingerprint,
				child.authority.sourcePolicyRevision,
				child.authority.captureConsentEpoch,
				child.authority.sessionManifestRevision,
				child.authority.lifecycleLeaseGeneration,
				child.authority.collectedDataEpoch,
				child.authority.scopeDeletionGeneration,
				child.authority.clockDomainId,
			).canonicalCellValues()
		}.sorted()
		return sha256(
			(listOf(
				"cell-decoded-provider-semantics-v1",
				origin.name,
				outcome.name,
				clockDomainId,
				receivedElapsedRealtimeNanos,
				receivedWallTimeMs,
				wallTimeUncertaintyMs,
			) + proof + childSemantics).canonicalCellValues(),
		)
	}

	private val CellObservationOrigin.isProviderCallback: Boolean
		get() = this == CellObservationOrigin.CHANGE_CALLBACK ||
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
private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")

private fun List<Any?>.canonicalCellValues(): String = joinToString(separator = "") { value ->
	val text = value?.toString()
	if (text == null) "-1:" else "${text.length}:$text"
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
	.digest(value.toByteArray(Charsets.UTF_8))
	.joinToString(separator = "") { byte -> "%02x".format(byte) }
