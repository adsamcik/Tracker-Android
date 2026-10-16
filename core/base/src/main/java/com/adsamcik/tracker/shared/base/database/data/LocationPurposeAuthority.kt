package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Physical accepted-fix evidence. Purpose grants never duplicate the canonical coordinate row. */
@Entity(
	tableName = "location_accepted_receipt",
	primaryKeys = ["sample_id"],
	foreignKeys = [ForeignKey(
		entity = LocationSample::class, parentColumns = ["id"], childColumns = ["sample_id"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(value = ["source_event_id"], unique = true, name = "idx_location_receipt_event"),
		Index(value = ["source_instance_id", "source_sequence"], unique = true, name = "idx_location_receipt_sequence"),
		Index(value = ["observed_wall_time_ms", "sample_id"], name = "idx_location_receipt_time"),
	],
)
data class LocationAcceptedReceiptEntity(
	@ColumnInfo(name = "sample_id") val sampleId: Long,
	@ColumnInfo(name = "source_event_id") val sourceEventId: String,
	@ColumnInfo(name = "source_signal_id") val sourceSignalId: String,
	@ColumnInfo(name = "source_admission_ordinal") val sourceAdmissionOrdinal: Long,
	@ColumnInfo(name = "wal_integrity_identity") val walIntegrityIdentity: String,
	@ColumnInfo(name = "canonical_content_checksum") val canonicalContentChecksum: String,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "source_sequence") val sourceSequence: Long,
	@ColumnInfo(name = "delivery_identity") val deliveryIdentity: String,
	@ColumnInfo(name = "delivery_unit_index") val deliveryUnitIndex: Int,
	@ColumnInfo(name = "delivery_unit_count") val deliveryUnitCount: Int,
	@ColumnInfo(name = "writer_id") val writerId: String,
	@ColumnInfo(name = "writer_version") val writerVersion: Int,
	@ColumnInfo(name = "configuration_revision") val configurationRevision: Long,
	@ColumnInfo(name = "physical_configuration_fingerprint") val physicalConfigurationFingerprint: String,
	@ColumnInfo(name = "authorization_revision") val authorizationRevision: Long,
	@ColumnInfo(name = "authorization_fingerprint") val authorizationFingerprint: String,
	@ColumnInfo(name = "clock_domain_id") val clockDomainId: String,
	@ColumnInfo(name = "boot_id") val bootId: String,
	@ColumnInfo(name = "observed_elapsed_realtime_nanos") val observedElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "received_elapsed_realtime_nanos") val receivedElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "observed_wall_time_ms") val observedWallTimeMs: Long,
	@ColumnInfo(name = "received_wall_time_ms") val receivedWallTimeMs: Long?,
	@ColumnInfo(name = "wall_time_uncertainty_ms") val wallTimeUncertaintyMs: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "retained_proof_version", defaultValue = "0") val retainedProofVersion: Int = 0,
	@ColumnInfo(name = "retained_proof_checksum", defaultValue = "''") val retainedProofChecksum: String = "",
) {
	init {
		require(sampleId > 0 && sourceAdmissionOrdinal > 0 && registrationGeneration > 0)
		require(sourceSequence >= 0 && configurationRevision >= 0 && authorizationRevision > 0)
		require(deliveryUnitCount > 0 && deliveryUnitIndex in 0 until deliveryUnitCount)
		require(writerVersion > 0 && collectedDataEpoch >= 0)
		require(listOf(sourceEventId, sourceSignalId, sourceInstanceId, deliveryIdentity, writerId,
			physicalConfigurationFingerprint, authorizationFingerprint, clockDomainId, bootId).all(String::isNotBlank))
		require(walIntegrityIdentity.matches(DIGEST) && canonicalContentChecksum.matches(DIGEST))
		require(observedElapsedRealtimeNanos > 0 && receivedElapsedRealtimeNanos >= observedElapsedRealtimeNanos)
		require(observedWallTimeMs >= 0 && wallTimeUncertaintyMs >= 0)
		require(receivedWallTimeMs == null || receivedWallTimeMs >= 0)
		require(retainedProofVersion in 0..1)
		require(if (retainedProofVersion == 0) {
			retainedProofChecksum.isEmpty()
		} else {
			DIGEST.matches(retainedProofChecksum)
		})
	}
	private companion object { val DIGEST = Regex("[0-9a-f]{64}") }
}

