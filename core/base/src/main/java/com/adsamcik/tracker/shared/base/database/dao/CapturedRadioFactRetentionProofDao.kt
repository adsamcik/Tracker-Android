package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.CapturedRadioFactRetentionProofEntity

@Dao
interface CapturedRadioFactRetentionProofDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insert(proof: CapturedRadioFactRetentionProofEntity): Long

	@Query(
		"SELECT * FROM captured_radio_fact_retention_proof WHERE source_kind = :sourceKind " +
			"AND writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id = :logicalFactId LIMIT 1",
	)
	suspend fun byFact(
		sourceKind: Int,
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
	): CapturedRadioFactRetentionProofEntity?

	@Query(
		"SELECT * FROM captured_radio_fact_retention_proof " +
			"WHERE source_kind = :sourceKind AND source_event_id = :sourceEventId " +
			"ORDER BY logical_fact_id LIMIT 2",
	)
	suspend fun eventCandidates(
		sourceKind: Int,
		sourceEventId: String,
	): List<CapturedRadioFactRetentionProofEntity>

	/** Legacy unscoped lookup is usable only when the event has one retained fact. */
	suspend fun byEvent(sourceKind: Int, sourceEventId: String): CapturedRadioFactRetentionProofEntity? =
		eventCandidates(sourceKind, sourceEventId).singleOrNull()

	@Query(
		"SELECT * FROM captured_radio_fact_retention_proof " +
			"WHERE source_kind = :sourceKind AND source_event_id = :sourceEventId " +
			"AND logical_tracking_id = :logicalTrackingId AND service_run_id = :serviceRunId " +
			"AND source_policy_revision = :sourcePolicyRevision " +
			"AND capture_consent_epoch = :captureConsentEpoch " +
			"ORDER BY logical_fact_id LIMIT 2",
	)
	suspend fun eventCandidatesForRun(
		sourceKind: Int,
		sourceEventId: String,
		logicalTrackingId: String,
		serviceRunId: String,
		sourcePolicyRevision: Long,
		captureConsentEpoch: Long,
	): List<CapturedRadioFactRetentionProofEntity>

	@Query(
		"SELECT EXISTS(SELECT 1 FROM captured_radio_fact_retention_proof " +
			"WHERE source_kind = :sourceKind AND source_event_id = :sourceEventId " +
			"AND (source_admission_ordinal != :sourceAdmissionOrdinal " +
			"OR collected_data_epoch != :collectedDataEpoch " +
			"OR wal_integrity_identity != :walIntegrityIdentity))",
	)
	suspend fun hasConflictingEventIdentity(
		sourceKind: Int,
		sourceEventId: String,
		sourceAdmissionOrdinal: Long,
		collectedDataEpoch: Long,
		walIntegrityIdentity: String,
	): Boolean

	@Query(
		"SELECT * FROM captured_radio_fact_retention_proof " +
			"WHERE source_kind = :sourceKind ORDER BY source_admission_ordinal, logical_fact_id",
	)
	suspend fun allForSource(sourceKind: Int): List<CapturedRadioFactRetentionProofEntity>

	@Query(
		"SELECT * FROM captured_radio_fact_retention_proof " +
			"WHERE source_kind = :sourceKind AND logical_tracking_id = :logicalTrackingId " +
			"AND service_run_id = :serviceRunId AND collected_data_epoch = :collectedDataEpoch " +
			"AND source_admission_ordinal >= :firstAdmissionOrdinal " +
			"AND source_admission_ordinal <= :lastAdmissionOrdinal " +
			"AND retained_from_ms <= :retainedFromMs " +
			"ORDER BY source_admission_ordinal, logical_fact_id LIMIT 1",
	)
	suspend fun firstForCapturedRun(
		sourceKind: Int,
		logicalTrackingId: String,
		serviceRunId: String,
		collectedDataEpoch: Long,
		firstAdmissionOrdinal: Long,
		lastAdmissionOrdinal: Long,
		retainedFromMs: Long,
	): CapturedRadioFactRetentionProofEntity?

	@Query(
		"SELECT * FROM captured_radio_fact_retention_proof " +
			"WHERE source_kind = :sourceKind AND (:afterAdmissionOrdinal IS NULL OR " +
			"source_admission_ordinal > :afterAdmissionOrdinal OR " +
			"(source_admission_ordinal = :afterAdmissionOrdinal AND " +
			"logical_fact_id > :afterLogicalFactId)) " +
			"ORDER BY source_admission_ordinal, logical_fact_id LIMIT :limit",
	)
	suspend fun sourceErasePage(
		sourceKind: Int,
		afterAdmissionOrdinal: Long?,
		afterLogicalFactId: String?,
		limit: Int,
	): List<CapturedRadioFactRetentionProofEntity>

	@Query(
		"DELETE FROM captured_radio_fact_retention_proof WHERE source_kind = :sourceKind",
	)
	suspend fun deleteSource(sourceKind: Int): Int

	@Query("SELECT COUNT(*) FROM captured_radio_fact_retention_proof")
	suspend fun countAll(): Long

	@Query("DELETE FROM captured_radio_fact_retention_proof")
	fun deleteAll()
}
