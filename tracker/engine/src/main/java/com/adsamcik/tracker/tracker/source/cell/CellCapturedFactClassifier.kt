package com.adsamcik.tracker.tracker.source.cell

import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity

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

/**
 * One source-local classification request. Subscription coverage is count-only: neither Android
 * subscription IDs nor stable slot ordinals are accepted by this contract.
 */
internal data class CellObservationInput(
	val origin: CellObservationOrigin,
	val outcome: CellProviderOutcome,
	val sourceDeliveryIdentity: SourceDeliveryIdentity?,
	val clockDomainId: String?,
	val providerEmptyObservationElapsedRealtimeNanos: Long?,
	val receivedElapsedRealtimeNanos: Long,
	val receivedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val expectedSubscriptionCount: Int?,
	val observedSubscriptionCount: Int,
	val children: List<CellProviderChild>,
) {
	init {
		require(receivedElapsedRealtimeNanos >= 0L)
		require(receivedWallTimeMs >= 0L)
		require(wallTimeUncertaintyMs >= 0L)
		require(expectedSubscriptionCount == null || expectedSubscriptionCount >= 0)
		require(observedSubscriptionCount >= 0)
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
	CONFIRMED_EMPTY_IDENTITY_MISSING,
}
internal enum class CellFactRejection {
	IDENTITY_BEARING_INPUT,
	DELIVERY_IDENTITY_COLLISION,
	CHILD_AUTHORITY_MISMATCH,
	INVALID_CORRECTION_BASE,
	INVALID_SUBSCRIPTION_COUNTS,
	PROCESSING_LIMIT_EXCEEDED,
}
internal enum class CellReplayReason { EXACT_DELIVERY, CORRECTION_NO_OP }

internal sealed interface CellCapturedFactClassification {
	data class FreshChanged(val fact: CellCapturedFact.Aggregate) : CellCapturedFactClassification
	data class FreshUnchanged(val fact: CellCapturedFact.CoverageOnly) : CellCapturedFactClassification
	data class FreshConfirmedEmpty(val fact: CellCapturedFact.CoverageOnly) : CellCapturedFactClassification
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
		if (input.children.size > MAX_CELL_CHILDREN_PER_DELIVERY ||
			(input.expectedSubscriptionCount ?: 0) > MAX_CELL_SUBSCRIPTIONS_PER_DELIVERY ||
			input.observedSubscriptionCount > MAX_CELL_SUBSCRIPTIONS_PER_DELIVERY
		) {
			return CellCapturedFactClassification.Rejected(
				CellFactRejection.PROCESSING_LIMIT_EXCEEDED,
			)
		}
		if (input.expectedSubscriptionCount != null &&
			input.observedSubscriptionCount > input.expectedSubscriptionCount
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
		val deliveryIdentity = input.sourceDeliveryIdentity ?: return when {
			input.children.isEmpty() -> CellCapturedFactClassification.SourceUnverifiable(
				CellSourceUnverifiableReason.CONFIRMED_EMPTY_IDENTITY_MISSING,
			)
			else -> CellCapturedFactClassification.SourceUnverifiable(
				CellSourceUnverifiableReason.DELIVERY_IDENTITY_MISSING,
			)
		}
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

		return if (input.children.isEmpty()) {
			classifyConfirmedEmpty(input, authority, mutation, priorFact, correctionBase)
		} else {
			classifyChildren(input, authority, mutation, priorFact, correctionBase)
		}
	}

	private fun classifyChildren(
		input: CellObservationInput,
		authority: CellCaptureAuthority,
		mutation: CellCapturedFactMutation,
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
			observedWallTimeMs = observedWallTimeMs,
			wallTimeUncertaintyMs = input.wallTimeUncertaintyMs,
			availability = CellAvailability.AVAILABLE,
			coverage = coverage,
			aggregate = aggregate,
			priorFact = priorFact,
			correctionBase = correctionBase,
		)
	}

	private fun classifyConfirmedEmpty(
		input: CellObservationInput,
		authority: CellCaptureAuthority,
		mutation: CellCapturedFactMutation,
		priorFact: CellReusableFact?,
		correctionBase: CellReusableFact?,
	): CellCapturedFactClassification {
		val providerTime = input.providerEmptyObservationElapsedRealtimeNanos
			?: return CellCapturedFactClassification.ClockUnverifiable(
				CellClockUnverifiableReason.PROVIDER_TIME_MISSING,
			)
		when (authority.classifyProviderTime(providerTime, input.receivedElapsedRealtimeNanos)) {
			ChildClassification.MISSING_TIME -> return CellCapturedFactClassification.ClockUnverifiable(
				CellClockUnverifiableReason.PROVIDER_TIME_MISSING,
			)
			ChildClassification.FUTURE_TIME -> return CellCapturedFactClassification.ClockUnverifiable(
				CellClockUnverifiableReason.PROVIDER_TIME_IN_FUTURE,
			)
			ChildClassification.STALE -> return CellCapturedFactClassification.Stale(0)
			else -> Unit
		}
		val observedWallTimeMs = observedWallTimeMs(
			providerTime,
			input.receivedElapsedRealtimeNanos,
			input.receivedWallTimeMs,
		) ?: return CellCapturedFactClassification.ClockUnverifiable(
			CellClockUnverifiableReason.WALL_TIME_MAPPING_UNDERFLOW,
		)
		val coverage = CellCoverageEvidence(
			providerIntervalStartElapsedRealtimeNanos = providerTime,
			providerIntervalEndElapsedRealtimeNanos = providerTime,
			submittedChildCount = 0,
			acceptedChildCount = 0,
			staleChildCount = 0,
			futureTimeChildCount = 0,
			missingTimeChildCount = 0,
			clockUnverifiableChildCount = 0,
			authorityMismatchChildCount = 0,
			unsupportedTechnologyChildCount = 0,
			expectedSubscriptionCount = input.expectedSubscriptionCount,
			observedSubscriptionCount = input.observedSubscriptionCount,
			subscriptionCompleteness = subscriptionCompleteness(input),
			childCompleteness = CellChildCompleteness.COMPLETE,
		)
		return classifyCandidate(
			mutation = mutation,
			authority = authority,
			observedWallTimeMs = observedWallTimeMs,
			wallTimeUncertaintyMs = input.wallTimeUncertaintyMs,
			availability = CellAvailability.CONFIRMED_EMPTY,
			coverage = coverage,
			aggregate = null,
			priorFact = priorFact,
			correctionBase = correctionBase,
		)
	}

	private fun classifyCandidate(
		mutation: CellCapturedFactMutation,
		authority: CellCaptureAuthority,
		observedWallTimeMs: Long,
		wallTimeUncertaintyMs: Long,
		availability: CellAvailability,
		coverage: CellCoverageEvidence,
		aggregate: CellIdentityFreeAggregate?,
		priorFact: CellReusableFact?,
		correctionBase: CellReusableFact?,
	): CellCapturedFactClassification {
		val sameIdentity = priorFact?.takeIf { it.reference.identity == mutation.identity }
		if (mutation.semanticRevision == 1L && sameIdentity != null) {
			return if (sameIdentity.matchesSourceDelivery(availability, aggregate)) {
				CellCapturedFactClassification.Replay(
					sameIdentity.reference,
					CellReplayReason.EXACT_DELIVERY,
				)
			} else {
				CellCapturedFactClassification.Rejected(
					CellFactRejection.DELIVERY_IDENTITY_COLLISION,
				)
			}
		}
		if (correctionBase != null && correctionBase.matches(availability, coverage, aggregate)) {
			return CellCapturedFactClassification.Replay(
				correctionBase.reference,
				CellReplayReason.CORRECTION_NO_OP,
			)
		}
		if (availability == CellAvailability.CONFIRMED_EMPTY) {
			return CellCapturedFactClassification.FreshConfirmedEmpty(
				CellCapturedFact.CoverageOnly(
					mutation,
					authority,
					observedWallTimeMs,
					wallTimeUncertaintyMs,
					availability,
					coverage,
					reusesAggregate = null,
				),
			)
		}
		val reusablePrior = when {
			mutation.semanticRevision > 1L -> correctionBase?.takeIf {
				it.availability == CellAvailability.AVAILABLE && it.aggregate == aggregate
			}
			else -> priorFact?.takeIf {
				it.reference.identity != mutation.identity && it.authority == authority &&
					it.availability == CellAvailability.AVAILABLE && it.aggregate == aggregate
			}
		}
		if (reusablePrior != null) {
			return CellCapturedFactClassification.FreshUnchanged(
				CellCapturedFact.CoverageOnly(
					mutation,
					authority,
					observedWallTimeMs,
					wallTimeUncertaintyMs,
					CellAvailability.AVAILABLE,
					coverage,
					reusesAggregate = requireNotNull(reusablePrior.aggregateOwnerReference),
				),
			)
		}
		return CellCapturedFactClassification.FreshChanged(
			CellCapturedFact.Aggregate(
				mutation,
				authority,
				observedWallTimeMs,
				wallTimeUncertaintyMs,
				CellAvailability.AVAILABLE,
				coverage,
				requireNotNull(aggregate),
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
	) = CellCoverageEvidence(
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
		expectedSubscriptionCount = input.expectedSubscriptionCount,
		observedSubscriptionCount = input.observedSubscriptionCount,
		subscriptionCompleteness = subscriptionCompleteness(input),
		childCompleteness = if (classified.all { it.classification == ChildClassification.FRESH }) {
			CellChildCompleteness.COMPLETE
		} else {
			CellChildCompleteness.PARTIAL
		},
	)

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

	private fun subscriptionCompleteness(input: CellObservationInput) = when {
		input.expectedSubscriptionCount == null -> CellSubscriptionCompleteness.UNKNOWN
		input.observedSubscriptionCount < input.expectedSubscriptionCount ->
			CellSubscriptionCompleteness.PARTIAL
		else -> CellSubscriptionCompleteness.COMPLETE
	}

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

	private fun CellReusableFact.matches(
		availability: CellAvailability,
		coverage: CellCoverageEvidence,
		aggregate: CellIdentityFreeAggregate?,
	): Boolean = this.availability == availability && this.coverage == coverage &&
		this.aggregate == aggregate

	private fun CellReusableFact.matchesSourceDelivery(
		availability: CellAvailability,
		aggregate: CellIdentityFreeAggregate?,
	): Boolean = this.availability == availability && this.aggregate == aggregate

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
