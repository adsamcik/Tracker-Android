package com.adsamcik.tracker.shared.base.database.data

/**
 * Specific original Cell payload, capture-binding, child-time and revision comparison failures.
 */
enum class CellWalCarrierFailure {
	WRONG_SOURCE,
	UNSUPPORTED_PAYLOAD_VERSION,
	PAYLOAD_INTEGRITY_MISMATCH,
	WAL_INTEGRITY_MISMATCH,
	INCOMPLETE_CAPTURE_BINDING,
	NON_CAPTURE_PURPOSE,
	PROVIDER_DEDUP_KEY_PRESENT,
	ACTIVITY_AUTOMATION_PRESENT,
	MALFORMED_PROVIDER_ENVELOPE,
	PAYLOAD_TOO_LARGE,
	PAYLOAD_DECODE_FAILED,
	NON_CANONICAL_PAYLOAD,
	EMPTY_PROVIDER_DELIVERY,
	TOO_MANY_CHILDREN,
	IDENTITY_BEARING_CHILD,
	INVALID_PROVIDER_TIME,
	NON_CANONICAL_CHILD_ORDER,
	PROVIDER_TIMELINE_MISMATCH,
	DELIVERY_IDENTITY_MISMATCH,
	CAPTURE_AUTHORITY_MISMATCH,
	STRUCTURAL_DAY_MISMATCH,
	FACT_LINKAGE_MISMATCH,
	FACT_SEMANTICS_MISMATCH,
}

/**
 * Half-open provider or authority acceptance interval measured in elapsed-realtime nanoseconds.
 */
data class CellWalProviderInterval(
	val startInclusiveNanos: Long,
	val endExclusiveNanos: Long,
) {
	init {
		require(startInclusiveNanos >= 0L)
		require(endExclusiveNanos > startInclusiveNanos)
	}

	/**
	 * Tests membership in this half-open elapsed-realtime interval, in nanoseconds.
	 */
	operator fun contains(value: Long): Boolean =
		value >= startInclusiveNanos && value < endExclusiveNanos
}

/**
 * Original Cell session/source/policy/consent/data ownership and provider acceptance boundaries
 * used to qualify each reading.
 */
@Suppress("LongParameterList")
data class CellWalCaptureAuthority(
	val logicalTrackingId: String,
	val serviceRunId: String,
	val sourceInstanceId: String,
	val registrationGeneration: Long,
	val configurationRevision: Long,
	val physicalConfigurationFingerprint: String,
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
	val purposeEligibilityMask: Long,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val manifestRevision: Long,
	val lifecycleLeaseGeneration: Long,
	val collectedDataEpoch: Long,
	val clockDomainId: String,
	val zoneId: String,
	val structuralEpochDay: Long,
	val providerAcceptance: CellWalProviderInterval,
	val authorizationEffect: CellWalProviderInterval,
	val consentEffect: CellWalProviderInterval,
	val sessionRunEffect: CellWalProviderInterval,
	val deletionEffect: CellWalProviderInterval,
	val maximumObservationAgeNanos: Long,
)

/**
 * Per-reading admission outcome; rejected siblings do not determine whether other Cell readings
 * qualify.
 */
enum class CellWalChildDisposition {
	ACCEPTED,
	STALE,
	FUTURE_TIME,
	UNSUPPORTED_TECHNOLOGY,
}

/**
 * One original Cell reading index with its admission outcome and available provider/UTC bounds.
 */
data class CellWalQualifiedChild(
	val observation: CellWalPayloadObservation,
	val disposition: CellWalChildDisposition,
)

/**
 * Counts of qualified Cell technologies and signal-quality categories without retaining tower
 * identities.
 */
@Suppress("LongParameterList")
data class CellWalIdentityFreeAggregate(
	val observationCount: Int,
	val registeredObservationCount: Int,
	val gsmCount: Int,
	val cdmaCount: Int,
	val wcdmaCount: Int,
	val tdscdmaCount: Int,
	val lteCount: Int,
	val nrCount: Int,
	val qualityUnknownCount: Int,
	val qualityNoneOrUnknownCount: Int,
	val qualityPoorCount: Int,
	val qualityModerateCount: Int,
	val qualityGoodCount: Int,
	val qualityGreatCount: Int,
	val weakObservationCount: Int,
	val knownQualityObservationCount: Int,
	val allKnownQualityIsWeak: Boolean,
)

/**
 * Per-reading Cell outcomes, qualified aggregate and nullable measured coverage/UTC uncertainty
 * bounds.
 */
data class CellWalChildQualification(
	val children: List<CellWalQualifiedChild>,
	val coverageIntervalStartNanos: Long?,
	val coverageIntervalEndNanos: Long?,
	val observedWallTimeMs: Long?,
	val providerWallInterval: LongRange?,
	val aggregate: CellWalIdentityFreeAggregate?,
) {
	val observedWallTimeUncertaintyMs: Long? = providerWallInterval?.let { interval ->
		observedWallTimeMs?.let { observed -> Math.subtractExact(interval.last, observed) }
	}
	val submittedChildCount: Int = children.size
	val acceptedChildCount: Int = children.count { it.disposition == CellWalChildDisposition.ACCEPTED }
	val staleChildCount: Int = children.count { it.disposition == CellWalChildDisposition.STALE }
	val futureTimeChildCount: Int =
		children.count { it.disposition == CellWalChildDisposition.FUTURE_TIME }
	val unsupportedTechnologyChildCount: Int =
		children.count { it.disposition == CellWalChildDisposition.UNSUPPORTED_TECHNOLOGY }
	val isComplete: Boolean = acceptedChildCount == submittedChildCount
}

