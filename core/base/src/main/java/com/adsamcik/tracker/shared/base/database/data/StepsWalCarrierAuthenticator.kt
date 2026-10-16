@file:Suppress("ComplexCondition", "LongMethod", "TooManyFunctions")

package com.adsamcik.tracker.shared.base.database.data

import com.adsamcik.tracker.shared.base.database.StepsCountDomainStoredOwner
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

const val STEPS_WAL_PAYLOAD_VERSION = 7
const val MINIMUM_STEPS_WAL_PAYLOAD_VERSION = 1
const val MAX_STEPS_WAL_PAYLOAD_BYTES = 4_096

/**
 * Original counter-window boundary: baseline, measured coverage, reset gap or legacy ambiguity.
 */
enum class StepsWalBoundaryKind {
	BASELINE,
	COVERED,
	COUNTER_RESET,
	LEGACY_AMBIGUOUS,
}

/**
 * Original cumulative counter window with elapsed nanosecond bounds, provider sequence and
 * optional count-domain token/epoch metadata.
 */
@Suppress("LongParameterList")
data class StepsWalPayload(
	val bootClockDomainId: String,
	val firstCumulativeCount: Long,
	val lastCumulativeCount: Long,
	val deltaCount: Long,
	val windowStartElapsedRealtimeNanos: Long,
	val windowEndElapsedRealtimeNanos: Long,
	val firstProviderSequence: Long,
	val lastProviderSequence: Long,
	val boundaryKind: StepsWalBoundaryKind,
	val counterEpochGeneration: Long?,
	val counterDomainToken: StepsCounterDomainToken?,
)

/**
 * Versioned bounded Steps binary encoding preserving older counter layouts and current
 * count-domain metadata.
 */
object StepsWalPayloadCodec {
	/**
	 * Serializes Steps payload values into the supported deterministic binary layout; nullable
	 * measurements keep their absence.
	 */
	fun encode(
		payload: StepsWalPayload,
		payloadVersion: Int = STEPS_WAL_PAYLOAD_VERSION,
	): ByteArray {
		requireValid(payload, payloadVersion)
		return ByteArrayOutputStream().use { buffer ->
			DataOutputStream(buffer).use { output ->
				output.writeInt(TYPE_STEP_WINDOW)
				output.writeUTF(payload.bootClockDomainId)
				output.writeLong(payload.firstCumulativeCount)
				output.writeLong(payload.lastCumulativeCount)
				output.writeLong(payload.deltaCount)
				output.writeLong(payload.windowStartElapsedRealtimeNanos)
				output.writeLong(payload.windowEndElapsedRealtimeNanos)
				output.writeLong(payload.firstProviderSequence)
				output.writeLong(payload.lastProviderSequence)
				if (payloadVersion >= STEP_BOUNDARY_KIND_PAYLOAD_VERSION) {
					output.writeInt(payload.boundaryKind.wireCode)
				} else {
					output.writeBoolean(
						payload.boundaryKind != StepsWalBoundaryKind.COVERED,
					)
				}
				if (payloadVersion >= STEP_COUNTER_EPOCH_GENERATION_PAYLOAD_VERSION) {
					output.writeLong(requireNotNull(payload.counterEpochGeneration))
					output.writeBoolean(payload.counterDomainToken != null)
					payload.counterDomainToken?.let { output.writeUTF(it.encoded) }
				} else if (payloadVersion >= STEP_COUNTER_DOMAIN_TOKEN_PAYLOAD_VERSION) {
					output.writeUTF(requireNotNull(payload.counterDomainToken).encoded)
				}
			}
			buffer.toByteArray()
		}
	}

