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

	fun authenticatedCaptureScope(
		expectedSourceKind: Int,
		expectedCollectedDataEpoch: Long,
	): RetainedCaptureScopeIdentity? {
		if (!hasValidStoredIntegrity() ||
			sourceKind != expectedSourceKind ||
			purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
			collectedDataEpoch != expectedCollectedDataEpoch
		) return null
		return RetainedCaptureScopeIdentity(
			sourceKind = sourceKind,
			logicalTrackingId = logicalTrackingId ?: return null,
			serviceRunId = serviceRunId ?: return null,
			collectedDataEpoch = collectedDataEpoch,
			scopeDeletionGeneration = scopeDeletionGeneration ?: return null,
			logicalRunScopeDigest = logicalRunScopeDigest ?: return null,
		).takeIf { it.isCanonicalFor(expectedSourceKind, expectedCollectedDataEpoch) }
	}

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

		@Suppress("LongMethod", "LongParameterList")
		fun createFromWal(wal: SourceEventWalEntity): SourceEventRetentionProofEntity {
			require(wal.hasQualifiedIntegrity())
			val sourceKind = wal.sourceKind
			val eventId = wal.eventId
			val sourceAdmissionOrdinal = wal.admissionOrdinal
			val purposeEligibilityMask = wal.authorizationPurposeEligibilityMask
			val sourcePolicyRevision = wal.sourcePolicyRevision
			val captureConsentEpoch = wal.captureConsentEpoch
			val collectedDataEpoch = wal.capturedCollectedDataEpoch
			val logicalTrackingId = wal.logicalTrackingId
			val serviceRunId = wal.serviceRunId
			require(sourceKind in SUPPORTED_SOURCE_KINDS)
			require(eventId.isNotBlank())
			require(sourceAdmissionOrdinal > 0L)
			require(purposeEligibilityMask in 0L..SourceBrokerPurpose.ALL_MASK)
			require(sourcePolicyRevision == null || sourcePolicyRevision > 0L)
			require(captureConsentEpoch == null || captureConsentEpoch >= 0L)
			require(collectedDataEpoch >= 0L)
			require(wal.clockDomainId.isNotBlank())
			require(wal.sourceInstanceId.isNotBlank())
			require(wal.registrationGeneration >= 0L)
			require(wal.payloadChecksum.matches(SHA_256_HEX))
			require(wal.integrityIdentity.matches(SHA_256_HEX))
			val captureEligible =
				purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L
			val logicalRunScopeDigest = if (captureEligible) {
				require(sourcePolicyRevision != null && sourcePolicyRevision > 0L)
				require(captureConsentEpoch != null && captureConsentEpoch >= 0L)
				require(wal.sessionManifestRevision != null && wal.sessionManifestRevision > 0L)
				require(wal.lifecycleLeaseGeneration != null && wal.lifecycleLeaseGeneration > 0L)
				require(wal.registrationGeneration > 0L)
				require(!wal.physicalConfigurationFingerprint.isNullOrBlank())
				require(wal.authorizationRevision != null && wal.authorizationRevision > 0L)
				require(!wal.authorizationFingerprint.isNullOrBlank())
				require(wal.configRevision != null && wal.configRevision > 0L)
				val logicalId = requireNotNull(logicalTrackingId).also {
					require(it.isNotBlank())
				}
				val runId = requireNotNull(serviceRunId).also {
					require(it.isNotBlank())
				}
				SourceDeletionFenceEntity.logicalServiceRunIdentity(
					sourceKind = sourceKind,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					logicalTrackingId = logicalId,
					serviceRunId = runId,
				)
			} else {
				require(logicalTrackingId == null && serviceRunId == null)
				null
			}
			val retainedLogicalTrackingId = logicalTrackingId.takeIf { captureEligible }
			val retainedServiceRunId = serviceRunId.takeIf { captureEligible }
			val scopeDeletionGeneration = 0L.takeIf { captureEligible }
			val proofAuthorityFingerprint = checksum(
				AUTHORITY_CONTRACT,
				sourceKind,
				SourceBrokerPurpose.SESSION_CAPTURE.takeIf { captureEligible },
				purposeEligibilityMask,
				eventId,
				sourceAdmissionOrdinal,
				sourcePolicyRevision,
				captureConsentEpoch,
				collectedDataEpoch,
				wal.logicalTrackingId,
				wal.serviceRunId,
				retainedLogicalTrackingId,
				retainedServiceRunId,
				scopeDeletionGeneration,
				logicalRunScopeDigest,
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
			val unsigned = SourceEventRetentionProofEntity(
				sourceKind = sourceKind,
				eventId = eventId,
				sourceAdmissionOrdinal = sourceAdmissionOrdinal,
				purposeEligibilityMask = purposeEligibilityMask,
				sourcePolicyRevision = sourcePolicyRevision,
				captureConsentEpoch = captureConsentEpoch,
				collectedDataEpoch = collectedDataEpoch,
				logicalTrackingId = retainedLogicalTrackingId,
				serviceRunId = retainedServiceRunId,
				scopeDeletionGeneration = scopeDeletionGeneration,
				logicalRunScopeDigest = logicalRunScopeDigest,
				walPayloadChecksum = wal.payloadChecksum,
				walIntegrityIdentity = wal.integrityIdentity,
				proofAuthorityFingerprint = proofAuthorityFingerprint,
				reason = REASON_RETENTION_EXPIRED,
				effectChecksum = "",
			)
			return unsigned.copy(effectChecksum = unsigned.calculatedEffectChecksum())
		}

		private fun checksum(vararg values: Any?): String {
			val canonical = values.joinToString(separator = "") { value ->
				val text = value?.toString()
				if (text == null) "-1:" else "${text.length}:$text"
			}
			return MessageDigest.getInstance("SHA-256")
				.digest(canonical.toByteArray(Charsets.UTF_8))
				.joinToString(separator = "") { byte -> "%02x".format(byte) }
		}
	}
}