/**
 * Original decoded Cell delivery after envelope checks, with optional per-reading qualification
 * for a recorded purpose.
 */
data class AuthenticatedCellWalCarrier internal constructor(
	val deliveryIdentity: String,
	val payload: CellWalPayload,
	val childQualification: CellWalChildQualification?,
	internal val admissionOrdinal: Long,
	internal val observedElapsedNanos: Long,
	internal val receivedElapsedNanos: Long,
	internal val observedWallTimeMs: Long,
	internal val wallTimeUncertaintyMs: Long,
)

/**
 * Typed Cell carrier comparison result with the exact rejection cause.
 */
sealed interface CellWalCarrierAuthentication {
	/**
	 * Successful original-carrier comparison containing its checked, decoded measurement payload.
	 */
	data class Authenticated(
		val carrier: AuthenticatedCellWalCarrier,
	) : CellWalCarrierAuthentication

	/**
	 * Specific rejected decode, carrier or per-purpose qualification outcome; it carries no accepted
	 * payload.
	 */
	data class Rejected(val reason: CellWalCarrierFailure) : CellWalCarrierAuthentication
}

/**
 * Single Cell WAL predicate shared by capture, materialization, history, and exact proof queries.
 */
object CellWalCarrierAuthenticator {
	/**
	 * Returns the uncertainty-adjusted UTC millisecond coverage start; inconsistent anchors return
	 * null.
	 */
	fun retentionLowerBound(
		fact: CellCapturedFactRevisionEntity,
		carrier: AuthenticatedCellWalCarrier,
	): Long? = CanonicalRetentionBounds.cellCoverage(
		walObservedWallTimeMs = carrier.observedWallTimeMs,
		wallTimeUncertaintyMs = carrier.wallTimeUncertaintyMs,
		walObservedElapsedRealtimeNanos = carrier.observedElapsedNanos,
		coverageStartElapsedRealtimeNanos = fact.coverageIntervalStartNanos,
	)

	/**
	 * Maps the original elapsed coverage start to UTC milliseconds using the saved observation
	 * anchor; invalid bounds return null.
	 */
	fun coverageStartWallTime(
		fact: CellCapturedFactRevisionEntity,
		carrier: AuthenticatedCellWalCarrier,
	): Long? = CanonicalRetentionBounds.cellCoverageStartWallTime(
		walObservedWallTimeMs = carrier.observedWallTimeMs,
		walObservedElapsedRealtimeNanos = carrier.observedElapsedNanos,
		coverageStartElapsedRealtimeNanos = fact.coverageIntervalStartNanos,
	)