	/**
	 * Decodes a bounded supported Steps payload and requires exact canonical bytes; malformed data is
	 * rejected through the declared result type.
	 */
	fun decodeCanonical(payloadVersion: Int, bytes: ByteArray): StepsWalPayload? =
		runCatching {
			require(payloadVersion in MINIMUM_STEPS_WAL_PAYLOAD_VERSION..STEPS_WAL_PAYLOAD_VERSION)
			require(bytes.isNotEmpty() && bytes.size <= MAX_STEPS_WAL_PAYLOAD_BYTES)
			val decoded = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
				require(input.readInt() == TYPE_STEP_WINDOW)
				StepsWalPayload(
					bootClockDomainId = input.readUTF(),
					firstCumulativeCount = input.readLong(),
					lastCumulativeCount = input.readLong(),
					deltaCount = input.readLong(),
					windowStartElapsedRealtimeNanos = input.readLong(),
					windowEndElapsedRealtimeNanos = input.readLong(),
					firstProviderSequence = input.readLong(),
					lastProviderSequence = input.readLong(),
					boundaryKind = input.readBoundaryKind(payloadVersion),
					counterEpochGeneration =
						if (payloadVersion >= STEP_COUNTER_EPOCH_GENERATION_PAYLOAD_VERSION) {
							input.readLong()
						} else {
							null
						},
					counterDomainToken = when {
						payloadVersion >= STEP_COUNTER_EPOCH_GENERATION_PAYLOAD_VERSION ->
							if (input.readBoolean()) {
								StepsCounterDomainToken.opaque(input.readUTF())
							} else {
								null
							}
						payloadVersion >= STEP_COUNTER_DOMAIN_TOKEN_PAYLOAD_VERSION ->
							StepsCounterDomainToken.opaque(input.readUTF())
						else -> null
					},
				).also {
					require(input.available() == 0)
					requireValid(it, payloadVersion)
				}
			}
			require(encode(decoded, payloadVersion).contentEquals(bytes))
			decoded
		}.getOrNull()

	// The boundary field uses a legacy reset flag before version 3 and stable integer tags thereafter.
	private fun DataInputStream.readBoundaryKind(payloadVersion: Int): StepsWalBoundaryKind =
		if (payloadVersion >= STEP_BOUNDARY_KIND_PAYLOAD_VERSION) {
			when (readInt()) {
				1 -> StepsWalBoundaryKind.BASELINE
				2 -> StepsWalBoundaryKind.COVERED
				STEP_COUNTER_RESET_WIRE_CODE -> StepsWalBoundaryKind.COUNTER_RESET
				else -> error("Unsupported Steps boundary code")
			}
		} else if (readBoolean()) {
			StepsWalBoundaryKind.LEGACY_AMBIGUOUS
		} else {
			StepsWalBoundaryKind.COVERED
		}

	private fun requireValid(payload: StepsWalPayload, payloadVersion: Int) {
		require(payloadVersion in MINIMUM_STEPS_WAL_PAYLOAD_VERSION..STEPS_WAL_PAYLOAD_VERSION)
		require(
			(payloadVersion >= STEP_COUNTER_EPOCH_GENERATION_PAYLOAD_VERSION) ==
				(payload.counterEpochGeneration != null),
		)
		payload.counterEpochGeneration?.let { require(it > 0L) }
		require(
			(payloadVersion >= STEP_COUNTER_DOMAIN_TOKEN_PAYLOAD_VERSION) ||
				payload.counterDomainToken == null,
		)
		if (payloadVersion == STEP_COUNTER_DOMAIN_TOKEN_PAYLOAD_VERSION) {
			require(payload.counterDomainToken != null)
		}
		if (payloadVersion >= STEP_BOUNDARY_KIND_PAYLOAD_VERSION) {
			require(payload.boundaryKind != StepsWalBoundaryKind.LEGACY_AMBIGUOUS)
		}
	}
}

/**
 * Specific Steps envelope, payload, ownership and count-domain comparison failures.
 */
enum class StepsWalCarrierFailure {
	WRONG_SOURCE,
	UNSUPPORTED_PAYLOAD_VERSION,
	INTEGRITY_MISMATCH,
	CONTROL_ONLY,
	INCOMPLETE_CAPTURE_AUTHORITY,
	MALFORMED_PROVIDER_ENVELOPE,
	PAYLOAD_INVALID,
	COUNT_DOMAIN_UNPROVEN,
	COUNT_DOMAIN_MISMATCH,
	FACT_LINKAGE_MISMATCH,
	FACT_SEMANTICS_MISMATCH,
	FACT_INTEGRITY_MISMATCH,
}

/**
 * Decoded original counter window; countDomainProven separately records whether its supplied owner
 * was verified.
 */
data class AuthenticatedStepsWalCarrier internal constructor(
	val payload: StepsWalPayload,
	val countDomainProven: Boolean,
) {
	val isPositiveCoveredWindow: Boolean
		get() = payload.boundaryKind == StepsWalBoundaryKind.COVERED &&
			payload.deltaCount > 0L
}

