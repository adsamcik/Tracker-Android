package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import java.security.MessageDigest

/**
 * Bounded payload-free proof that ordinary source-event retention removed one exact WAL row.
 *
 * This receipt carries no payload or location and grants no generic deletion authority.
 * Capture-eligible receipts retain the exact opaque run identifiers and generation needed to
 * install both no-resurrection authorities after the WAL is gone. Its
 * deterministic authority fingerprint binds the immutable provider, manifest, admission, policy,
 * consent, epoch, and run-scope tuple that existed immediately before WAL deletion.
 */
@Entity(
	tableName = "source_event_retention_proof",
	primaryKeys = ["source_kind", "event_id"],
	indices = [
		Index(
			value = ["source_kind", "source_admission_ordinal"],
			unique = true,
			name = "idx_source_event_retention_proof_admission",
		),
	],
)
data class SourceEventRetentionProofEntity(
	@ColumnInfo(name = "source_kind")
	val sourceKind: Int,
	@ColumnInfo(name = "event_id")
	val eventId: String,
	@ColumnInfo(name = "source_admission_ordinal")
	val sourceAdmissionOrdinal: Long,
	@ColumnInfo(name = "purpose_eligibility_mask")
	val purposeEligibilityMask: Long,
	@ColumnInfo(name = "source_policy_revision")
	val sourcePolicyRevision: Long?,
	@ColumnInfo(name = "capture_consent_epoch")
	val captureConsentEpoch: Long?,
	@ColumnInfo(name = "collected_data_epoch")
	val collectedDataEpoch: Long,
	@ColumnInfo(name = "logical_tracking_id")
	val logicalTrackingId: String?,
	@ColumnInfo(name = "service_run_id")
	val serviceRunId: String?,
	@ColumnInfo(name = "scope_deletion_generation")
	val scopeDeletionGeneration: Long?,
	@ColumnInfo(name = "logical_run_scope_digest")
	val logicalRunScopeDigest: String?,
	@ColumnInfo(name = "wal_payload_checksum")
	val walPayloadChecksum: String,
	@ColumnInfo(name = "wal_integrity_identity")
	val walIntegrityIdentity: String,
	@ColumnInfo(name = "proof_authority_fingerprint")
	val proofAuthorityFingerprint: String,
	@ColumnInfo(name = "reason")
	val reason: String,
	@ColumnInfo(name = "effect_checksum")
	val effectChecksum: String,
) {
	/**
	 * Checks the supplied original Location ownership/lineage metadata against this retained record's
	 * saved values and digest.
	 */
	// Compares supplied expected fields with one immutable retained event record.
	@Suppress("CyclomaticComplexMethod")
	fun authenticates(
		expectedSourceKind: Int,
		expectedEventId: String,
		expectedPurposeMask: Long,
		expectedSourcePolicyRevision: Long,
		expectedCaptureConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
		expectedScopeDeletionGeneration: Long,
		expectedLogicalRunScopeDigest: String,
	): Boolean = sourceKind == expectedSourceKind &&
		eventId == expectedEventId &&
		sourceAdmissionOrdinal > 0L &&
		purposeEligibilityMask and expectedPurposeMask == expectedPurposeMask &&
		sourcePolicyRevision == expectedSourcePolicyRevision &&
		captureConsentEpoch == expectedCaptureConsentEpoch &&
		collectedDataEpoch == expectedCollectedDataEpoch &&
		scopeDeletionGeneration == expectedScopeDeletionGeneration &&
		logicalRunScopeDigest == expectedLogicalRunScopeDigest &&
		reason == REASON_RETENTION_EXPIRED &&
		hasValidStoredIntegrity()

	/**
	 * Checks the record's stored identity, value constraints and deterministic digest before using
	 * retained metadata.
	 */
	// Checks the stored nullable-field combinations and digest without reading live authority.
	@Suppress("CyclomaticComplexMethod")
	fun hasValidStoredIntegrity(): Boolean =
		sourceKind in SUPPORTED_SOURCE_KINDS &&
			eventId.isNotBlank() &&
			sourceAdmissionOrdinal > 0L &&
			purposeEligibilityMask in 0L..SourceBrokerPurpose.ALL_MASK &&
			sourcePolicyRevision?.let { it > 0L } != false &&
			captureConsentEpoch?.let { it >= 0L } != false &&
			collectedDataEpoch >= 0L &&
			logicalTrackingId?.let { it.isNotBlank() } != false &&
			serviceRunId?.let { it.isNotBlank() } != false &&
			scopeDeletionGeneration?.let { it >= 0L } != false &&
			logicalRunScopeDigest?.let(SHA_256_HEX::matches) != false &&
			walPayloadChecksum.matches(SHA_256_HEX) &&
			walIntegrityIdentity.matches(SHA_256_HEX) &&
			proofAuthorityFingerprint.matches(SHA_256_HEX) &&
			(purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
				(sourcePolicyRevision != null && captureConsentEpoch != null &&
					logicalTrackingId != null && serviceRunId != null &&
					scopeDeletionGeneration == 0L && logicalRunScopeDigest != null &&
					logicalRunScopeDigest == SourceDeletionFenceEntity.logicalServiceRunIdentity(
						sourceKind = sourceKind,
						purpose = SourceBrokerPurpose.SESSION_CAPTURE,
						logicalTrackingId = logicalTrackingId,
						serviceRunId = serviceRunId,
					))) &&
			(purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L ||
				(logicalTrackingId == null && serviceRunId == null &&
					scopeDeletionGeneration == null && logicalRunScopeDigest == null)) &&
			reason == REASON_RETENTION_EXPIRED &&
			effectChecksum == calculatedEffectChecksum()

	/**
	 * Returns the original session/run scope only when this retained record's capture metadata and
	 * digest are valid; otherwise returns null.
	 */
	// One nullable saved-scope projection keeps each absent required field as an explicit rejection.
	@Suppress("ReturnCount")
	fun authenticatedCaptureScope(
		expectedSourceKind: Int,
		expectedCollectedDataEpoch: Long,
	): RetainedCaptureScopeIdentity? {
		if (!hasValidStoredIntegrity() ||
			sourceKind != expectedSourceKind ||
			purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
			collectedDataEpoch != expectedCollectedDataEpoch
		) {
			return null
		}
		return RetainedCaptureScopeIdentity(
			sourceKind = sourceKind,
			logicalTrackingId = logicalTrackingId ?: return null,
			serviceRunId = serviceRunId ?: return null,
			collectedDataEpoch = collectedDataEpoch,
			scopeDeletionGeneration = scopeDeletionGeneration ?: return null,
			logicalRunScopeDigest = logicalRunScopeDigest ?: return null,
		).takeIf { it.isCanonicalFor(expectedSourceKind, expectedCollectedDataEpoch) }
	}

	/**
	 * Returns the original admitted event and capture scope only when retained metadata validates;
	 * otherwise returns null.
	 */
	fun authenticatedCaptureEvent(
		expectedSourceKind: Int,
		expectedCollectedDataEpoch: Long,
	): RetainedCaptureEventIdentity? {
		val scope = authenticatedCaptureScope(
			expectedSourceKind,
			expectedCollectedDataEpoch,
		) ?: return null
		return RetainedCaptureEventIdentity(
			eventId = eventId,
			sourceAdmissionOrdinal = sourceAdmissionOrdinal,
			sourcePolicyRevision = sourcePolicyRevision ?: return null,
			captureConsentEpoch = captureConsentEpoch ?: return null,
			walIntegrityIdentity = walIntegrityIdentity,
			scope = scope,
		)
	}

	/**
	 * Recomputes the digest from this record's persisted values for replay and corruption checks.
	 */
	fun calculatedEffectChecksum(): String = checksum(
		CONTRACT,
		sourceKind,
		eventId,
		sourceAdmissionOrdinal,
		purposeEligibilityMask,
		sourcePolicyRevision,
		captureConsentEpoch,
		collectedDataEpoch,
		logicalTrackingId,
		serviceRunId,
		scopeDeletionGeneration,
		logicalRunScopeDigest,
		walPayloadChecksum,
		walIntegrityIdentity,
		proofAuthorityFingerprint,
		reason,
	)

	/**
	 * Stable persisted codes, including `REASON_RETENTION_EXPIRED`, `CONTRACT`, `AUTHORITY_CONTRACT`,
	 * and any identity-bound record factories.
	 */
	companion object {
		const val REASON_RETENTION_EXPIRED = "RETENTION_EXPIRED"
		private const val CONTRACT = "source-event-retention-proof-v4"
		private const val AUTHORITY_CONTRACT = "source-event-retention-proof-authority-v4"
		private val SHA_256_HEX = Regex("[0-9a-f]{64}")
		private val SUPPORTED_SOURCE_KINDS = setOf(
			SourceDestinationOwnerEntity.SOURCE_LOCATION,
			SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SourceDestinationOwnerEntity.SOURCE_WIFI,
			SourceDestinationOwnerEntity.SOURCE_CELL,
		)

		/**
		 * Builds payload-free retained event metadata after checking the original WAL digest and capture
		 * ownership fields.
		 */
		fun createFromWal(wal: SourceEventWalEntity): SourceEventRetentionProofEntity {
			requireOriginalWal(wal)
			val scope = captureScopeFromWal(wal)
			val proofAuthorityFingerprint = authorityFingerprint(wal, scope)
			val unsigned = SourceEventRetentionProofEntity(
				sourceKind = wal.sourceKind,
				eventId = wal.eventId,
				sourceAdmissionOrdinal = wal.admissionOrdinal,
				purposeEligibilityMask = wal.authorizationPurposeEligibilityMask,
				sourcePolicyRevision = wal.sourcePolicyRevision,
				captureConsentEpoch = wal.captureConsentEpoch,
				collectedDataEpoch = wal.capturedCollectedDataEpoch,
				logicalTrackingId = scope?.logicalTrackingId,
				serviceRunId = scope?.serviceRunId,
				scopeDeletionGeneration = scope?.scopeDeletionGeneration,
				logicalRunScopeDigest = scope?.logicalRunScopeDigest,
				walPayloadChecksum = wal.payloadChecksum,
				walIntegrityIdentity = wal.integrityIdentity,
				proofAuthorityFingerprint = proofAuthorityFingerprint,
				reason = REASON_RETENTION_EXPIRED,
				effectChecksum = "",
			)
			return unsigned.copy(effectChecksum = unsigned.calculatedEffectChecksum())
		}

		private fun requireOriginalWal(wal: SourceEventWalEntity) {
			require(wal.hasQualifiedIntegrity())
			require(wal.sourceKind in SUPPORTED_SOURCE_KINDS)
			require(wal.eventId.isNotBlank())
			require(wal.admissionOrdinal > 0L)
			require(wal.authorizationPurposeEligibilityMask in 0L..SourceBrokerPurpose.ALL_MASK)
			require(wal.sourcePolicyRevision == null || wal.sourcePolicyRevision > 0L)
			require(wal.captureConsentEpoch == null || wal.captureConsentEpoch >= 0L)
			require(wal.capturedCollectedDataEpoch >= 0L)
			require(wal.clockDomainId.isNotBlank())
			require(wal.sourceInstanceId.isNotBlank())
			require(wal.registrationGeneration >= 0L)
			require(wal.payloadChecksum.matches(SHA_256_HEX))
			require(wal.integrityIdentity.matches(SHA_256_HEX))
		}

		// Fixed immutable capture fields are validated before constructing their saved scope identity.
		@Suppress("CyclomaticComplexMethod")
		private fun captureScopeFromWal(wal: SourceEventWalEntity): RetainedCaptureScopeIdentity? {
			val captureEligible =
				wal.authorizationPurposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L
			return if (captureEligible) {
				require(wal.sourcePolicyRevision != null && wal.sourcePolicyRevision > 0L)
				require(wal.captureConsentEpoch != null && wal.captureConsentEpoch >= 0L)
				require(wal.sessionManifestRevision != null && wal.sessionManifestRevision > 0L)
				require(wal.lifecycleLeaseGeneration != null && wal.lifecycleLeaseGeneration > 0L)
				require(wal.registrationGeneration > 0L)
				require(!wal.physicalConfigurationFingerprint.isNullOrBlank())
				require(wal.authorizationRevision != null && wal.authorizationRevision > 0L)
				require(!wal.authorizationFingerprint.isNullOrBlank())
				require(wal.configRevision != null && wal.configRevision > 0L)
				val logicalId = requireNotNull(wal.logicalTrackingId).also {
					require(it.isNotBlank())
				}
				val runId = requireNotNull(wal.serviceRunId).also {
					require(it.isNotBlank())
				}
				val logicalRunScopeDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
					sourceKind = wal.sourceKind,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					logicalTrackingId = logicalId,
					serviceRunId = runId,
				)
				RetainedCaptureScopeIdentity(
					sourceKind = wal.sourceKind,
					logicalTrackingId = logicalId,
					serviceRunId = runId,
					collectedDataEpoch = wal.capturedCollectedDataEpoch,
					scopeDeletionGeneration = 0L,
					logicalRunScopeDigest = logicalRunScopeDigest,
				)
			} else {
				require(wal.logicalTrackingId == null && wal.serviceRunId == null)
				null
			}
		}

		private fun authorityFingerprint(
			wal: SourceEventWalEntity,
			scope: RetainedCaptureScopeIdentity?,
		): String = checksum(
			AUTHORITY_CONTRACT,
			wal.sourceKind,
			SourceBrokerPurpose.SESSION_CAPTURE.takeIf { scope != null },
			wal.authorizationPurposeEligibilityMask,
			wal.eventId,
			wal.admissionOrdinal,
			wal.sourcePolicyRevision,
			wal.captureConsentEpoch,
			wal.capturedCollectedDataEpoch,
			wal.logicalTrackingId,
			wal.serviceRunId,
			scope?.logicalTrackingId,
			scope?.serviceRunId,
			scope?.scopeDeletionGeneration,
			scope?.logicalRunScopeDigest,
			wal.providerDedupKey,
			wal.deliveryIdentity,
			wal.deliveryUnitIndex,
			wal.deliveryUnitCount,
			wal.sourceInstanceId,
			wal.registrationGeneration,
			wal.physicalConfigurationFingerprint,
			wal.authorizationRevision,
			wal.authorizationFingerprint,
			wal.sourceSequence,
			wal.configRevision,
			wal.planAttribution,
			wal.clockDomainId,
			wal.observedElapsedNanos,
			wal.observedIntervalStartNanos,
			wal.receivedElapsedNanos,
			wal.receivedWallTimeMs,
			wal.wallTimeMs,
			wal.wallTimeUncertaintyMs,
			wal.activityAutomationEpoch,
			wal.sessionManifestRevision,
			wal.lifecycleLeaseGeneration,
			wal.acquiredAtMs,
			wal.qualityFlags,
			wal.qualityConfidence,
			wal.payloadVersion,
			wal.payloadChecksum,
			wal.integrityIdentity,
			wal.createdAtMs,
		)

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