	/**
	 * Checks the original Cell WAL checksum, provider envelope, supported payload and recorded
	 * capture binding; reports the exact rejection cause.
	 */
	@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
	fun authenticate(
		wal: SourceEventWalEntity,
		expectedAuthority: CellWalCaptureAuthority? = null,
		qualifiedItemIndexes: Set<Int>? = null,
	): CellWalCarrierAuthentication {
		if (wal.sourceKind != SourceDestinationOwnerEntity.SOURCE_CELL) {
			return rejected(CellWalCarrierFailure.WRONG_SOURCE)
		}
		if (wal.payloadVersion !in LEGACY_CELL_WAL_PAYLOAD_VERSION..CELL_WAL_PAYLOAD_VERSION) {
			return rejected(CellWalCarrierFailure.UNSUPPORTED_PAYLOAD_VERSION)
		}
		if (!LOWERCASE_SHA_256.matches(wal.payloadChecksum) ||
			wal.payloadChecksum != wal.calculatedPayloadChecksum()
		) {
			return rejected(CellWalCarrierFailure.PAYLOAD_INTEGRITY_MISMATCH)
		}
		if (!LOWERCASE_SHA_256.matches(wal.integrityIdentity) ||
			wal.integrityIdentity != wal.calculatedIntegrityIdentity()
		) {
			return rejected(CellWalCarrierFailure.WAL_INTEGRITY_MISMATCH)
		}
		if (wal.providerDedupKey != null) {
			return rejected(CellWalCarrierFailure.PROVIDER_DEDUP_KEY_PRESENT)
		}
		if (wal.activityAutomationEpoch != null) {
			return rejected(CellWalCarrierFailure.ACTIVITY_AUTOMATION_PRESENT)
		}
		if (wal.payloadVersion == LEGACY_CELL_WAL_PAYLOAD_VERSION && !wal.hasCapturePurpose()) {
			return rejected(CellWalCarrierFailure.NON_CAPTURE_PURPOSE)
		}
		if (wal.payloadVersion == LEGACY_CELL_WAL_PAYLOAD_VERSION && !wal.hasCompleteCaptureBinding()) {
			return rejected(CellWalCarrierFailure.INCOMPLETE_CAPTURE_BINDING)
		}
		if (!wal.hasCanonicalProviderEnvelope()) {
			return rejected(CellWalCarrierFailure.MALFORMED_PROVIDER_ENVELOPE)
		}
		val payload = when (val decoded = CellWalPayloadCodec.decodeCanonical(
			wal.payloadVersion,
			wal.payload,
		)) {
			is CellWalPayloadDecodeResult.Decoded -> decoded.payload
			is CellWalPayloadDecodeResult.Rejected -> return rejected(decoded.reason.toCarrierFailure())
		}
		if (payload.subscriptionId != null ||
			payload.refreshOutcomeCode != CELL_REFRESH_OUTCOME_CALLBACK
		) {
			return rejected(CellWalCarrierFailure.MALFORMED_PROVIDER_ENVELOPE)
		}
		if (payload.observations.isEmpty()) {
			return rejected(CellWalCarrierFailure.EMPTY_PROVIDER_DELIVERY)
		}
		if (payload.observations.size > MAX_CELL_WAL_CHILDREN) {
			return rejected(CellWalCarrierFailure.TOO_MANY_CHILDREN)
		}
		if (wal.payloadVersion == LEGACY_CELL_WAL_PAYLOAD_VERSION &&
			payload.observations.any { it.identifierToken.isNotEmpty() }
		) {
			return rejected(CellWalCarrierFailure.IDENTITY_BEARING_CHILD)
		}
		if (payload.observations.any { !it.hasValidCellCarrierShape() }
		) {
			return rejected(CellWalCarrierFailure.INVALID_PROVIDER_TIME)
		}
		if (payload.observations != payload.observations.sortedWith(CELL_WAL_OBSERVATION_ORDER)) {
			return rejected(CellWalCarrierFailure.NON_CANONICAL_CHILD_ORDER)
		}
		if (wal.deliveryIdentity?.let(LOWERCASE_SHA_256::matches) != true) {
			return rejected(CellWalCarrierFailure.INCOMPLETE_CAPTURE_BINDING)
		}
		val providerTimes = payload.observations.map {
			requireNotNull(it.providerTimestampNanos)
		}
		if (providerTimes.minOrNull() != wal.observedIntervalStartNanos ||
			providerTimes.maxOrNull() != wal.observedElapsedNanos
		) {
			return rejected(CellWalCarrierFailure.PROVIDER_TIMELINE_MISMATCH)
		}
		val deliveryIdentity = runCatching {
			canonicalCellProviderDeliveryIdentity(
				wal.clockDomainId,
				payload.observations.map { observation ->
					CellProviderDeliveryIdentityFact(
						radioType = observation.radioType,
						registered = observation.registered,
						signalLevelDbm = observation.signalLevelDbm,
						providerTimestampNanos = requireNotNull(
							observation.providerTimestampNanos,
						),
						identifierToken = observation.identifierToken,
					)
				},
				wal.payloadVersion,
			)
		}.getOrNull()
		if (deliveryIdentity == null || deliveryIdentity != wal.deliveryIdentity) {
			return rejected(CellWalCarrierFailure.DELIVERY_IDENTITY_MISMATCH)
		}
		if (expectedAuthority != null && !wal.matches(expectedAuthority, payload)) {
			return rejected(CellWalCarrierFailure.CAPTURE_AUTHORITY_MISMATCH)
		}
		if (qualifiedItemIndexes?.any { it !in payload.observations.indices } == true ||
			(qualifiedItemIndexes != null && wal.payloadVersion != CELL_WAL_PAYLOAD_VERSION)
		) {
			return rejected(CellWalCarrierFailure.CAPTURE_AUTHORITY_MISMATCH)
		}
		val qualification = expectedAuthority?.let { authority ->
			qualifyCellWalChildren(wal, payload, authority, qualifiedItemIndexes)
				?: return rejected(CellWalCarrierFailure.STRUCTURAL_DAY_MISMATCH)
		}
		return CellWalCarrierAuthentication.Authenticated(
			AuthenticatedCellWalCarrier(
				deliveryIdentity = deliveryIdentity,
				payload = payload,
				childQualification = qualification,
				admissionOrdinal = wal.admissionOrdinal,
				observedElapsedNanos = wal.observedElapsedNanos,
				receivedElapsedNanos = wal.receivedElapsedNanos,
				observedWallTimeMs = requireNotNull(wal.wallTimeMs),
				wallTimeUncertaintyMs = requireNotNull(wal.wallTimeUncertaintyMs),
			),
		)
	}

	/**
	 * Checks the saved Cell revision against its original WAL carrier, ownership, measured values and
	 * supplied child/count-domain evidence.
	 */
	fun authenticate(
		fact: CellCapturedFactRevisionEntity,
		wal: SourceEventWalEntity,
		qualifiedItemIndexes: Set<Int>? = null,
	): CellWalCarrierAuthentication {
		val authenticated = authenticate(wal, fact.captureAuthority(), qualifiedItemIndexes)
		if (authenticated !is CellWalCarrierAuthentication.Authenticated) {
			return authenticated
		}
		if (!fact.hasExactWalLink(wal, authenticated.carrier)) {
			return rejected(CellWalCarrierFailure.FACT_LINKAGE_MISMATCH)
		}
		if (!fact.hasExactPayloadSemantics(authenticated.carrier)) {
			return rejected(CellWalCarrierFailure.FACT_SEMANTICS_MISMATCH)
		}
		return authenticated
	}

