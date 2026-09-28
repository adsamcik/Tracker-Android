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
			"WHERE source_kind = :sourceKind AND source_event_id = :sourceEventId LIMIT 1",
	)
	suspend fun byEvent(
		sourceKind: Int,
		sourceEventId: String,
	): CapturedRadioFactRetentionProofEntity?

	@Query(
		"SELECT * FROM captured_radio_fact_retention_proof " +
			"WHERE source_kind = :sourceKind ORDER BY source_admission_ordinal",
	)
	suspend fun allForSource(sourceKind: Int): List<CapturedRadioFactRetentionProofEntity>

	@Query(
		"SELECT * FROM captured_radio_fact_retention_proof " +
			"WHERE source_kind = :sourceKind AND logical_tracking_id = :logicalTrackingId " +
			"AND service_run_id = :serviceRunId AND collected_data_epoch = :collectedDataEpoch " +
			"AND source_admission_ordinal >= :firstAdmissionOrdinal " +
			"AND source_admission_ordinal <= :lastAdmissionOrdinal " +
			"AND retained_from_ms <= :retainedFromMs " +
			"ORDER BY source_admission_ordinal LIMIT 1",
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
