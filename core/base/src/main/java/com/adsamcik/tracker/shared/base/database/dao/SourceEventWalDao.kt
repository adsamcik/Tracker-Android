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
		"SELECT admission_ordinal, source_kind, captured_collected_data_epoch, " +
			"acquired_at_ms, wall_time_ms FROM source_event_wal " +
			"WHERE admission_ordinal = :admissionOrdinal LIMIT 1",
	)
	suspend fun projectionEligibilityByAdmissionOrdinal(
		admissionOrdinal: Long,
	): SourceEventProjectionEligibilityRow?

	@Query(
		"SELECT event_id, admission_ordinal, provider_dedup_key, source_instance_id, " +
			"registration_generation, physical_configuration_fingerprint, authorization_revision, " +
			"authorization_purpose_eligibility_mask, authorization_fingerprint, " +
			"source_sequence, activity_automation_epoch, source_policy_revision, " +
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
			"source_sequence, activity_automation_epoch, source_policy_revision, " +
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
	 * Authoritative location evidence that does not yet have its canonical raw-observation row.
	 *
	 * The WAL remains the recovery authority. Paging by its durable admission ordinal prevents a
	 * missing destination row from being hidden by projection state, while the destination's unique
	 * `source_event_id` keeps repair exactly once in effect.
	 */
	@Query(
		"SELECT wal.* FROM source_event_wal AS wal " +
			"LEFT JOIN location_observation AS observation " +
			"ON observation.source_event_id = wal.event_id " +
			"WHERE wal.source_kind = :sourceKind AND wal.admission_ordinal > :afterOrdinal " +
			"AND observation.id IS NULL " +
			"ORDER BY wal.admission_ordinal ASC LIMIT :limit",
	)
	suspend fun locationEventsMissingCanonicalObservation(
		sourceKind: Int,
		afterOrdinal: Long,
		limit: Int,
	): List<SourceEventWalEntity>

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

	@Query(
		"SELECT * FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND admission_ordinal > :afterOrdinal AND admission_ordinal <= :throughOrdinal " +
			"ORDER BY admission_ordinal ASC LIMIT :limit",
	)
	suspend fun sourceEventsAfterThrough(
		sourceKind: Int,
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

	@Query("SELECT COUNT(*) FROM source_event_wal")
	suspend fun countAll(): Long

	@Query("SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM source_event_wal")
	suspend fun payloadBytes(): Long

	@Query(
		"SELECT DISTINCT source_kind FROM source_event_wal " +
			"WHERE admission_ordinal <= :safeOrdinal ORDER BY source_kind",
	)
	suspend fun sourceKindsThrough(safeOrdinal: Long): List<Int>

	/**
	 * Deletes TTL-eligible facts for one source through its own safe checkpoint.
	 *
	 * Outbox delivery is independent once its payload is durable, so a pending non-retaining control
	 * effect cannot pin raw WAL. Receipt cleanup runs first while the source association is present;
	 * any bounded remainder is handled conservatively by writer identity as an outbox-only row.
	 */
	@Query(
		"DELETE FROM source_event_wal WHERE admission_ordinal IN (" +
			"SELECT wal.admission_ordinal FROM source_event_wal AS wal " +
			"WHERE wal.source_kind = :sourceKind AND wal.created_at_ms < :createdBeforeMs " +
			"AND wal.admission_ordinal <= :safeOrdinal " +
			"ORDER BY wal.created_at_ms, wal.admission_ordinal LIMIT :limit)",
	)
	suspend fun deleteProjectedSourceBatch(
		sourceKind: Int,
		safeOrdinal: Long,
		createdBeforeMs: Long,
		limit: Int,
	): Int

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
	@ColumnInfo(name = "activity_automation_epoch") val activityAutomationEpoch: Long?,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long?,
	@ColumnInfo(name = "capture_consent_epoch") val captureConsentEpoch: Long?,
	@ColumnInfo(name = "session_manifest_revision") val sessionManifestRevision: Long?,
	@ColumnInfo(name = "lifecycle_lease_generation") val lifecycleLeaseGeneration: Long?,
	@ColumnInfo(name = "payload_version") val payloadVersion: Int,
	@ColumnInfo(name = "payload_checksum") val payloadChecksum: String,
	@ColumnInfo(name = "integrity_identity") val integrityIdentity: String,
)

/** Payload-free lifecycle identity used to reconcile a terminal source-writer failure. */
data class SourceEventProjectionEligibilityRow(
	@ColumnInfo(name = "admission_ordinal") val admissionOrdinal: Long,
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "captured_collected_data_epoch") val capturedCollectedDataEpoch: Long,
	@ColumnInfo(name = "acquired_at_ms") val acquiredAtMs: Long,
	@ColumnInfo(name = "wall_time_ms") val wallTimeMs: Long?,
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