	/** History and export must prove that every displayed v6 tower belongs to this exact fact. */
	fun authenticate(
		fact: CellCapturedFactRevisionEntity,
		wal: SourceEventWalEntity,
		children: List<CellCapturedChildEntity>,
	): CellWalCarrierAuthentication {
		val authenticated = authenticate(fact, wal,
			children.map(CellCapturedChildEntity::itemIndex).toSet()
				.takeIf { wal.payloadVersion == CELL_WAL_PAYLOAD_VERSION })
		if (authenticated !is CellWalCarrierAuthentication.Authenticated) {
			return authenticated
		}
		if (wal.payloadVersion == LEGACY_CELL_WAL_PAYLOAD_VERSION && children.isEmpty()) {
			return authenticated
		}
		if (wal.payloadVersion != CELL_WAL_PAYLOAD_VERSION ||
			!children.authenticateFor(fact, wal, authenticated.carrier)
		) {
			return rejected(CellWalCarrierFailure.FACT_SEMANTICS_MISMATCH)
		}
		return authenticated
	}

	/**
	 * Returns whether the saved Cell fact and supplied original carrier pass the complete carrier
	 * comparison.
	 */
	fun authenticates(
		fact: CellCapturedFactRevisionEntity,
		wal: SourceEventWalEntity,
	): Boolean = authenticate(fact, wal) is CellWalCarrierAuthentication.Authenticated

	/**
	 * Checks that aggregate values and original owner revision match the qualified Cell carrier and
	 * any dependent coverage-only fact.
	 */
	fun authenticatesAggregate(
		aggregateFact: CellCapturedFactRevisionEntity,
		carrier: AuthenticatedCellWalCarrier,
	): Boolean = carrier.childQualification?.aggregate?.let { aggregate ->
		aggregateFact.matchesAggregate(aggregate)
	} == true

	/**
	 * Checks that aggregate values and original owner revision match the qualified Cell carrier and
	 * any dependent coverage-only fact.
	 */
	// One saved aggregate/dependent ownership tuple; the carrier comparison also reads fixed fields only.
	@Suppress("ComplexCondition", "CyclomaticComplexMethod")
	fun authenticatesAggregate(
		aggregateFact: CellCapturedFactRevisionEntity,
		carrier: AuthenticatedCellWalCarrier,
		dependentFact: CellCapturedFactRevisionEntity,
	): Boolean = authenticatesAggregate(aggregateFact, carrier) &&
		dependentFact.factKind == CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY &&
		dependentFact.aggregateOwnerLogicalFactId == aggregateFact.logicalFactId &&
		dependentFact.aggregateOwnerSemanticRevision == aggregateFact.semanticRevision &&
		aggregateFact.factKind == CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE &&
		aggregateFact.writerProjectionId == dependentFact.writerProjectionId &&
		aggregateFact.writerProjectionVersion == dependentFact.writerProjectionVersion &&
		aggregateFact.writerBindingGeneration == dependentFact.writerBindingGeneration &&
		aggregateFact.writerOwnerGeneration == dependentFact.writerOwnerGeneration &&
		aggregateFact.providerAcceptanceEndNanos != Long.MAX_VALUE &&
		aggregateFact.authorizationEffectEndNanos != Long.MAX_VALUE &&
		aggregateFact.consentEffectEndNanos != Long.MAX_VALUE &&
		aggregateFact.sessionRunEffectEndNanos != Long.MAX_VALUE &&
		aggregateFact.deletionEffectEndNanos != Long.MAX_VALUE

}

private fun CellWalPayloadObservation.hasValidCellCarrierShape(): Boolean = radioType.isNotBlank() &&
	radioType == radioType.trim() && radioType == radioType.uppercase() &&
	providerTimestampNanos != null && requireNotNull(providerTimestampNanos) > 0L

// Missing qualification, mismatched membership and missing anchors reject before per-child reconstruction.
@Suppress("ReturnCount")
private fun List<CellCapturedChildEntity>.authenticateFor(
	fact: CellCapturedFactRevisionEntity,
	wal: SourceEventWalEntity,
	carrier: AuthenticatedCellWalCarrier,
): Boolean {
	val acceptedIndexes = carrier.childQualification?.children?.withIndex()
		?.filter { it.value.disposition == CellWalChildDisposition.ACCEPTED }
		?.map { it.index }?.toSet() ?: return false
	if (isEmpty() || size != fact.acceptedChildCount || size > MAX_CELL_WAL_CHILDREN ||
		map(CellCapturedChildEntity::itemIndex).toSet() != acceptedIndexes
	) {
		return false
	}
	val wallAnchor = wal.wallTimeMs ?: return false
	val uncertaintyAnchor = wal.wallTimeUncertaintyMs ?: return false
	return all { child ->
		val input = child.authenticatedChecksumInput(fact, wal, carrier, wallAnchor, uncertaintyAnchor)
			?: return@all false
		child.matchesAuthenticatedInput(input)
	}
}