/**
 * Typed Steps carrier comparison result with the exact rejection cause.
 */
sealed interface StepsWalCarrierAuthentication {
	/**
	 * Successful original-carrier comparison containing its checked, decoded measurement payload.
	 */
	data class Authenticated(
		val carrier: AuthenticatedStepsWalCarrier,
	) : StepsWalCarrierAuthentication

	/**
	 * Specific rejected decode, carrier or per-purpose qualification outcome; it carries no accepted
	 * payload.
	 */
	data class Rejected(val reason: StepsWalCarrierFailure) : StepsWalCarrierAuthentication
}

/**
 * Compares original Steps carriers, recorded counter semantics and supplied WAL/fact count-domain
 * owners.
 */
object StepsWalCarrierAuthenticator {
	/**
	 * Checks the original Steps WAL checksum, provider envelope, supported payload and recorded
	 * capture binding; reports the exact rejection cause.
	 */
	@Suppress("ComplexCondition", "ReturnCount")
	fun authenticate(wal: SourceEventWalEntity): StepsWalCarrierAuthentication {
		val envelopeRejection = rejectInvalidEnvelope(wal)
		if (envelopeRejection != null) {
			return envelopeRejection
		}
		val payload = StepsWalPayloadCodec.decodeCanonical(wal.payloadVersion, wal.payload)
			?: return rejected(StepsWalCarrierFailure.PAYLOAD_INVALID)
		if (!payload.hasValidSourceSemantics(wal.payloadVersion)) {
			return rejected(StepsWalCarrierFailure.PAYLOAD_INVALID)
		}
		if (
			payload.bootClockDomainId != wal.clockDomainId ||
			payload.windowStartElapsedRealtimeNanos != wal.observedIntervalStartNanos ||
			payload.windowEndElapsedRealtimeNanos != wal.observedElapsedNanos
		) {
			return rejected(StepsWalCarrierFailure.MALFORMED_PROVIDER_ENVELOPE)
		}
		return StepsWalCarrierAuthentication.Authenticated(
			AuthenticatedStepsWalCarrier(payload, countDomainProven = false),
		)
	}

	// Preserve the original envelope failure precedence before attempting payload decoding.
	@Suppress("ReturnCount")
	private fun rejectInvalidEnvelope(
		wal: SourceEventWalEntity,
	): StepsWalCarrierAuthentication.Rejected? {
		if (wal.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS) {
			return rejected(StepsWalCarrierFailure.WRONG_SOURCE)
		}
		if (wal.payloadVersion !in
			MINIMUM_STEPS_WAL_PAYLOAD_VERSION..STEPS_WAL_PAYLOAD_VERSION
		) {
			return rejected(StepsWalCarrierFailure.UNSUPPORTED_PAYLOAD_VERSION)
		}
		if (!wal.hasExactQualifiedIntegrity(MAX_STEPS_WAL_PAYLOAD_BYTES)) {
			return rejected(StepsWalCarrierFailure.INTEGRITY_MISMATCH)
		}
		if (wal.authorizationPurposeEligibilityMask and
			SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L
		) {
			return rejected(StepsWalCarrierFailure.CONTROL_ONLY)
		}
		if (!wal.hasCompleteSessionCaptureBinding()) {
			return rejected(StepsWalCarrierFailure.INCOMPLETE_CAPTURE_AUTHORITY)
		}
		if (hasMalformedProviderEnvelope(wal)) {
			return rejected(StepsWalCarrierFailure.MALFORMED_PROVIDER_ENVELOPE)
		}
		return null
	}

	/**
	 * Checks the Steps WAL envelope, then requires its current-format provider token to match the
	 * supplied original count-domain owner.
	 */
	fun authenticate(
		wal: SourceEventWalEntity,
		walOwner: StepsCountDomainStoredOwner?,
	): StepsWalCarrierAuthentication {
		val authentication = authenticate(wal)
		if (authentication !is StepsWalCarrierAuthentication.Authenticated) {
			return authentication
		}
		return authenticateCountDomain(wal, walOwner, authentication)
	}

