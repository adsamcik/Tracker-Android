package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity

@Dao
interface SourceEventWalDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertIgnoringDuplicate(entity: SourceEventWalEntity): Long

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertDeliveryUnits(entities: List<SourceEventWalEntity>): List<Long>

	@Query("SELECT * FROM source_event_wal WHERE event_id = :eventId LIMIT 1")
	suspend fun getByEventId(eventId: String): SourceEventWalEntity?

	@Query(
		"SELECT event_id, admission_ordinal, provider_dedup_key, source_instance_id, " +
			"registration_generation, physical_configuration_fingerprint, authorization_revision, " +
			"authorization_purpose_eligibility_mask, authorization_fingerprint, " +
			"source_sequence, source_policy_revision, " +
			"capture_consent_epoch, session_manifest_revision, lifecycle_lease_generation, " +
			"payload_version, payload_checksum, integrity_identity " +
			"FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND provider_dedup_key = :providerDedupKey LIMIT 1",
	)
	suspend fun identityByProviderDedupKey(
		sourceKind: Int,
		providerDedupKey: String,
	): SourceEventIdentityRow?

	@Query(
		"SELECT event_id, admission_ordinal, provider_dedup_key, source_instance_id, " +
			"registration_generation, physical_configuration_fingerprint, authorization_revision, " +
			"authorization_purpose_eligibility_mask, authorization_fingerprint, " +
			"source_sequence, source_policy_revision, " +
			"capture_consent_epoch, session_manifest_revision, lifecycle_lease_generation, " +
			"payload_version, payload_checksum, integrity_identity " +
			"FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND source_instance_id = :sourceInstanceId AND source_sequence = :sourceSequence LIMIT 1",
	)
	suspend fun identityBySourceSequence(
		sourceKind: Int,
		sourceInstanceId: String,
		sourceSequence: Long,
	): SourceEventIdentityRow?

	@Query(
		"SELECT * FROM source_event_wal " +
			"WHERE source_kind = :sourceKind AND provider_dedup_key = :providerDedupKey LIMIT 1",
	)
	suspend fun getByProviderDedupKey(sourceKind: Int, providerDedupKey: String): SourceEventWalEntity?

	@Query(
		"SELECT * FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND source_instance_id = :sourceInstanceId AND source_sequence = :sourceSequence LIMIT 1",
	)
	suspend fun getBySourceSequence(
		sourceKind: Int,
		sourceInstanceId: String,
		sourceSequence: Long,
	): SourceEventWalEntity?

	@Query(
		"SELECT event_id, admission_ordinal, delivery_unit_index, delivery_unit_count, " +
			"observed_elapsed_nanos, observed_interval_start_nanos, " +
			"payload_version, payload_checksum " +
			"FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND captured_collected_data_epoch = :collectedDataEpoch " +
			"AND clock_domain_id = :clockDomainId AND delivery_identity = :deliveryIdentity " +
			"ORDER BY delivery_unit_index ASC",
	)
	suspend fun deliveryUnits(
		sourceKind: Int,
		collectedDataEpoch: Long,
		clockDomainId: String,
		deliveryIdentity: String,
	): List<SourceDeliveryUnitIdentityRow>

	@Query(
		"SELECT * FROM source_event_wal WHERE admission_ordinal > :afterOrdinal " +
			"ORDER BY admission_ordinal ASC LIMIT :limit",
	)
	suspend fun eventsAfter(afterOrdinal: Long, limit: Int): List<SourceEventWalEntity>

	/**
	 * Reads only the immutable released-v27 recovery interval.
	 *
	 * Admission ordinals are high-water marks, not a promise of a dense sequence. Callers may move
	 * their durable cursor directly to [throughOrdinal] after this returns no rows.
	 */
	@Query(
		"SELECT * FROM source_event_wal WHERE admission_ordinal > :afterOrdinal " +
			"AND admission_ordinal <= :throughOrdinal " +
			"ORDER BY admission_ordinal ASC LIMIT :limit",
	)
	suspend fun eventsAfterThrough(
		afterOrdinal: Long,
		throughOrdinal: Long,
		limit: Int,
	): List<SourceEventWalEntity>

	@Query("SELECT MAX(admission_ordinal) FROM source_event_wal")
	suspend fun maximumAdmissionOrdinal(): Long?

	@Query("SELECT MIN(admission_ordinal) FROM source_event_wal")
	suspend fun minimumAdmissionOrdinal(): Long?

	@Query(
		"SELECT MIN(admission_ordinal) FROM source_event_wal " +
			"WHERE integrity_identity IN ('LEGACY_PENDING_CHECKSUM', 'LEGACY_CHECKSUM_VERIFIED', " +
			"'LEGACY_CHECKSUM_MISMATCH', 'LEGACY_UNKNOWN')",
	)
	suspend fun minimumUnqualifiedIntegrityOrdinal(): Long?

	@Query(
		"UPDATE source_event_wal SET integrity_identity = :classification " +
			"WHERE admission_ordinal = :admissionOrdinal AND integrity_identity = 'LEGACY_PENDING_CHECKSUM'",
	)
	suspend fun classifyPendingLegacyPayload(admissionOrdinal: Long, classification: String): Int

	@Query(
		"SELECT MIN(admission_ordinal) FROM source_event_wal " +
			"WHERE admission_ordinal <= :safeOrdinal AND created_at_ms >= :createdBeforeMs",
	)
	suspend fun firstNonPrunableOrdinal(safeOrdinal: Long, createdBeforeMs: Long): Long?

	@Query("SELECT COUNT(*) FROM source_event_wal")
	suspend fun countAll(): Long

	@Query("SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM source_event_wal")
	suspend fun payloadBytes(): Long

	@Query(
		"DELETE FROM source_event_wal WHERE admission_ordinal IN (" +
			"SELECT admission_ordinal FROM source_event_wal " +
			"WHERE created_at_ms < :createdBeforeMs AND admission_ordinal <= :safeOrdinal " +
			"ORDER BY created_at_ms, admission_ordinal LIMIT :limit)",
	)
	suspend fun deleteProjectedBatch(
		safeOrdinal: Long,
		createdBeforeMs: Long,
		limit: Int,
	): Int

	@Query(
		"DELETE FROM source_event_wal WHERE admission_ordinal IN (" +
			"SELECT admission_ordinal FROM source_event_wal " +
			"WHERE admission_ordinal <= :pruneThroughOrdinal " +
			"ORDER BY admission_ordinal LIMIT :limit)",
	)
	suspend fun deleteContiguousPrefixBatch(pruneThroughOrdinal: Long, limit: Int): Int

	@Query("DELETE FROM source_event_wal")
	fun deleteAll()
}