// Missing child/time, foreign ownership or unrepresentable UTC bounds reject before checksum construction.
@Suppress("ReturnCount")
private fun CellCapturedChildEntity.authenticatedChecksumInput(
	fact: CellCapturedFactRevisionEntity,
	wal: SourceEventWalEntity,
	carrier: AuthenticatedCellWalCarrier,
	wallAnchor: Long,
	uncertaintyAnchor: Long,
): CellChildChecksumInput? {
	val item = carrier.payload.observations.getOrNull(itemIndex) ?: return null
	val providerTime = item.providerTimestampNanos ?: return null
	val age = wal.observedElapsedNanos - providerTime
	if (age < 0L || !hasExactCellCarrierOwner(fact)) {
		return null
	}
	val wall = runCatching { Math.subtractExact(wallAnchor, age / NANOS_PER_MILLISECOND) }
		.getOrNull() ?: return null
	val uncertainty = runCatching { Math.addExact(uncertaintyAnchor,
		if (age % NANOS_PER_MILLISECOND == 0L) {
			0L
		} else {
			1L
		}) }
		.getOrNull() ?: return null
	val identity = item.identifierToken.ifEmpty { null }
	return CellChildChecksumInput(
		RadioChildOwner(RadioChildPurpose.SESSION, fact.writerProjectionId,
			fact.writerProjectionVersion, fact.logicalFactId, fact.semanticRevision, itemIndex),
		providerTime, wall, uncertainty, identity, item.radioType,
		item.registered, item.signalLevelDbm,
	)
}

private fun CellCapturedChildEntity.hasExactCellCarrierOwner(fact: CellCapturedFactRevisionEntity): Boolean =
	writerProjectionId == fact.writerProjectionId && writerProjectionVersion == fact.writerProjectionVersion &&
	logicalFactId == fact.logicalFactId && semanticRevision == fact.semanticRevision

private fun CellCapturedChildEntity.matchesAuthenticatedInput(input: CellChildChecksumInput): Boolean =
	providerTimestampNanos == input.providerTimestampNanos &&
		observedWallTimeMs == input.observedWallTimeMs &&
		wallTimeUncertaintyMs == input.wallTimeUncertaintyMs &&
		towerIdentity == input.towerIdentity &&
		radioType == input.radioType &&
		registered == input.registered &&
		signalDbm == input.signalDbm &&
		contentChecksum == RadioObservationChildIntegrity.cell(input)

private fun CellWalPayloadDecodeFailure.toCarrierFailure(): CellWalCarrierFailure = when (this) {
	CellWalPayloadDecodeFailure.UNSUPPORTED_PAYLOAD_VERSION ->
		CellWalCarrierFailure.UNSUPPORTED_PAYLOAD_VERSION
	CellWalPayloadDecodeFailure.PAYLOAD_TOO_LARGE -> CellWalCarrierFailure.PAYLOAD_TOO_LARGE
	CellWalPayloadDecodeFailure.MALFORMED_PAYLOAD -> CellWalCarrierFailure.PAYLOAD_DECODE_FAILED
	CellWalPayloadDecodeFailure.NON_CANONICAL_PAYLOAD ->
		CellWalCarrierFailure.NON_CANONICAL_PAYLOAD
}

private fun SourceEventWalEntity.hasCapturePurpose(): Boolean =
	authorizationPurposeEligibilityMask and SourceBrokerPurpose.ALL_MASK ==
		authorizationPurposeEligibilityMask &&
		authorizationPurposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L

// One persisted capture tuple; keep every required field visible in its short-circuit contract.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun SourceEventWalEntity.hasCompleteCaptureBinding(): Boolean =
	logicalTrackingId?.isNotBlank() == true &&
		serviceRunId?.isNotBlank() == true &&
		sourceInstanceId.isNotBlank() &&
		registrationGeneration > 0L &&
		configRevision?.let { it > 0L } == true &&
		physicalConfigurationFingerprint?.isNotBlank() == true &&
		authorizationRevision?.let { it > 0L } == true &&
		authorizationFingerprint?.let(LOWERCASE_SHA_256::matches) == true &&
		sourcePolicyRevision?.let { it > 0L } == true &&
		captureConsentEpoch?.let { it >= 0L } == true &&
		sessionManifestRevision?.let { it > 0L } == true &&
		lifecycleLeaseGeneration?.let { it > 0L } == true &&
		capturedCollectedDataEpoch >= 0L &&
		clockDomainId.isNotBlank()

// One callback envelope; the version-specific binding has its own predicate below.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun SourceEventWalEntity.hasCanonicalProviderEnvelope(): Boolean =
	admissionOrdinal > 0L &&
		eventId.isNotBlank() &&
		deliveryUnitIndex == 0 &&
		deliveryUnitCount == 1 &&
		sourceSequence >= 0L &&
		(if (payloadVersion == CELL_WAL_PAYLOAD_VERSION) {
			hasCanonicalPerReadingBinding()
		} else
			{
			planAttribution == CAPTURED_REGISTRATION_PLAN_ORDINAL
		}) &&
		observedIntervalStartNanos?.let { it > 0L } == true &&
		observedElapsedNanos >= requireNotNull(observedIntervalStartNanos) &&
		receivedElapsedNanos >= observedElapsedNanos &&
		receivedWallTimeMs?.let { it >= 0L } != false &&
		wallTimeMs?.let { it >= 0L } == true &&
		wallTimeUncertaintyMs == CELL_PROVIDER_WALL_UNCERTAINTY_MS &&
		acquiredAtMs == wallTimeMs &&
		createdAtMs >= 0L &&
		qualityFlags == 0L &&
		qualityConfidence == null