	private fun authenticateCountDomain(
		wal: SourceEventWalEntity,
		walOwner: StepsCountDomainStoredOwner?,
		authentication: StepsWalCarrierAuthentication.Authenticated,
	): StepsWalCarrierAuthentication {
		if (wal.payloadVersion < STEP_COUNTER_EPOCH_GENERATION_PAYLOAD_VERSION) {
			return rejected(StepsWalCarrierFailure.COUNT_DOMAIN_UNPROVEN)
		}
		val token = authentication.carrier.payload.counterDomainToken
			?: return rejected(StepsWalCarrierFailure.COUNT_DOMAIN_UNPROVEN)
		if (!walOwner.authenticatesWal(wal, token)) {
			return rejected(StepsWalCarrierFailure.COUNT_DOMAIN_MISMATCH)
		}
		return StepsWalCarrierAuthentication.Authenticated(
			authentication.carrier.copy(countDomainProven = true),
		)
	}

	/**
	 * Checks the saved Steps revision against its original WAL carrier, ownership, measured values
	 * and supplied child/count-domain evidence.
	 */
	// Keep carrier, count-domain and saved-fact rejection precedence visible.
	@Suppress("ReturnCount")
	fun authenticate(
		fact: StepFactRevisionEntity,
		wal: SourceEventWalEntity,
		walOwner: StepsCountDomainStoredOwner?,
		factOwner: StepsCountDomainStoredOwner?,
	): StepsWalCarrierAuthentication {
		val rawAuthentication = authenticate(wal)
		if (rawAuthentication !is StepsWalCarrierAuthentication.Authenticated) {
			return rawAuthentication
		}
		val authentication = authenticateFactCountDomain(wal, walOwner, rawAuthentication)
		if (authentication !is StepsWalCarrierAuthentication.Authenticated) {
			return authentication
		}
		if (hasMismatchedFactLink(fact, wal, authentication.carrier.payload)) {
			return rejected(StepsWalCarrierFailure.FACT_LINKAGE_MISMATCH)
		}
		if (!fact.matches(authentication.carrier.payload, wal)) {
			return rejected(StepsWalCarrierFailure.FACT_SEMANTICS_MISMATCH)
		}
		val countDomainMismatch = if (authentication.carrier.countDomainProven) {
			!factOwner.authenticatesFact(fact, requireNotNull(walOwner))
		} else {
			!factOwner.authenticatesUnprovenFact(fact)
		}
		if (
			!StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(fact) ||
			countDomainMismatch
		) {
			return rejected(StepsWalCarrierFailure.FACT_INTEGRITY_MISMATCH)
		}
		return authentication
	}

	private fun authenticateFactCountDomain(
		wal: SourceEventWalEntity,
		walOwner: StepsCountDomainStoredOwner?,
		rawAuthentication: StepsWalCarrierAuthentication.Authenticated,
	): StepsWalCarrierAuthentication {
		val token = rawAuthentication.carrier.payload.counterDomainToken
		return if (
			wal.payloadVersion < STEP_COUNTER_EPOCH_GENERATION_PAYLOAD_VERSION ||
			token == null
		) {
			if (
				(
					wal.payloadVersion >= STEP_COUNTER_EPOCH_GENERATION_PAYLOAD_VERSION &&
						rawAuthentication.carrier.payload.boundaryKind !=
						StepsWalBoundaryKind.COUNTER_RESET
					) ||
				!walOwner.authenticatesUnprovenWal(wal)
			) {
				return rejected(StepsWalCarrierFailure.COUNT_DOMAIN_UNPROVEN)
			}
			rawAuthentication
		} else {
			when (val proven = authenticateCountDomain(wal, walOwner, rawAuthentication)) {
				is StepsWalCarrierAuthentication.Authenticated -> proven
				is StepsWalCarrierAuthentication.Rejected -> return proven
			}
		}
	}

	/**
	 * Returns whether the saved Steps fact and supplied original carrier pass the complete carrier
	 * comparison.
	 */
	fun authenticates(
		fact: StepFactRevisionEntity,
		wal: SourceEventWalEntity,
		walOwner: StepsCountDomainStoredOwner?,
		factOwner: StepsCountDomainStoredOwner?,
	): Boolean =
		authenticate(fact, wal, walOwner, factOwner) is StepsWalCarrierAuthentication.Authenticated
}