/** Covering projection used by duplicate admission checks so payload BLOBs are never read. */
data class SourceEventIdentityRow(
	@ColumnInfo(name = "event_id") val eventId: String,
	@ColumnInfo(name = "admission_ordinal") val admissionOrdinal: Long,
	@ColumnInfo(name = "provider_dedup_key") val providerDedupKey: String?,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "physical_configuration_fingerprint") val physicalConfigurationFingerprint: String?,
	@ColumnInfo(name = "authorization_revision") val authorizationRevision: Long?,
	@ColumnInfo(name = "authorization_purpose_eligibility_mask") val authorizationPurposeEligibilityMask: Long,
	@ColumnInfo(name = "authorization_fingerprint") val authorizationFingerprint: String?,
	@ColumnInfo(name = "source_sequence") val sourceSequence: Long,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long?,
	@ColumnInfo(name = "capture_consent_epoch") val captureConsentEpoch: Long?,
	@ColumnInfo(name = "session_manifest_revision") val sessionManifestRevision: Long?,
	@ColumnInfo(name = "lifecycle_lease_generation") val lifecycleLeaseGeneration: Long?,
	@ColumnInfo(name = "payload_version") val payloadVersion: Int,
	@ColumnInfo(name = "payload_checksum") val payloadChecksum: String,
	@ColumnInfo(name = "integrity_identity") val integrityIdentity: String,
)

/** Payload-free projection used to recognize an exact replay of a process-stable delivery. */
data class SourceDeliveryUnitIdentityRow(
	@ColumnInfo(name = "event_id") val eventId: String,
	@ColumnInfo(name = "admission_ordinal") val admissionOrdinal: Long,
	@ColumnInfo(name = "delivery_unit_index") val deliveryUnitIndex: Int?,
	@ColumnInfo(name = "delivery_unit_count") val deliveryUnitCount: Int?,
	@ColumnInfo(name = "observed_elapsed_nanos") val observedElapsedNanos: Long,
	@ColumnInfo(name = "observed_interval_start_nanos") val observedIntervalStartNanos: Long?,
	@ColumnInfo(name = "payload_version") val payloadVersion: Int,
	@ColumnInfo(name = "payload_checksum") val payloadChecksum: String,
)