// One authority projection with explicit legacy/v6 field ownership; avoid a second mapping model.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun SourceEventWalEntity.matches(
	authority: CellWalCaptureAuthority,
	payload: CellWalPayload,
): Boolean =
	(payloadVersion == CELL_WAL_PAYLOAD_VERSION || (
		logicalTrackingId == authority.logicalTrackingId && serviceRunId == authority.serviceRunId &&
		configRevision == authority.configurationRevision &&
		authorizationRevision == authority.authorizationRevision &&
		authorizationFingerprint == authority.authorizationFingerprint &&
		authorizationPurposeEligibilityMask == authority.purposeEligibilityMask &&
		sourcePolicyRevision == authority.sourcePolicyRevision &&
		captureConsentEpoch == authority.captureConsentEpoch &&
		sessionManifestRevision == authority.manifestRevision &&
		lifecycleLeaseGeneration == authority.lifecycleLeaseGeneration)) &&
		sourceInstanceId == authority.sourceInstanceId &&
		registrationGeneration == authority.registrationGeneration &&
		physicalConfigurationFingerprint == authority.physicalConfigurationFingerprint &&
		capturedCollectedDataEpoch == authority.collectedDataEpoch &&
		clockDomainId == authority.clockDomainId &&
		(payloadVersion == CELL_WAL_PAYLOAD_VERSION || payload.observationZoneId == authority.zoneId)

/** v6 records the real callback, including a denied callback after historical capture ended. */
// The two persisted header shapes share fixed nullability, mask and fingerprint requirements.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun SourceEventWalEntity.hasCanonicalPerReadingBinding(): Boolean {
	if (sourceInstanceId.isBlank() || registrationGeneration <= 0L ||
		configRevision?.let { it > 0L } != true || physicalConfigurationFingerprint.isNullOrBlank() ||
		authorizationRevision?.let { it > 0L } != true ||
		authorizationFingerprint?.let(LOWERCASE_SHA_256::matches) != true ||
		authorizationPurposeEligibilityMask and SourceBrokerPurpose.ALL_MASK != authorizationPurposeEligibilityMask ||
		capturedCollectedDataEpoch < 0L || clockDomainId.isBlank()
	) {
		return false
	}
	return if (hasCapturePurpose()) {
		hasCompleteCaptureBinding() &&
		planAttribution == CAPTURED_REGISTRATION_PLAN_ORDINAL
	} else
		{
		listOf(logicalTrackingId, serviceRunId, sourcePolicyRevision, captureConsentEpoch,
			sessionManifestRevision, lifecycleLeaseGeneration).all { it == null } &&
			planAttribution == RECEIVE_TIME_ONLY_PLAN_ORDINAL
	}
}

internal fun CellWalCaptureAuthority.containsQualifiedCellTime(providerTimeNanos: Long): Boolean =
	providerTimeNanos in providerAcceptance &&
		providerTimeNanos in authorizationEffect &&
		providerTimeNanos in consentEffect &&
		providerTimeNanos in sessionRunEffect &&
		providerTimeNanos in deletionEffect

internal fun AuthenticatedCellWalCarrier.cellCarrierProviderWallTime(providerTimeNanos: Long): Long? {
	val ageNanos = observedElapsedNanos - providerTimeNanos
	if (ageNanos < 0L) {
		return null
	}
	val ageMs = ageNanos / NANOS_PER_MILLISECOND
	return observedWallTimeMs.takeIf { it >= ageMs }?.minus(ageMs)
}

private fun CellCapturedFactRevisionEntity.captureAuthority() = CellWalCaptureAuthority(
	logicalTrackingId = logicalTrackingId,
	serviceRunId = serviceRunId,
	sourceInstanceId = sourceInstanceId,
	registrationGeneration = registrationGeneration,
	configurationRevision = configurationRevision,
	physicalConfigurationFingerprint = physicalConfigurationFingerprint,
	authorizationRevision = authorizationRevision,
	authorizationFingerprint = authorizationFingerprint,
	purposeEligibilityMask = purposeEligibilityMask,
	sourcePolicyRevision = sourcePolicyRevision,
	captureConsentEpoch = captureConsentEpoch,
	manifestRevision = manifestRevision,
	lifecycleLeaseGeneration = lifecycleLeaseGeneration,
	collectedDataEpoch = collectedDataEpoch,
	clockDomainId = clockDomainId,
	zoneId = storedZoneId,
	structuralEpochDay = structuralEpochDay,
	providerAcceptance = CellWalProviderInterval(
		providerAcceptanceStartNanos,
		providerAcceptanceEndNanos,
	),
	authorizationEffect = CellWalProviderInterval(
		authorizationEffectStartNanos,
		authorizationEffectEndNanos,
	),
	consentEffect = CellWalProviderInterval(consentEffectStartNanos, consentEffectEndNanos),
	sessionRunEffect = CellWalProviderInterval(
		sessionRunEffectStartNanos,
		sessionRunEffectEndNanos,
	),
	deletionEffect = CellWalProviderInterval(deletionEffectStartNanos, deletionEffectEndNanos),
	maximumObservationAgeNanos = maximumObservationAgeNanos,
)