// One saved provider envelope: each field and its null guard remain in original short-circuit order.
@Suppress("CyclomaticComplexMethod")
private fun hasMalformedProviderEnvelope(wal: SourceEventWalEntity): Boolean =
	wal.providerDedupKey.isNullOrBlank() ||
		wal.deliveryIdentity != null ||
		wal.deliveryUnitIndex != null ||
		wal.deliveryUnitCount != null ||
		wal.activityAutomationEpoch != null ||
		wal.sourceSequence < 0L ||
		wal.observedIntervalStartNanos?.let { it >= 0L } != true ||
		wal.observedElapsedNanos < requireNotNull(wal.observedIntervalStartNanos) ||
		wal.receivedElapsedNanos < wal.observedElapsedNanos ||
		wal.receivedWallTimeMs?.let { it < 0L } == true ||
		wal.wallTimeMs?.let { it >= 0L } != true ||
		wal.wallTimeUncertaintyMs?.let { it >= 0L } != true ||
		wal.acquiredAtMs != wal.wallTimeMs ||
		wal.createdAtMs < 0L ||
		!wal.hasCanonicalQuality()

private fun hasMismatchedFactLink(
	fact: StepFactRevisionEntity,
	wal: SourceEventWalEntity,
	payload: StepsWalPayload,
): Boolean =
	fact.sourceEventId != wal.eventId ||
		fact.sourceAdmissionOrdinal != wal.admissionOrdinal ||
		fact.logicalTrackingId != wal.logicalTrackingId ||
		fact.serviceRunId != wal.serviceRunId ||
		fact.sourcePolicyRevision != wal.sourcePolicyRevision ||
		fact.captureConsentEpoch != wal.captureConsentEpoch ||
		fact.manifestRevision != wal.sessionManifestRevision ||
		fact.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
		fact.clockDomainId != wal.clockDomainId ||
		fact.bootClockDomainId != payload.bootClockDomainId

// Validates one immutable counter payload against its existing version and boundary shape.
@Suppress("CyclomaticComplexMethod")
private fun StepsWalPayload.hasValidSourceSemantics(payloadVersion: Int): Boolean {
	if (
		bootClockDomainId.isBlank() ||
		firstCumulativeCount < 0L ||
		lastCumulativeCount < 0L ||
		deltaCount < 0L ||
		windowStartElapsedRealtimeNanos < 0L ||
		windowEndElapsedRealtimeNanos < windowStartElapsedRealtimeNanos ||
		firstProviderSequence < 0L ||
		lastProviderSequence < firstProviderSequence
	) {
		return false
	}
	return when (boundaryKind) {
		StepsWalBoundaryKind.BASELINE ->
			deltaCount == 0L && firstCumulativeCount == lastCumulativeCount
		StepsWalBoundaryKind.COVERED ->
			lastCumulativeCount >= firstCumulativeCount &&
				deltaCount == lastCumulativeCount - firstCumulativeCount
		StepsWalBoundaryKind.COUNTER_RESET ->
			deltaCount == 0L &&
				lastCumulativeCount < firstCumulativeCount &&
				(payloadVersion < STEP_COUNTER_EPOCH_GENERATION_PAYLOAD_VERSION ||
					counterDomainToken == null)
		StepsWalBoundaryKind.LEGACY_AMBIGUOUS -> deltaCount == 0L
	}
}

// One serialized source-window-to-fact correspondence.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun StepFactRevisionEntity.matches(
	payload: StepsWalPayload,
	wal: SourceEventWalEntity,
): Boolean {
	val endTimeMs = wal.wallTimeMs ?: return false
	val durationMs =
		(payload.windowEndElapsedRealtimeNanos - payload.windowStartElapsedRealtimeNanos) /
			NANOS_PER_MILLISECOND
	val startTimeMs = if (durationMs > endTimeMs) {
		0L
	} else {
		endTimeMs - durationMs
	}
	val expectedCoverage = when (payload.boundaryKind) {
		StepsWalBoundaryKind.BASELINE -> StepFactRevisionEntity.COVERAGE_BASELINE
		StepsWalBoundaryKind.COVERED -> StepFactRevisionEntity.COVERAGE_COVERED
		StepsWalBoundaryKind.COUNTER_RESET -> StepFactRevisionEntity.COVERAGE_RESET_GAP
		StepsWalBoundaryKind.LEGACY_AMBIGUOUS -> StepFactRevisionEntity.COVERAGE_PARTIAL
	}
	return originKind == StepFactRevisionEntity.ORIGIN_LIVE_WAL &&
		operation == StepFactRevisionEntity.OPERATION_UPSERT &&
		originIdentity == wal.eventId &&
		intervalStartTimeMs == startTimeMs &&
		intervalEndTimeMs == endTimeMs &&
		intervalStartElapsedRealtimeNanos == payload.windowStartElapsedRealtimeNanos &&
		intervalEndElapsedRealtimeNanos == payload.windowEndElapsedRealtimeNanos &&
		cumulativeStepCountStart == payload.firstCumulativeCount &&
		cumulativeStepCountEnd == payload.lastCumulativeCount &&
		wallTimeUncertaintyMs == wal.wallTimeUncertaintyMs &&
		coverageKind == expectedCoverage &&
		effectiveStepCount == payload.deltaCount &&
		purpose == StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE &&
		scopeDeletionGeneration == 0L &&
		appliedAtMs == endTimeMs
}

