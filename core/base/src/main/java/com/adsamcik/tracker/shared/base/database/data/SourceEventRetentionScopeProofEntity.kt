package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import java.security.MessageDigest

/**
 * Compact authenticated Wi-Fi/Cell capture-scope authority retained independently of bounded
 * event proofs.
 *
 * One exact source/run row survives ordinary event-proof trimming. It contains no captured payload
 * and is removed only after source erase has durably installed both the run fence and the
 * source-local deletion generation.
 */
@Entity(
	tableName = "source_event_retention_scope_proof",
	primaryKeys = ["source_kind", "logical_run_scope_digest"],
	indices = [
		Index(
			value = ["source_kind", "logical_tracking_id", "service_run_id"],
			unique = true,
			name = "idx_source_event_retention_scope_run",
		),
	],
)
data class SourceEventRetentionScopeProofEntity(
	@ColumnInfo(name = "source_kind")
	val sourceKind: Int,
	@ColumnInfo(name = "purpose")
	val purpose: String,
	@ColumnInfo(name = "logical_tracking_id")
	val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id")
	val serviceRunId: String,
	@ColumnInfo(name = "source_policy_revision")
	val sourcePolicyRevision: Long,
	@ColumnInfo(name = "capture_consent_epoch")
	val captureConsentEpoch: Long,
	@ColumnInfo(name = "collected_data_epoch")
	val collectedDataEpoch: Long,
	@ColumnInfo(name = "scope_deletion_generation")
	val scopeDeletionGeneration: Long,
	@ColumnInfo(name = "logical_run_scope_digest")
	val logicalRunScopeDigest: String,
	@ColumnInfo(name = "retained_event_high_water_ordinal")
	val retainedEventHighWaterOrdinal: Long,
	@ColumnInfo(name = "retained_event_authority_checksum")
	val retainedEventAuthorityChecksum: String,
	@ColumnInfo(name = "effect_checksum")
	val effectChecksum: String,
) {
	/**
	 * Checks the record's stored identity, value constraints and deterministic digest before using
	 * retained metadata.
	 */
	// Checks a complete immutable retained-scope record and its deterministic identities.
	@Suppress("CyclomaticComplexMethod")
	fun hasValidStoredIntegrity(): Boolean =
		sourceKind in SUPPORTED_SOURCE_KINDS &&
			purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
			logicalTrackingId.isNotBlank() &&
			serviceRunId.isNotBlank() &&
			sourcePolicyRevision > 0L &&
			captureConsentEpoch >= 0L &&
			collectedDataEpoch >= 0L &&
			scopeDeletionGeneration == 0L &&
			logicalRunScopeDigest.matches(SHA_256_HEX) &&
			logicalRunScopeDigest == SourceDeletionFenceEntity.logicalServiceRunIdentity(
				sourceKind = sourceKind,
				purpose = purpose,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
			) &&
			retainedEventHighWaterOrdinal > 0L &&
			retainedEventAuthorityChecksum.matches(SHA_256_HEX) &&
			effectChecksum == calculatedEffectChecksum()

	/**
	 * Returns the original session/run scope only when this retained record's capture metadata and
	 * digest are valid; otherwise returns null.
	 */
	fun authenticatedCaptureScope(
		expectedSourceKind: Int,
		expectedCollectedDataEpoch: Long,
		expectedDeletedSourceEventHighWaterOrdinal: Long,
	): RetainedCaptureScopeIdentity? {
		if (!hasValidStoredIntegrity() ||
			sourceKind != expectedSourceKind ||
			collectedDataEpoch != expectedCollectedDataEpoch ||
			retainedEventHighWaterOrdinal <= expectedDeletedSourceEventHighWaterOrdinal
		) {
			return null
		}
		return RetainedCaptureScopeIdentity(
			sourceKind = sourceKind,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			collectedDataEpoch = collectedDataEpoch,
			scopeDeletionGeneration = scopeDeletionGeneration,
			logicalRunScopeDigest = logicalRunScopeDigest,
		).takeIf { it.isCanonicalFor(expectedSourceKind, expectedCollectedDataEpoch) }
	}

	/**
	 * Checks that the retained event is inside this exact source/session/run scope and matches its
	 * original policy, consent and data epoch.
	 */
	fun authenticatesRetainedEvent(eventProof: SourceEventRetentionProofEntity): Boolean {
		if (!hasValidStoredIntegrity() || !eventProof.hasValidStoredIntegrity()) {
			return false
		}
		val candidate = runCatching { create(eventProof) }.getOrNull() ?: return false
		if (!sameScopeAuthority(candidate) ||
			retainedEventHighWaterOrdinal < candidate.retainedEventHighWaterOrdinal
		) {
			return false
		}
		return retainedEventHighWaterOrdinal != candidate.retainedEventHighWaterOrdinal ||
			retainedEventAuthorityChecksum == candidate.retainedEventAuthorityChecksum
	}

	/**
	 * Extends the retained scope's monotonic admission/sequence bounds with a matching retained event
	 * and recomputes its digest.
	 */
	fun advance(eventProof: SourceEventRetentionProofEntity): SourceEventRetentionScopeProofEntity {
		require(hasValidStoredIntegrity())
		val candidate = create(eventProof)
		require(sameScopeAuthority(candidate))
		if (candidate.retainedEventHighWaterOrdinal < retainedEventHighWaterOrdinal) {
			return this
		}
		if (candidate.retainedEventHighWaterOrdinal == retainedEventHighWaterOrdinal) {
			require(
				retainedEventAuthorityChecksum == candidate.retainedEventAuthorityChecksum,
			)
			return this
		}
		return candidate
	}

	private fun sameScopeAuthority(other: SourceEventRetentionScopeProofEntity): Boolean =
		sourceKind == other.sourceKind &&
			purpose == other.purpose &&
			logicalTrackingId == other.logicalTrackingId &&
			serviceRunId == other.serviceRunId &&
			sourcePolicyRevision == other.sourcePolicyRevision &&
			captureConsentEpoch == other.captureConsentEpoch &&
			collectedDataEpoch == other.collectedDataEpoch &&
			scopeDeletionGeneration == other.scopeDeletionGeneration &&
			logicalRunScopeDigest == other.logicalRunScopeDigest

	/**
	 * Recomputes the digest from this record's persisted values for replay and corruption checks.
	 */
	fun calculatedEffectChecksum(): String = checksum(
		CONTRACT,
		sourceKind,
		purpose,
		logicalTrackingId,
		serviceRunId,
		sourcePolicyRevision,
		captureConsentEpoch,
		collectedDataEpoch,
		scopeDeletionGeneration,
		logicalRunScopeDigest,
		retainedEventHighWaterOrdinal,
		retainedEventAuthorityChecksum,
	)

	/**
	 * Stable persisted codes, including `CONTRACT`, and any identity-bound record factories.
	 */
	companion object {
		private const val CONTRACT = "source-event-retention-scope-proof-v1"
		private val SHA_256_HEX = Regex("[0-9a-f]{64}")
		private val SUPPORTED_SOURCE_KINDS = setOf(
			SourceDestinationOwnerEntity.SOURCE_WIFI,
			SourceDestinationOwnerEntity.SOURCE_CELL,
		)

		/**
		 * Builds a session-run scope witness from a valid retained event with explicit session-capture
		 * membership.
		 */
		fun create(
			eventProof: SourceEventRetentionProofEntity,
		): SourceEventRetentionScopeProofEntity {
			require(eventProof.hasValidStoredIntegrity())
			require(
				eventProof.purposeEligibilityMask and
					SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L,
			)
			val unsigned = SourceEventRetentionScopeProofEntity(
				sourceKind = eventProof.sourceKind,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = requireNotNull(eventProof.logicalTrackingId),
				serviceRunId = requireNotNull(eventProof.serviceRunId),
				sourcePolicyRevision = requireNotNull(eventProof.sourcePolicyRevision),
				captureConsentEpoch = requireNotNull(eventProof.captureConsentEpoch),
				collectedDataEpoch = eventProof.collectedDataEpoch,
				scopeDeletionGeneration = requireNotNull(eventProof.scopeDeletionGeneration),
				logicalRunScopeDigest = requireNotNull(eventProof.logicalRunScopeDigest),
				retainedEventHighWaterOrdinal = eventProof.sourceAdmissionOrdinal,
				retainedEventAuthorityChecksum = eventProof.proofAuthorityFingerprint,
				effectChecksum = "",
			)
			return unsigned.copy(effectChecksum = unsigned.calculatedEffectChecksum())
		}

		private fun checksum(vararg values: Any?): String {
			val canonical = values.joinToString(separator = "") { value ->
				val text = value?.toString()
				if (text == null) {
					"-1:"
				} else {
					"${text.length}:$text"
				}
			}
			return MessageDigest.getInstance("SHA-256")
				.digest(canonical.toByteArray(Charsets.UTF_8))
				.joinToString(separator = "") { byte -> "%02x".format(byte) }
		}
	}
}