// One fact-to-carrier field mapping; keeping the complete list together exposes missing links.
@Suppress("ComplexCondition", "LongMethod", "CyclomaticComplexMethod")
private fun CellCapturedFactRevisionEntity.hasExactWalLink(
	wal: SourceEventWalEntity,
	carrier: AuthenticatedCellWalCarrier,
): Boolean =
	purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
		payloadVersion == wal.payloadVersion &&
		payloadVersion in LEGACY_CELL_WAL_PAYLOAD_VERSION..CELL_WAL_PAYLOAD_VERSION &&
		sourceEventId == wal.eventId &&
		sourceAdmissionOrdinal == wal.admissionOrdinal &&
		walIntegrityIdentity == wal.integrityIdentity &&
		payloadChecksum == wal.payloadChecksum &&
		sourceDeliveryIdentity == carrier.deliveryIdentity &&
		sourceDeliveryIdentity == wal.deliveryIdentity &&
		canonicalProviderSemanticsDigest == carrier.deliveryIdentity &&
		deliveryUnitIndex == wal.deliveryUnitIndex &&
		deliveryUnitCount == wal.deliveryUnitCount &&
		sourceSequence == wal.sourceSequence &&
		(payloadVersion == CELL_WAL_PAYLOAD_VERSION || (
			logicalTrackingId == wal.logicalTrackingId && serviceRunId == wal.serviceRunId &&
			configurationRevision == wal.configRevision && authorizationRevision == wal.authorizationRevision &&
			authorizationFingerprint == wal.authorizationFingerprint &&
			purposeEligibilityMask == wal.authorizationPurposeEligibilityMask &&
			sourcePolicyRevision == wal.sourcePolicyRevision && captureConsentEpoch == wal.captureConsentEpoch &&
			manifestRevision == wal.sessionManifestRevision && lifecycleLeaseGeneration == wal.lifecycleLeaseGeneration)) &&
		sourceInstanceId == wal.sourceInstanceId &&
		registrationGeneration == wal.registrationGeneration &&
		physicalConfigurationFingerprint == wal.physicalConfigurationFingerprint &&
		collectedDataEpoch == wal.capturedCollectedDataEpoch &&
		clockDomainId == wal.clockDomainId &&
		(payloadVersion == CELL_WAL_PAYLOAD_VERSION || storedZoneId == carrier.payload.observationZoneId) &&
		observedIntervalStartNanos == wal.observedIntervalStartNanos &&
		observedElapsedNanos == wal.observedElapsedNanos &&
		receivedElapsedNanos == wal.receivedElapsedNanos &&
		wallTimeUncertaintyMs == carrier.childQualification?.observedWallTimeUncertaintyMs &&
		acquiredAtMs == wal.acquiredAtMs &&
		createdAtMs == wal.createdAtMs &&
		qualityFlags == wal.qualityFlags &&
		qualityConfidence == wal.qualityConfidence &&
		planAttribution == CellCapturedFactRevisionEntity.PLAN_ATTRIBUTION_CAPTURED_REGISTRATION &&
		(payloadVersion == CELL_WAL_PAYLOAD_VERSION || wal.planAttribution == CAPTURED_REGISTRATION_PLAN_ORDINAL)

// Compare the complete saved fact shape, keeping nullable qualification exits with its fields.
@Suppress("ComplexCondition", "LongMethod", "CyclomaticComplexMethod", "ReturnCount")
private fun CellCapturedFactRevisionEntity.hasExactPayloadSemantics(
	carrier: AuthenticatedCellWalCarrier,
): Boolean {
	val qualification = carrier.childQualification ?: return false
	val aggregate = qualification.aggregate ?: return false
	if (observedWallTimeMs != qualification.observedWallTimeMs ||
		coverageIntervalStartNanos != qualification.coverageIntervalStartNanos ||
		coverageIntervalEndNanos != qualification.coverageIntervalEndNanos ||
		submittedChildCount != qualification.submittedChildCount ||
		acceptedChildCount != qualification.acceptedChildCount ||
		staleChildCount != qualification.staleChildCount ||
		futureTimeChildCount != qualification.futureTimeChildCount ||
		missingTimeChildCount != 0 ||
		clockUnverifiableChildCount != 0 ||
		authorityMismatchChildCount != 0 ||
		unsupportedTechnologyChildCount != qualification.unsupportedTechnologyChildCount ||
		childCompleteness != if (qualification.isComplete) {
			CellCapturedFactRevisionEntity.CHILD_COMPLETENESS_COMPLETE
		} else {
			CellCapturedFactRevisionEntity.CHILD_COMPLETENESS_PARTIAL
		}
	) {
		return false
	}
	if (factKind == CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY) {
		return true
	}
	return factKind == CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE && matchesAggregate(aggregate)
}