// A complete three-object join predicate belongs together so the count-domain receipt cannot be accidentally
// borrowed from another WAL.
@Suppress("CyclomaticComplexMethod")
private fun StepsCountDomainStoredOwner?.authenticatesWal(
	wal: SourceEventWalEntity,
	token: StepsCounterDomainToken,
): Boolean {
	val stored = this ?: return false
	val receipt = stored.receipt ?: return false
	val expectedScope = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
		requireNotNull(wal.logicalTrackingId),
		requireNotNull(wal.serviceRunId),
	)
	val expectedOwner = StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
		wal.admissionOrdinal,
		wal.eventId,
	)
	return stored.isAuthenticBinding() &&
		stored.owner.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL &&
		stored.owner.scopeIdentity == expectedScope &&
		stored.owner.ownerIdentity == expectedOwner &&
		stored.owner.ownerRevision == 1L &&
		stored.owner.ownerEffectChecksum == wal.integrityIdentity &&
		receipt.domainIdentity == StepsCountDomainReceiptIntegrity.counterDomainIdentity(token) &&
		receipt.registrationGeneration == wal.registrationGeneration &&
		receipt.collectedDataEpoch == wal.capturedCollectedDataEpoch &&
		receipt.authorityRevision == wal.authorizationRevision &&
		receipt.authorityFingerprint == wal.authorizationFingerprint &&
		receipt.coverageKind == StepsCountDomainReceiptEntity.COVERAGE_ADMITTED_WINDOW &&
		receipt.coverageVersion == wal.payloadVersion &&
		receipt.countDomainVersion ==
		StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION &&
		receipt.effectChecksum == wal.integrityIdentity
}

// One exact fact-to-WAL count-domain linkage, including absent receipts and unsupported coverage.
@Suppress("CyclomaticComplexMethod", "ReturnCount")
private fun StepsCountDomainStoredOwner?.authenticatesFact(
	fact: StepFactRevisionEntity,
	walOwner: StepsCountDomainStoredOwner,
): Boolean {
	val stored = this ?: return false
	val receipt = stored.receipt ?: return false
	val walReceipt = walOwner.receipt ?: return false
	val expectedScope = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
		requireNotNull(fact.logicalTrackingId),
		requireNotNull(fact.serviceRunId),
	)
	val expectedOwner = StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
		fact.writerProjectionId,
		fact.writerProjectionVersion,
		fact.logicalFactId,
	)
	val expectedCoverage = when (fact.coverageKind) {
		StepFactRevisionEntity.COVERAGE_BASELINE -> StepsCountDomainReceiptEntity.COVERAGE_BASELINE
		StepFactRevisionEntity.COVERAGE_COVERED -> StepsCountDomainReceiptEntity.COVERAGE_COVERED
		StepFactRevisionEntity.COVERAGE_RESET_GAP ->
			StepsCountDomainReceiptEntity.COVERAGE_RESET_GAP
		StepFactRevisionEntity.COVERAGE_PARTIAL -> StepsCountDomainReceiptEntity.COVERAGE_PARTIAL
		else -> return false
	}
	return stored.isAuthenticBinding() &&
		stored.owner.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT &&
		stored.owner.scopeIdentity == expectedScope &&
		stored.owner.ownerIdentity == expectedOwner &&
		stored.owner.ownerRevision == fact.semanticRevision &&
		stored.owner.ownerEffectChecksum == fact.effectChecksum &&
		receipt.domainIdentity == walReceipt.domainIdentity &&
		receipt.registrationGeneration == walReceipt.registrationGeneration &&
		receipt.collectedDataEpoch == walReceipt.collectedDataEpoch &&
		receipt.authorityRevision == walReceipt.authorityRevision &&
		receipt.authorityFingerprint == walReceipt.authorityFingerprint &&
		receipt.coverageKind == expectedCoverage &&
		receipt.coverageVersion == fact.writerProjectionVersion &&
		receipt.countDomainVersion == walReceipt.countDomainVersion &&
		receipt.effectChecksum == fact.effectChecksum
}

