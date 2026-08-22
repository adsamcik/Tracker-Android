package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest

@Entity(
	tableName = "source_event_wal",
	indices = [
		Index(value = ["event_id"], unique = true, name = "idx_source_event_wal_event_id"),
		Index(
			value = ["source_kind", "provider_dedup_key"],
			unique = true,
			name = "idx_source_event_wal_provider_dedup",
		),
		Index(
			value = ["logical_tracking_id", "admission_ordinal"],
			name = "idx_source_event_wal_tracking_ordinal",
		),
		Index(
			value = ["source_kind", "source_instance_id", "source_sequence"],
			unique = true,
			name = "idx_source_event_wal_source_sequence",
		),
		Index(
			value = ["captured_collected_data_epoch", "acquired_at_ms"],
			name = "idx_source_event_wal_lifecycle",
		),
		Index(
			value = ["created_at_ms", "admission_ordinal"],
			name = "idx_source_event_wal_retention",
		),
		Index(
			value = [
				"source_kind",
				"captured_collected_data_epoch",
				"clock_domain_id",
				"delivery_identity",
				"delivery_unit_index",
			],
			unique = true,
			name = "idx_source_event_wal_delivery_unit",
		),
	],
)
data class SourceEventWalEntity(
	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "admission_ordinal")
	val admissionOrdinal: Long = 0L,
	@ColumnInfo(name = "event_id")
	val eventId: String,
	@ColumnInfo(name = "provider_dedup_key")
	val providerDedupKey: String?,
	@ColumnInfo(name = "delivery_identity")
	val deliveryIdentity: String? = null,
	@ColumnInfo(name = "delivery_unit_index")
	val deliveryUnitIndex: Int? = null,
	@ColumnInfo(name = "delivery_unit_count")
	val deliveryUnitCount: Int? = null,
	@ColumnInfo(name = "logical_tracking_id")
	val logicalTrackingId: String?,
	@ColumnInfo(name = "service_run_id")
	val serviceRunId: String?,
	@ColumnInfo(name = "source_kind")
	val sourceKind: Int,
	@ColumnInfo(name = "source_instance_id")
	val sourceInstanceId: String,
	@ColumnInfo(name = "registration_generation")
	val registrationGeneration: Long,
	@ColumnInfo(name = "physical_configuration_fingerprint")
	val physicalConfigurationFingerprint: String? = null,
	@ColumnInfo(name = "authorization_revision")
	val authorizationRevision: Long? = null,
	@ColumnInfo(name = "authorization_purpose_eligibility_mask", defaultValue = "0")
	val authorizationPurposeEligibilityMask: Long = 0L,
	@ColumnInfo(name = "authorization_fingerprint")
	val authorizationFingerprint: String? = null,
	@ColumnInfo(name = "source_sequence")
	val sourceSequence: Long,
	@ColumnInfo(name = "config_revision")
	val configRevision: Long?,
	@ColumnInfo(name = "plan_attribution")
	val planAttribution: Int,
	@ColumnInfo(name = "clock_domain_id")
	val clockDomainId: String,
	@ColumnInfo(name = "observed_elapsed_nanos")
	val observedElapsedNanos: Long,
	@ColumnInfo(name = "observed_interval_start_nanos")
	val observedIntervalStartNanos: Long? = null,
	@ColumnInfo(name = "received_elapsed_nanos")
	val receivedElapsedNanos: Long,
	@ColumnInfo(name = "wall_time_ms")
	val wallTimeMs: Long?,
	@ColumnInfo(name = "wall_time_uncertainty_ms")
	val wallTimeUncertaintyMs: Long?,
	@ColumnInfo(name = "captured_collected_data_epoch")
	val capturedCollectedDataEpoch: Long,
	@ColumnInfo(name = "source_policy_revision")
	val sourcePolicyRevision: Long? = null,
	@ColumnInfo(name = "capture_consent_epoch")
	val captureConsentEpoch: Long? = null,
	@ColumnInfo(name = "session_manifest_revision")
	val sessionManifestRevision: Long? = null,
	@ColumnInfo(name = "lifecycle_lease_generation")
	val lifecycleLeaseGeneration: Long? = null,
	@ColumnInfo(name = "acquired_at_ms")
	val acquiredAtMs: Long,
	@ColumnInfo(name = "quality_flags")
	val qualityFlags: Long,
	@ColumnInfo(name = "quality_confidence")
	val qualityConfidence: Float?,
	@ColumnInfo(name = "payload_version")
	val payloadVersion: Int,
	@ColumnInfo(name = "payload")
	val payload: ByteArray,
	@ColumnInfo(name = "payload_checksum")
	val payloadChecksum: String,
	@ColumnInfo(name = "integrity_identity", defaultValue = "'LEGACY_PENDING_CHECKSUM'")
	val integrityIdentity: String = LEGACY_PENDING_CHECKSUM,
	@ColumnInfo(name = "created_at_ms")
	val createdAtMs: Long,
) {
	fun calculatedPayloadChecksum(): String = payload.sha256()

	fun calculatedIntegrityIdentity(): String {
		val canonical = listOf(
			deliveryIdentity,
			deliveryUnitIndex,
			deliveryUnitCount,
			logicalTrackingId,
			serviceRunId,
			sourceKind,
			sourceInstanceId,
			registrationGeneration,
			physicalConfigurationFingerprint,
			authorizationRevision,
			authorizationPurposeEligibilityMask,
			authorizationFingerprint,
			sourceSequence,
			providerDedupKey,
			configRevision,
			planAttribution,
			clockDomainId,
			observedElapsedNanos,
			observedIntervalStartNanos,
			receivedElapsedNanos,
			wallTimeMs,
			wallTimeUncertaintyMs,
			capturedCollectedDataEpoch,
			sourcePolicyRevision,
			captureConsentEpoch,
			sessionManifestRevision,
			lifecycleLeaseGeneration,
			acquiredAtMs,
			qualityFlags,
			qualityConfidence,
			payloadVersion,
			calculatedPayloadChecksum(),
		).joinToString(separator = "") { value ->
			val text = value?.toString()
			if (text == null) "-1:" else "${text.length}:$text"
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray())
			.joinToString("") { byte -> "%02x".format(byte) }
	}

	fun hasQualifiedIntegrity(): Boolean = integrityIdentity !in LEGACY_INTEGRITY_IDENTITIES &&
		payloadChecksum == calculatedPayloadChecksum() &&
		integrityIdentity == calculatedIntegrityIdentity()

	fun hasVerifiedLegacyPayload(): Boolean =
		integrityIdentity == LEGACY_CHECKSUM_VERIFIED && payloadChecksum == calculatedPayloadChecksum()

	fun hasPendingLegacyPayload(): Boolean =
		integrityIdentity == LEGACY_PENDING_CHECKSUM && payloadChecksum == calculatedPayloadChecksum()

	companion object {
		const val LEGACY_PENDING_CHECKSUM = "LEGACY_PENDING_CHECKSUM"
		const val LEGACY_CHECKSUM_VERIFIED = "LEGACY_CHECKSUM_VERIFIED"
		const val LEGACY_CHECKSUM_MISMATCH = "LEGACY_CHECKSUM_MISMATCH"
		val LEGACY_INTEGRITY_IDENTITIES = setOf(
			LEGACY_PENDING_CHECKSUM,
			LEGACY_CHECKSUM_VERIFIED,
			LEGACY_CHECKSUM_MISMATCH,
			"LEGACY_UNKNOWN",
		)
	}
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
	.digest(this)
	.joinToString(separator = "") { byte -> "%02x".format(byte) }
