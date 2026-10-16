package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.CapturedRadioFactRetentionProofEntity

/**
 * Stores per-fact radio retention witnesses separately from short-lived raw event payloads.
 */
@Dao
interface CapturedRadioFactRetentionProofDao {
	/**
	 * Inserts [CapturedRadioFactRetentionProofEntity] rows. Existing conflicting rows remain
	 * unchanged. An ignored insert returns -1 for that row.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insert(proof: CapturedRadioFactRetentionProofEntity): Long

	/**
	 * Reads `captured_radio_fact_retention_proof` rows. Query conditions include `source_kind =
	 * :sourceKind`, `writer_projection_id = :writerProjectionId`, `writer_projection_version =
	 * :writerProjectionVersion`, `logical_fact_id = :logicalFactId`. Returns at most 1 row. Returns
	 * null when no row matches.
	 */
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

	/**
	 * Reads `captured_radio_fact_retention_proof` rows. Query conditions include `source_kind =
	 * :sourceKind`, `source_event_id = :sourceEventId`. Orders by `logical_fact_id`. Returns at most
	 * 2 rows.
	 */
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

	/**
	 * Reads `captured_radio_fact_retention_proof` rows. Query conditions include `source_kind =
	 * :sourceKind`, `source_event_id = :sourceEventId`, `logical_tracking_id = :logicalTrackingId`,
	 * `service_run_id = :serviceRunId`. Orders by `logical_fact_id`. Returns at most 2 rows.
	 */
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

	/**
	 * Reports whether a row exists within the query scope. Query conditions include `source_kind =
	 * :sourceKind`, `source_event_id = :sourceEventId`, `source_admission_ordinal !=
	 * :sourceAdmissionOrdinal`, `collected_data_epoch != :collectedDataEpoch`.
	 */
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

	/**
	 * Reads `captured_radio_fact_retention_proof` rows. Query conditions include `source_kind =
	 * :sourceKind`. Orders by `source_admission_ordinal, logical_fact_id`.
	 */
	@Query(
		"SELECT * FROM captured_radio_fact_retention_proof " +
			"WHERE source_kind = :sourceKind ORDER BY source_admission_ordinal, logical_fact_id",
	)
	suspend fun allForSource(sourceKind: Int): List<CapturedRadioFactRetentionProofEntity>

	/**
	 * Reads `captured_radio_fact_retention_proof` rows. Query conditions include `source_kind =
	 * :sourceKind`, `logical_tracking_id = :logicalTrackingId`, `service_run_id = :serviceRunId`,
	 * `collected_data_epoch = :collectedDataEpoch`. Orders by `source_admission_ordinal,
	 * logical_fact_id`. Returns at most 1 row. Returns null when no row matches. Timestamp bounds use
	 * UTC milliseconds.
	 */
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

	/**
	 * Reads `captured_radio_fact_retention_proof` rows. Query conditions include `source_kind =
	 * :sourceKind`, `source_admission_ordinal > :afterAdmissionOrdinal`, `source_admission_ordinal =
	 * :afterAdmissionOrdinal`, `logical_fact_id > :afterLogicalFactId`. Orders by
	 * `source_admission_ordinal, logical_fact_id`. Returns at most `limit` rows.
	 */
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

	/**
	 * Deletes `captured_radio_fact_retention_proof` rows matching the supplied scope. Query
	 * conditions include `source_kind = :sourceKind`. Returns the affected row count.
	 */
	@Query(
		"DELETE FROM captured_radio_fact_retention_proof WHERE source_kind = :sourceKind",
	)
	suspend fun deleteSource(sourceKind: Int): Int

	/**
	 * Counts rows in `captured_radio_fact_retention_proof`.
	 */
	@Query("SELECT COUNT(*) FROM captured_radio_fact_retention_proof")
	suspend fun countAll(): Long

	/**
	 * Deletes `captured_radio_fact_retention_proof` rows.
	 */
	@Query("DELETE FROM captured_radio_fact_retention_proof")
	fun deleteAll()
}