private fun StepsCountDomainStoredOwner?.authenticatesUnprovenWal(
	wal: SourceEventWalEntity,
): Boolean {
	val stored = this ?: return false
	val expectedScope = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
		requireNotNull(wal.logicalTrackingId),
		requireNotNull(wal.serviceRunId),
	)
	val expectedOwner = StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
		wal.admissionOrdinal,
		wal.eventId,
	)
	return stored.owner.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL &&
		stored.owner.scopeIdentity == expectedScope &&
		stored.owner.ownerIdentity == expectedOwner &&
		stored.owner.ownerRevision == 1L &&
		stored.owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN &&
		stored.owner.receiptIdentity == null &&
		stored.owner.ownerEffectChecksum == wal.integrityIdentity &&
		stored.receipt == null &&
		stored.completenessMarker == null
}

private fun StepsCountDomainStoredOwner?.authenticatesUnprovenFact(
	fact: StepFactRevisionEntity,
): Boolean {
	val stored = this ?: return false
	val expectedScope = StepsCountDomainReceiptIntegrity.sessionRunScopeIdentity(
		requireNotNull(fact.logicalTrackingId),
		requireNotNull(fact.serviceRunId),
	)
	val expectedOwner = StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
		fact.writerProjectionId,
		fact.writerProjectionVersion,
		fact.logicalFactId,
	)
	return stored.owner.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT &&
		stored.owner.scopeIdentity == expectedScope &&
		stored.owner.ownerIdentity == expectedOwner &&
		stored.owner.ownerRevision == fact.semanticRevision &&
		stored.owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN &&
		stored.owner.receiptIdentity == null &&
		stored.owner.ownerEffectChecksum == fact.effectChecksum &&
		stored.receipt == null &&
		stored.completenessMarker == null
}

// Compares the immutable owner/receipt tuple and its existing deterministic receipt integrity.
@Suppress("CyclomaticComplexMethod")
private fun StepsCountDomainStoredOwner.isAuthenticBinding(): Boolean {
	val receipt = receipt ?: return false
	return owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_BIND &&
		owner.receiptIdentity == receipt.receiptIdentity &&
		owner.ownerKind == receipt.ownerKind &&
		owner.scopeIdentity == receipt.scopeIdentity &&
		owner.ownerIdentity == receipt.ownerIdentity &&
		owner.ownerRevision == receipt.ownerRevision &&
		owner.ownerEffectChecksum == receipt.effectChecksum &&
		StepsCountDomainReceiptIntegrity.hasValidReceipt(receipt) &&
		completenessMarker == null &&
		receipt.completionEvidenceChecksum == null
}

private val StepsWalBoundaryKind.wireCode: Int
	get() = when (this) {
		StepsWalBoundaryKind.BASELINE -> 1
		StepsWalBoundaryKind.COVERED -> 2
		StepsWalBoundaryKind.COUNTER_RESET -> STEP_COUNTER_RESET_WIRE_CODE
		StepsWalBoundaryKind.LEGACY_AMBIGUOUS ->
			throw IllegalArgumentException("Legacy ambiguous Steps has no stable boundary wire code")
	}

private fun rejected(reason: StepsWalCarrierFailure) =
	StepsWalCarrierAuthentication.Rejected(reason)

private const val TYPE_STEP_WINDOW = 4
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val STEP_BOUNDARY_KIND_PAYLOAD_VERSION = 3
private const val STEP_COUNTER_DOMAIN_TOKEN_PAYLOAD_VERSION = 6
private const val STEP_COUNTER_EPOCH_GENERATION_PAYLOAD_VERSION = 7

private const val STEP_COUNTER_RESET_WIRE_CODE = 3