/** Immutable tagged authority. Ambient has no logical tracking, service run, segment or manifest. */
@Entity(
	tableName = "location_sample_purpose_authority",
	primaryKeys = ["sample_id", "purpose", "authority_id"],
	foreignKeys = [ForeignKey(
		entity = LocationAcceptedReceiptEntity::class,
		parentColumns = ["sample_id"], childColumns = ["sample_id"], onDelete = ForeignKey.CASCADE,
	)],
	indices = [Index(value = ["purpose", "authority_id", "sample_id"], name = "idx_location_purpose_authority")],
)
data class LocationSamplePurposeAuthorityEntity(
	@ColumnInfo(name = "sample_id") val sampleId: Long,
	@ColumnInfo(name = "purpose") val purpose: String,
	@ColumnInfo(name = "authority_id") val authorityId: String,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long,
	@ColumnInfo(name = "consent_epoch") val consentEpoch: Long,
	@ColumnInfo(name = "effective_from_elapsed_nanos") val effectiveFromElapsedNanos: Long,
	@ColumnInfo(name = "effective_until_elapsed_nanos") val effectiveUntilElapsedNanos: Long,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String?,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String?,
	@ColumnInfo(name = "session_segment_id") val sessionSegmentId: Long?,
	@ColumnInfo(name = "session_manifest_revision") val sessionManifestRevision: Long?,
	@ColumnInfo(name = "lifecycle_lease_generation") val lifecycleLeaseGeneration: Long?,
	@ColumnInfo(name = "ambient_intent_id") val ambientIntentId: String?,
	@ColumnInfo(name = "retention_policy_id") val retentionPolicyId: String?,
	@ColumnInfo(name = "retention_approval_revision") val retentionApprovalRevision: Long?,
	@ColumnInfo(name = "scope_deletion_generation") val scopeDeletionGeneration: Long,
	@ColumnInfo(name = "zone_id") val zoneId: String?,
	@ColumnInfo(name = "retained_proof_checksum", defaultValue = "''") val retainedProofChecksum: String = "",
) {
	init {
		require(sampleId > 0 && authorityId.isNotBlank() && sourcePolicyRevision > 0 && consentEpoch >= 0)
		require(effectiveFromElapsedNanos >= 0 && effectiveUntilElapsedNanos > effectiveFromElapsedNanos)
		require(scopeDeletionGeneration >= 0)
		require(zoneId == null || runCatching { java.time.ZoneId.of(zoneId) }.isSuccess)
		require(retainedProofChecksum.isEmpty() || retainedProofChecksum.matches(Regex("[0-9a-f]{64}")))
		when (purpose) {
			SourceBrokerPurpose.SESSION_CAPTURE -> {
				require(zoneId != null)
				require(!logicalTrackingId.isNullOrBlank() && !serviceRunId.isNullOrBlank())
				require(sessionSegmentId != null && sessionSegmentId > 0)
				require(sessionManifestRevision != null && sessionManifestRevision > 0)
				require(lifecycleLeaseGeneration != null && lifecycleLeaseGeneration > 0)
				require(ambientIntentId == null && retentionPolicyId == null && retentionApprovalRevision == null)
			}
			SourceBrokerPurpose.AMBIENT_PRODUCT -> {
				require(logicalTrackingId == null && serviceRunId == null && sessionSegmentId == null)
				require(sessionManifestRevision == null && lifecycleLeaseGeneration == null)
				require(!ambientIntentId.isNullOrBlank() && !retentionPolicyId.isNullOrBlank())
				require(retentionApprovalRevision != null && retentionApprovalRevision > 0)
			}
			else -> error("Location product association cannot grant CONTROL authority")
		}
	}
}