// Compare only the 17 aggregate fields; full entity equality would include unrelated ownership.
@Suppress("CyclomaticComplexMethod")
private fun CellCapturedFactRevisionEntity.matchesAggregate(
	aggregate: CellWalIdentityFreeAggregate,
): Boolean =
	observationCount == aggregate.observationCount &&
		registeredObservationCount == aggregate.registeredObservationCount &&
		gsmCount == aggregate.gsmCount &&
		cdmaCount == aggregate.cdmaCount &&
		wcdmaCount == aggregate.wcdmaCount &&
		tdscdmaCount == aggregate.tdscdmaCount &&
		lteCount == aggregate.lteCount &&
		nrCount == aggregate.nrCount &&
		qualityUnknownCount == aggregate.qualityUnknownCount &&
		qualityNoneOrUnknownCount == aggregate.qualityNoneOrUnknownCount &&
		qualityPoorCount == aggregate.qualityPoorCount &&
		qualityModerateCount == aggregate.qualityModerateCount &&
		qualityGoodCount == aggregate.qualityGoodCount &&
		qualityGreatCount == aggregate.qualityGreatCount &&
		weakObservationCount == aggregate.weakObservationCount &&
		knownQualityObservationCount == aggregate.knownQualityObservationCount &&
		allKnownQualityIsWeak == aggregate.allKnownQualityIsWeak

internal fun List<CellWalPayloadObservation>.toQualifiedCellAggregate(): CellWalIdentityFreeAggregate {
	val technologyCounts = groupingBy(CellWalPayloadObservation::radioType).eachCount().withDefault { 0 }
	val qualityCounts = groupingBy { it.signalLevelDbm.toQualityLevel() }.eachCount().withDefault { 0 }
	val unknown = qualityCounts.getValue(null)
	val noneOrUnknown = qualityCounts.getValue(CELL_QUALITY_NONE_OR_UNKNOWN)
	val poor = qualityCounts.getValue(CELL_QUALITY_POOR)
	val moderate = qualityCounts.getValue(CELL_QUALITY_MODERATE)
	val good = qualityCounts.getValue(CELL_QUALITY_GOOD)
	val great = qualityCounts.getValue(CELL_QUALITY_GREAT)
	val weak = noneOrUnknown + poor
	val known = size - unknown
	return CellWalIdentityFreeAggregate(
		observationCount = size,
		registeredObservationCount = count(CellWalPayloadObservation::registered),
		gsmCount = technologyCounts.getValue("GSM"),
		cdmaCount = technologyCounts.getValue("CDMA"),
		wcdmaCount = technologyCounts.getValue("WCDMA"),
		tdscdmaCount = technologyCounts.getValue("TDSCDMA"),
		lteCount = technologyCounts.getValue("LTE"),
		nrCount = technologyCounts.getValue("NR"),
		qualityUnknownCount = unknown,
		qualityNoneOrUnknownCount = noneOrUnknown,
		qualityPoorCount = poor,
		qualityModerateCount = moderate,
		qualityGoodCount = good,
		qualityGreatCount = great,
		weakObservationCount = weak,
		knownQualityObservationCount = known,
		allKnownQualityIsWeak = known > 0 && weak == known,
	)
}

private fun Int?.toQualityLevel(): Int? = when {
	this == null || this == Int.MAX_VALUE -> null
	this <= CELL_NONE_OR_UNKNOWN_MAX_DBM -> CELL_QUALITY_NONE_OR_UNKNOWN
	this <= CELL_POOR_MAX_DBM -> CELL_QUALITY_POOR
	this <= CELL_MODERATE_MAX_DBM -> CELL_QUALITY_MODERATE
	this <= CELL_GOOD_MAX_DBM -> CELL_QUALITY_GOOD
	else -> CELL_QUALITY_GREAT
}

private fun rejected(reason: CellWalCarrierFailure) =
	CellWalCarrierAuthentication.Rejected(reason)

private val CELL_WAL_OBSERVATION_ORDER = compareBy<CellWalPayloadObservation>(
	CellWalPayloadObservation::radioType,
	CellWalPayloadObservation::identifierToken,
	CellWalPayloadObservation::registered,
	CellWalPayloadObservation::signalLevelDbm,
	CellWalPayloadObservation::providerTimestampNanos,
)
/**
 * Reports whether this reading uses one of the supported GSM/CDMA/WCDMA/TD-SCDMA/LTE/NR radio
 * types.
 */
fun CellWalPayloadObservation.hasSupportedCellProductShape(): Boolean =
	radioType in SUPPORTED_CELL_RADIO_TYPES

private val SUPPORTED_CELL_RADIO_TYPES = setOf("GSM", "CDMA", "WCDMA", "TDSCDMA", "LTE", "NR")
private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
private const val CAPTURED_REGISTRATION_PLAN_ORDINAL = 0
private const val RECEIVE_TIME_ONLY_PLAN_ORDINAL = 2
private const val CELL_PROVIDER_WALL_UNCERTAINTY_MS = 1L
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val CELL_QUALITY_NONE_OR_UNKNOWN = 0
private const val CELL_QUALITY_POOR = 1
private const val CELL_QUALITY_MODERATE = 2
private const val CELL_QUALITY_GOOD = 3
private const val CELL_QUALITY_GREAT = 4
private const val CELL_NONE_OR_UNKNOWN_MAX_DBM = -120
private const val CELL_POOR_MAX_DBM = -110
private const val CELL_MODERATE_MAX_DBM = -100
private const val CELL_GOOD_MAX_DBM = -90
