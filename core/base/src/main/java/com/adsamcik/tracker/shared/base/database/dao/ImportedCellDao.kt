package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellObservationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity

/**
 * Cell-local imported-product storage primitives. These methods grant no live provider or capture
 * authority; the importer authenticates every bounded relation in one transaction before insert.
 */
// Keep the ImportedCellDao SQL contract together; query text accounts for this declaration's size.
@Suppress("LargeClass")
@Dao
abstract class ImportedCellDao {
	/**
	 * Inserts [ImportedCellEntryRevisionEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertEntryRevision(entry: ImportedCellEntryRevisionEntity)

	/**
	 * Inserts [ImportedCellRunEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertRun(run: ImportedCellRunEntity)

	/**
	 * Inserts [ImportedCellObservationEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertObservation(observation: ImportedCellObservationEntity)

	/**
	 * Inserts [ImportedCellReceiptEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertReceipt(receipt: ImportedCellReceiptEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	protected abstract suspend fun insertDeletionGenerationRow(
		generation: ImportedCellDeletionGenerationEntity,
	)

	/**
	 * Records one initial run-deletion fence after requiring generation 1; higher generations must
	 * use the dedicated revision operation.
	 */
	suspend fun insertDeletionGeneration(generation: ImportedCellDeletionGenerationEntity) {
		require(generation.generation == 1L)
		insertDeletionGenerationRow(generation)
	}

	/**
	 * Inserts [ImportedCellEntryDeletionEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertEntryDeletion(deletion: ImportedCellEntryDeletionEntity)

	/**
	 * Inserts [ImportedCellEntryDeletionReceiptEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertEntryDeletionReceipt(
		receipt: ImportedCellEntryDeletionReceiptEntity,
	)

	/**
	 * Inserts [ImportedCellDeletedIdentityEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertDeletedIdentities(
		identities: List<ImportedCellDeletedIdentityEntity>,
	)

	/**
	 * Reads `imported_cell_receipt` rows. Query conditions include `import_job_id = :jobId`,
	 * `import_entry_key = :entryKey`. Returns at most 1 row. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM imported_cell_receipt WHERE import_job_id = :jobId " +
			"AND import_entry_key = :entryKey LIMIT 1",
	)
	abstract suspend fun receipt(jobId: String, entryKey: String): ImportedCellReceiptEntity?

	@Query(
		"SELECT * FROM imported_cell_entry_revision WHERE identity = :identity " +
			"ORDER BY import_revision LIMIT :limit",
	)
	protected abstract suspend fun loadEntryRevisions(
		identity: String,
		limit: Int,
	): List<ImportedCellEntryRevisionEntity>

	/**
	 * Reads one Cell entry in revision order with one overflow row beyond the maximum retained
	 * correction lineage.
	 */
	suspend fun boundedEntryRevisions(identity: String): List<ImportedCellEntryRevisionEntity> =
		loadEntryRevisions(identity, MAX_REVISIONS_PER_ENTRY + 1)

	/**
	 * Reads import receipts for one entry in revision/job/key order, including one overflow row to
	 * detect excessive lineage.
	 */
	suspend fun receiptsForAdmission(identity: String): List<ImportedCellReceiptEntity> =
		loadReceipts(identity, MAX_RECEIPTS_PER_ENTRY + 1)

	@Query(
		"SELECT * FROM imported_cell_receipt WHERE entry_identity = :identity " +
			"ORDER BY entry_import_revision, import_job_id, import_entry_key LIMIT :limit",
	)
	protected abstract suspend fun loadReceipts(
		identity: String,
		limit: Int,
	): List<ImportedCellReceiptEntity>

	/**
	 * Reads saved runs across one Cell entry lineage in revision and measured-bound order, including
	 * one overflow row beyond the lineage limit.
	 */
	suspend fun allRunsForAdmission(identity: String): List<ImportedCellRunEntity> =
		loadAllRuns(identity, MAX_RUN_ROWS_PER_LINEAGE + 1)

	@Query(
		"SELECT * FROM imported_cell_run WHERE entry_identity = :identity " +
			"ORDER BY entry_import_revision, start_time_ms, end_time_ms, identity LIMIT :limit",
	)
	protected abstract suspend fun loadAllRuns(
		identity: String,
		limit: Int,
	): List<ImportedCellRunEntity>

	/**
	 * Reads saved Cell observations across one entry lineage in revision/run/coverage/measurement
	 * order with one overflow row beyond the lineage limit.
	 */
	suspend fun allObservationsForAdmission(identity: String): List<ImportedCellObservationEntity> =
		loadAllObservations(identity, MAX_OBSERVATION_ROWS_PER_LINEAGE + 1)

	@Query(
		"SELECT * FROM imported_cell_observation WHERE entry_identity = :identity " +
			"ORDER BY entry_import_revision, run_identity, coverage_start_time_ms, observed_time_ms, " +
			"identity LIMIT :limit",
	)
	protected abstract suspend fun loadAllObservations(
		identity: String,
		limit: Int,
	): List<ImportedCellObservationEntity>

	/**
	 * Reads `imported_cell_entry_deletion` rows. Query conditions include `entry_identity =
	 * :identity`. Returns at most 1 row. Returns null when no row matches.
	 */
	@Query("SELECT * FROM imported_cell_entry_deletion WHERE entry_identity = :identity LIMIT 1")
	abstract suspend fun entryDeletion(identity: String): ImportedCellEntryDeletionEntity?

	/**
	 * Reads `imported_cell_entry_deletion_receipt` rows. Query conditions include `entry_identity =
	 * :identity`. Returns at most 1 row. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM imported_cell_entry_deletion_receipt WHERE entry_identity = :identity LIMIT 1",
	)
	abstract suspend fun entryDeletionReceipt(
		identity: String,
	): ImportedCellEntryDeletionReceiptEntity?

	/**
	 * Reads `imported_cell_deleted_identity` rows. Query conditions include `entry_identity =
	 * :identity`. Orders by `protected_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_cell_deleted_identity WHERE entry_identity = :identity " +
			"ORDER BY protected_identity LIMIT :limit",
	)
	abstract suspend fun deletedIdentitiesForEntry(
		identity: String,
		limit: Int,
	): List<ImportedCellDeletedIdentityEntity>

	/**
	 * Reads `imported_cell_entry_deletion_receipt` rows. Query conditions include `entry_identity IN
	 * (:identities)`. Orders by `entry_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_cell_entry_deletion_receipt WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity LIMIT :limit",
	)
	abstract suspend fun entryDeletionReceiptOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellEntryDeletionReceiptEntity>

	/**
	 * Reads `imported_cell_deleted_identity` rows. Query conditions include `protected_identity IN
	 * (:identities)`, `entry_identity IN (:identities)`, `run_identity IN (:identities)`,
	 * `aggregate_owner_identity IN (:identities)`. Orders by `protected_identity`. Returns at most
	 * `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_cell_deleted_identity WHERE protected_identity IN (:identities) " +
			"OR entry_identity IN (:identities) OR run_identity IN (:identities) " +
			"OR aggregate_owner_identity IN (:identities) " +
			"ORDER BY protected_identity LIMIT :limit",
	)
	abstract suspend fun deletedIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellDeletedIdentityEntity>

	/**
	 * Reads `imported_cell_entry_revision` rows. Query conditions include `entry.identity =
	 * :identity`. Returns at most 1 row. Returns null when no row matches.
	 */
	@Query(
		"SELECT entry.identity, entry.import_revision, entry.content_checksum, " +
			"entry.start_time_ms, entry.end_time_ms, entry.received_at_ms " +
			"FROM imported_cell_entry_revision AS entry " +
			"WHERE entry.identity = :identity AND entry.import_revision = (" +
			"SELECT MAX(candidate.import_revision) FROM imported_cell_entry_revision AS candidate " +
			"WHERE candidate.identity = entry.identity) LIMIT 1",
	)
	abstract suspend fun latestHistoryCandidate(identity: String): ImportedCellHistoryCandidate?

	/**
	 * Reads `imported_cell_entry_revision` rows. Query conditions include `entry.start_time_ms <
	 * :beforeStartTimeMs`, `entry.start_time_ms = :beforeStartTimeMs`. Orders by `entry.start_time_ms
	 * DESC, entry.identity DESC`. Returns at most `limit` rows. Timestamp bounds use UTC
	 * milliseconds.
	 */
	@Query(
		"SELECT entry.identity, entry.import_revision, entry.content_checksum, " +
			"entry.start_time_ms, entry.end_time_ms, entry.received_at_ms " +
			"FROM imported_cell_entry_revision AS entry " +
			"WHERE entry.import_revision = (" +
			"SELECT MAX(candidate.import_revision) FROM imported_cell_entry_revision AS candidate " +
			"WHERE candidate.identity = entry.identity) " +
			"AND (:beforeStartTimeMs IS NULL OR entry.start_time_ms < :beforeStartTimeMs OR " +
			"(entry.start_time_ms = :beforeStartTimeMs AND " +
			"entry.identity < COALESCE(:beforeIdentity, ''))) " +
			"ORDER BY entry.start_time_ms DESC, entry.identity DESC LIMIT :limit",
	)
	abstract suspend fun recentHistoryCandidatePage(
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
	): List<ImportedCellHistoryCandidate>

	/**
	 * Reads row and storage-size counters for the selected dependencies before loading payloads.
	 * Query conditions include `identity IN (:identities)`, `entry_identity IN (:identities)`.
	 */
	@Query(
		"""
		SELECT
		  (SELECT COUNT(*) FROM imported_cell_entry_revision
		   WHERE identity IN (:identities)) AS revision_rows,
		  (SELECT COUNT(*) FROM imported_cell_receipt
		   WHERE entry_identity IN (:identities)) AS receipt_rows,
		  (SELECT COUNT(*) FROM imported_cell_run
		   WHERE entry_identity IN (:identities)) AS run_rows,
		  (SELECT COUNT(*) FROM imported_cell_observation
		   WHERE entry_identity IN (:identities)) AS observation_rows,
		  (SELECT COUNT(*) FROM imported_cell_entry_deletion
		   WHERE entry_identity IN (:identities)) AS entry_deletion_rows,
		  (SELECT COUNT(*) FROM imported_cell_deletion_generation
		   WHERE entry_identity IN (:identities)) AS run_deletion_rows,
		  COALESCE((
		    SELECT SUM(
		      length(CAST(identity AS BLOB)) +
		      length(CAST(content_checksum AS BLOB)) +
		      length(CAST(source_format AS BLOB)) +
		      length(CAST(session_mode AS BLOB)) +
		      length(CAST(subscription_grouping AS BLOB)) +
		      length(CAST(import_job_id AS BLOB)) +
		      length(CAST(import_entry_key AS BLOB)) +
		      length(CAST(import_source_name AS BLOB))
		    )
		    FROM imported_cell_entry_revision
		    WHERE identity IN (:identities)
		  ), 0) +
		  COALESCE((
		    SELECT SUM(
		      length(CAST(import_job_id AS BLOB)) +
		      length(CAST(import_entry_key AS BLOB)) +
		      length(CAST(import_source_name AS BLOB)) +
		      length(CAST(entry_identity AS BLOB)) +
		      length(CAST(entry_content_checksum AS BLOB))
		    )
		    FROM imported_cell_receipt
		    WHERE entry_identity IN (:identities)
		  ), 0) +
		  COALESCE((
		    SELECT SUM(
		      length(CAST(entry_identity AS BLOB)) +
		      length(CAST(identity AS BLOB)) +
		      length(CAST(deletion_scope_digest AS BLOB)) +
		      length(CAST(content_checksum AS BLOB)) +
		      length(CAST(capture_coverage AS BLOB)) +
		      length(CAST(availability AS BLOB)) +
		      length(CAST(acquisition_completeness AS BLOB)) +
		      length(CAST(subscription_grouping AS BLOB))
		    )
		    FROM imported_cell_run
		    WHERE entry_identity IN (:identities)
		  ), 0) +
		  COALESCE((
		    SELECT SUM(
		      length(CAST(entry_identity AS BLOB)) +
		      length(CAST(run_identity AS BLOB)) +
		      length(CAST(identity AS BLOB)) +
		      length(CAST(COALESCE(aggregate_owner_identity, '') AS BLOB)) +
		      length(CAST(content_checksum AS BLOB)) +
		      length(CAST(stored_zone_id AS BLOB)) +
		      length(CAST(child_completeness AS BLOB)) +
		      length(CAST(subscription_grouping AS BLOB))
		    )
		    FROM imported_cell_observation
		    WHERE entry_identity IN (:identities)
		  ), 0) +
		  COALESCE((
		    SELECT SUM(
		      length(CAST(entry_identity AS BLOB)) +
		      length(CAST(effect_checksum AS BLOB))
		    )
		    FROM imported_cell_entry_deletion
		    WHERE entry_identity IN (:identities)
		  ), 0) +
		  COALESCE((
		    SELECT SUM(
		      length(CAST(run_identity AS BLOB)) +
		      length(CAST(entry_identity AS BLOB)) +
		      length(CAST(deletion_scope_digest AS BLOB)) +
		      length(CAST(effect_checksum AS BLOB))
		    )
		    FROM imported_cell_deletion_generation
		    WHERE entry_identity IN (:identities)
		  ), 0) AS text_bytes
		""",
	)
	abstract suspend fun historyPreflight(
		identities: List<String>,
	): ImportedCellHistoryPreflight

	/**
	 * Reports whether a row exists within the query scope. Query conditions include
	 * `entry.start_time_ms < :beforeStartTimeMs`, `entry.start_time_ms = :beforeStartTimeMs`.
	 * Timestamp bounds use UTC milliseconds.
	 */
	@Query(
		"SELECT EXISTS(SELECT 1 FROM imported_cell_entry_revision AS entry " +
			"WHERE entry.import_revision = (" +
			"SELECT MAX(candidate.import_revision) FROM imported_cell_entry_revision AS candidate " +
			"WHERE candidate.identity = entry.identity) " +
			"AND (:beforeStartTimeMs IS NULL OR entry.start_time_ms < :beforeStartTimeMs OR " +
			"(entry.start_time_ms = :beforeStartTimeMs AND " +
			"entry.identity < COALESCE(:beforeIdentity, ''))) LIMIT 1)",
	)
	abstract suspend fun hasRecentHistoryCandidate(
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
	): Boolean

	/**
	 * Reads `imported_cell_entry_revision` rows. Query conditions include `entry.start_time_ms <
	 * :toExclusiveMs`, `entry.end_time_ms > :fromInclusiveMs`, `entry.start_time_ms <
	 * :beforeStartTimeMs`, `entry.start_time_ms = :beforeStartTimeMs`. Orders by `entry.start_time_ms
	 * DESC, entry.identity DESC`. Returns at most `limit` rows. Timestamp bounds use UTC
	 * milliseconds.
	 */
	@Query(
		"""
		WITH latest_revision AS (
		  SELECT identity, MAX(import_revision) AS import_revision
		  FROM imported_cell_entry_revision
		  GROUP BY identity
		)
		SELECT entry.identity,
		       entry.import_revision,
		       entry.content_checksum,
		       entry.start_time_ms,
		       entry.end_time_ms,
		       entry.received_at_ms
		FROM imported_cell_entry_revision AS entry
		INNER JOIN latest_revision AS latest
		  ON latest.identity = entry.identity
		 AND latest.import_revision = entry.import_revision
		WHERE entry.start_time_ms < :toExclusiveMs
		  AND entry.end_time_ms > :fromInclusiveMs
		  AND (
		    :beforeStartTimeMs IS NULL
		    OR entry.start_time_ms < :beforeStartTimeMs
		    OR (
		      entry.start_time_ms = :beforeStartTimeMs
		      AND entry.identity < COALESCE(:beforeIdentity, '')
		    )
		  )
		ORDER BY entry.start_time_ms DESC, entry.identity DESC
		LIMIT :limit
		""",
	)
	abstract suspend fun historyCandidatePageInWallRange(
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
	): List<ImportedCellHistoryCandidate>

	/** Source-specific export discovery; original headers remain the stable paging key. */
	@Query(
		"""
		WITH latest_revision AS (
		  SELECT identity, MAX(import_revision) AS import_revision FROM imported_cell_entry_revision GROUP BY identity
		)
		SELECT entry.identity, entry.import_revision, entry.content_checksum, entry.start_time_ms,
		       entry.end_time_ms, entry.received_at_ms
		FROM imported_cell_entry_revision AS entry INNER JOIN latest_revision AS latest
		  ON latest.identity = entry.identity AND latest.import_revision = entry.import_revision
		WHERE (
		  (:fromInclusiveMs = 0 AND :toExclusiveMs = 9223372036854775807)
		  OR typeof(entry.start_time_ms) != 'integer' OR typeof(entry.end_time_ms) != 'integer'
		  OR entry.start_time_ms < 0 OR entry.end_time_ms < entry.start_time_ms
		  OR EXISTS (
		    SELECT 1 FROM imported_cell_observation AS observation
		    WHERE observation.entry_identity = entry.identity AND observation.entry_import_revision = entry.import_revision
		      AND CASE WHEN typeof(observation.coverage_start_time_ms) != 'integer'
		        OR typeof(observation.observed_time_ms) != 'integer' OR typeof(observation.latest_possible_time_ms) != 'integer'
		        OR typeof(observation.wall_time_uncertainty_ms) != 'integer' OR observation.coverage_start_time_ms < 0
		        OR observation.wall_time_uncertainty_ms < 0 OR observation.observed_time_ms < observation.wall_time_uncertainty_ms
		        OR observation.observed_time_ms > 9223372036854775807 - observation.wall_time_uncertainty_ms
		        OR observation.coverage_start_time_ms > observation.observed_time_ms - observation.wall_time_uncertainty_ms
		        OR observation.latest_possible_time_ms != observation.observed_time_ms + observation.wall_time_uncertainty_ms
		        THEN 1 ELSE observation.coverage_start_time_ms < :toExclusiveMs
		          AND observation.latest_possible_time_ms >= :fromInclusiveMs END
		  )
		  OR EXISTS (
		    SELECT 1 FROM imported_cell_run AS run
		    WHERE run.entry_identity = entry.identity AND run.entry_import_revision = entry.import_revision
		      AND CASE WHEN typeof(run.start_time_ms) != 'integer' OR typeof(run.end_time_ms) != 'integer'
		        OR run.start_time_ms < 0 OR run.end_time_ms < run.start_time_ms THEN 1
		        ELSE run.capture_coverage != 'NOT_CAPTURED' AND run.retention_loss != 0
		          AND run.start_time_ms < :toExclusiveMs AND
		            (run.end_time_ms > :fromInclusiveMs OR (run.start_time_ms = run.end_time_ms AND run.start_time_ms >= :fromInclusiveMs)) END
		  )
		) AND (:beforeStartTimeMs IS NULL OR entry.start_time_ms < :beforeStartTimeMs
		  OR (entry.start_time_ms = :beforeStartTimeMs AND entry.identity < COALESCE(:beforeIdentity, '')))
		ORDER BY entry.start_time_ms DESC, entry.identity DESC LIMIT :limit
		""",
	)
	abstract suspend fun exportCandidatePageInWallRange(
		fromInclusiveMs: Long, toExclusiveMs: Long, limit: Int,
		beforeStartTimeMs: Long?, beforeIdentity: String?,
	): List<ImportedCellHistoryCandidate>

	/**
	 * Reads `imported_cell_entry_revision` rows. Query conditions include
	 * `observation.latest_possible_time_ms >= :broadFromInclusiveMs`,
	 * `observation.coverage_start_time_ms < :broadToExclusiveMs`, `entry.start_time_ms <
	 * :beforeStartTimeMs`, `entry.start_time_ms = :beforeStartTimeMs`. Orders by `entry.start_time_ms
	 * DESC, entry.identity DESC`. Returns at most `limit` rows. Timestamp bounds use UTC
	 * milliseconds.
	 */
	@Query(
		"""
		WITH latest_revision AS (
		  SELECT identity, MAX(import_revision) AS import_revision
		  FROM imported_cell_entry_revision
		  GROUP BY identity
		)
		SELECT entry.identity,
		       entry.import_revision,
		       entry.content_checksum,
		       entry.start_time_ms,
		       entry.end_time_ms,
		       entry.received_at_ms
		FROM imported_cell_entry_revision AS entry
		INNER JOIN latest_revision AS latest
		  ON latest.identity = entry.identity
		 AND latest.import_revision = entry.import_revision
		WHERE EXISTS (
		  SELECT 1
		  FROM imported_cell_observation AS observation
		  WHERE observation.entry_identity = entry.identity
		    AND observation.entry_import_revision = entry.import_revision
		    AND observation.latest_possible_time_ms >= :broadFromInclusiveMs
		    AND observation.coverage_start_time_ms < :broadToExclusiveMs
		)
		  AND (
		    :beforeStartTimeMs IS NULL
		    OR entry.start_time_ms < :beforeStartTimeMs
		    OR (
		      entry.start_time_ms = :beforeStartTimeMs
		      AND entry.identity < COALESCE(:beforeIdentity, '')
		    )
		  )
		ORDER BY entry.start_time_ms DESC, entry.identity DESC
		LIMIT :limit
		""",
	)
	abstract suspend fun historyCandidatePageForStructuralDays(
		broadFromInclusiveMs: Long,
		broadToExclusiveMs: Long,
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
	): List<ImportedCellHistoryCandidate>

	/**
	 * Reads `imported_cell_entry_revision` rows. Query conditions include `identity IN
	 * (:identities)`. Orders by `identity, import_revision`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_cell_entry_revision WHERE identity IN (:identities) " +
			"ORDER BY identity, import_revision LIMIT :limit",
	)
	abstract suspend fun entryRevisionsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellEntryRevisionEntity>

	/**
	 * Reads `imported_cell_receipt` rows. Query conditions include `entry_identity IN (:identities)`.
	 * Orders by `entry_identity, entry_import_revision, import_job_id, import_entry_key`. Returns at
	 * most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_cell_receipt WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, import_job_id, import_entry_key LIMIT :limit",
	)
	abstract suspend fun receiptsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellReceiptEntity>

	/**
	 * Reads `imported_cell_run` rows. Query conditions include `entry_identity IN (:identities)`.
	 * Orders by `entry_identity, entry_import_revision, start_time_ms, end_time_ms, identity`.
	 * Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_cell_run WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, start_time_ms, end_time_ms, identity LIMIT :limit",
	)
	abstract suspend fun runsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellRunEntity>

	/**
	 * Reads `imported_cell_observation` rows. Query conditions include `entry_identity IN
	 * (:identities)`. Orders by `entry_identity, entry_import_revision, run_identity,
	 * coverage_start_time_ms, observed_time_ms, identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_cell_observation WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, run_identity, coverage_start_time_ms, " +
			"observed_time_ms, identity LIMIT :limit",
	)
	abstract suspend fun observationsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellObservationEntity>

	/**
	 * Reads `imported_cell_entry_deletion` rows. Query conditions include `entry_identity IN
	 * (:identities)`. Orders by `entry_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_cell_entry_deletion WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity LIMIT :limit",
	)
	abstract suspend fun entryDeletionsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellEntryDeletionEntity>

	/**
	 * Reads `imported_cell_deletion_generation` rows. Query conditions include `entry_identity IN
	 * (:identities)`. Orders by `entry_identity, run_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_cell_deletion_generation WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, run_identity LIMIT :limit",
	)
	abstract suspend fun deletionGenerationsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellDeletionGenerationEntity>

	/**
	 * Reads `imported_cell_deletion_generation` rows. Query conditions include `run_identity IN
	 * (:identities)`, `entry_identity IN (:identities)`, `deletion_scope_digest IN (:identities)`.
	 * Orders by `run_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_cell_deletion_generation WHERE run_identity IN (:identities) " +
			"OR entry_identity IN (:identities) OR deletion_scope_digest IN (:identities) " +
			"ORDER BY run_identity LIMIT :limit",
	)
	abstract suspend fun deletionGenerationOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellDeletionGenerationEntity>

	/**
	 * Reads distinct `identity` values from `imported_cell_entry_revision`. Query conditions include
	 * `identity IN (:identities)`. Orders by `identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT DISTINCT identity FROM imported_cell_entry_revision " +
			"WHERE identity IN (:identities) ORDER BY identity LIMIT :limit",
	)
	abstract suspend fun existingEntryIdentities(
		identities: List<String>,
		limit: Int,
	): List<String>

	/**
	 * Reads `imported_cell_run` rows. Query conditions include `identity IN (:identities)`. Orders by
	 * `identity, entry_identity, deletion_scope_digest`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT DISTINCT identity, entry_identity, deletion_scope_digest FROM imported_cell_run " +
			"WHERE identity IN (:identities) ORDER BY identity, entry_identity, deletion_scope_digest " +
			"LIMIT :limit",
	)
	abstract suspend fun existingRunIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellRunIdentityOwner>

	/**
	 * Reads `imported_cell_run` rows. Query conditions include `deletion_scope_digest IN (:digests)`.
	 * Orders by `deletion_scope_digest, identity, entry_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT DISTINCT deletion_scope_digest, identity, entry_identity FROM imported_cell_run " +
			"WHERE deletion_scope_digest IN (:digests) ORDER BY deletion_scope_digest, identity, " +
			"entry_identity LIMIT :limit",
	)
	abstract suspend fun existingRunScopeOwners(
		digests: List<String>,
		limit: Int,
	): List<ImportedCellRunScopeOwner>

	/**
	 * Reads `imported_cell_observation` rows. Query conditions include `identity IN (:identities)`,
	 * `aggregate_owner_identity IN (:identities)`. Orders by `identity, entry_identity, run_identity,
	 * aggregate_owner_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT DISTINCT identity, entry_identity, run_identity, aggregate_owner_identity " +
			"FROM imported_cell_observation " +
			"WHERE identity IN (:identities) OR aggregate_owner_identity IN (:identities) " +
			"ORDER BY identity, entry_identity, run_identity, aggregate_owner_identity LIMIT :limit",
	)
	abstract suspend fun existingObservationIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellObservationIdentityOwner>

	/**
	 * Reads `imported_cell_entry_deletion` rows. Query conditions include `entry_identity IN
	 * (:identities)`. Orders by `entry_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_cell_entry_deletion WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity LIMIT :limit",
	)
	abstract suspend fun entryDeletionOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellEntryDeletionEntity>

	/** Every current local logical identity, including zero-observation Cell candidates. */
	@Query(
		"SELECT logical_tracking_id FROM logical_tracking_session ORDER BY logical_tracking_id LIMIT :limit",
	)
	abstract suspend fun liveLogicalTrackingIds(limit: Int): List<String>

	/** Every current local physical-run owner; imported identities must not claim another tuple. */
	@Query(
		"SELECT service_run_id, logical_tracking_id FROM source_service_run " +
			"ORDER BY service_run_id LIMIT :limit",
	)
	abstract suspend fun liveServiceRunOwners(limit: Int): List<ImportedCellLiveRunOwner>

	/** Distinct Cell fact ownership, independently of whether a current cursor still names it. */
	@Query(
		"SELECT DISTINCT logical_fact_id, logical_tracking_id, service_run_id, " +
			"aggregate_owner_logical_fact_id, aggregate_owner_semantic_revision " +
			"FROM cell_captured_fact_revision ORDER BY logical_fact_id, logical_tracking_id, " +
			"service_run_id, aggregate_owner_logical_fact_id, aggregate_owner_semantic_revision " +
			"LIMIT :limit",
	)
	abstract suspend fun liveCellFactOwners(limit: Int): List<ImportedCellLiveFactOwner>

	/** Independent zero/gap run owner relation; source filtering happens before the bound. */
	@Query(
		"SELECT DISTINCT logical_tracking_id, service_run_id FROM source_session_completeness " +
			"WHERE source_kind = :sourceKind ORDER BY logical_tracking_id, service_run_id LIMIT :limit",
	)
	abstract suspend fun liveCellCompletenessOwners(
		sourceKind: Int,
		limit: Int,
	): List<ImportedCellLiveCompletenessOwner>

	/** Complete bounded local Cell deletion generations for scope-digest derivation in Kotlin. */
	@Query(
		"SELECT * FROM cell_capture_deletion_generation ORDER BY logical_tracking_id, service_run_id " +
			"LIMIT :limit",
	)
	abstract suspend fun liveCellDeletionGenerations(
		limit: Int,
	): List<CellCaptureDeletionGenerationEntity>

	/** Any source fence owning a candidate opaque identity or Cell deletion-scope digest. */
	@Query(
		"SELECT * FROM source_deletion_fence " +
			"WHERE scope_identity_digest IN (:digests) ORDER BY source_kind, purpose, scope_kind, " +
			"scope_identity_digest LIMIT :limit",
	)
	abstract suspend fun sourceFenceOwners(
		digests: List<String>,
		limit: Int,
	): List<SourceDeletionFenceEntity>

	/**
	 * One typed collision probe across every imported/source owner namespace. Payload hierarchy
	 * rows are already loaded separately; this UNION detects incompatible cross-kind ownership.
	 */
	@Query(
		"""
		SELECT 'ENTRY' AS owner_kind, identity AS protected_identity,
		       identity AS entry_identity, NULL AS run_identity,
		       NULL AS deletion_scope_digest, NULL AS aggregate_owner_identity,
		       collected_data_epoch, NULL AS source_kind, NULL AS purpose,
		       NULL AS scope_kind, NULL AS generation
		FROM imported_cell_entry_revision
		WHERE identity IN (:identities)
		UNION ALL
		SELECT 'RUN' AS owner_kind, identity AS protected_identity,
		       entry_identity, identity AS run_identity,
		       deletion_scope_digest, NULL AS aggregate_owner_identity,
		       collected_data_epoch, NULL AS source_kind, NULL AS purpose,
		       NULL AS scope_kind, scope_deletion_generation AS generation
		FROM imported_cell_run
		WHERE identity IN (:identities)
		UNION ALL
		SELECT 'SCOPE' AS owner_kind, deletion_scope_digest AS protected_identity,
		       entry_identity, identity AS run_identity,
		       deletion_scope_digest, NULL AS aggregate_owner_identity,
		       collected_data_epoch, NULL AS source_kind, NULL AS purpose,
		       NULL AS scope_kind, scope_deletion_generation AS generation
		FROM imported_cell_run
		WHERE deletion_scope_digest IN (:identities)
		UNION ALL
		SELECT 'OBSERVATION' AS owner_kind, identity AS protected_identity,
		       entry_identity, run_identity, NULL AS deletion_scope_digest,
		       aggregate_owner_identity, NULL AS collected_data_epoch,
		       NULL AS source_kind, NULL AS purpose, NULL AS scope_kind,
		       NULL AS generation
		FROM imported_cell_observation
		WHERE identity IN (:identities) OR aggregate_owner_identity IN (:identities)
		UNION ALL
		SELECT 'ENTRY_DELETION' AS owner_kind, entry_identity AS protected_identity,
		       entry_identity, NULL AS run_identity, NULL AS deletion_scope_digest,
		       NULL AS aggregate_owner_identity, collected_data_epoch,
		       NULL AS source_kind, NULL AS purpose, NULL AS scope_kind,
		       NULL AS generation
		FROM imported_cell_entry_deletion
		WHERE entry_identity IN (:identities)
		UNION ALL
		SELECT 'RUN_DELETION' AS owner_kind, run_identity AS protected_identity,
		       entry_identity, run_identity, deletion_scope_digest,
		       NULL AS aggregate_owner_identity, collected_data_epoch,
		       NULL AS source_kind, NULL AS purpose, NULL AS scope_kind,
		       generation
		FROM imported_cell_deletion_generation
		WHERE run_identity IN (:identities) OR entry_identity IN (:identities)
		   OR deletion_scope_digest IN (:identities)
		UNION ALL
		SELECT 'SOURCE_FENCE' AS owner_kind, scope_identity_digest AS protected_identity,
		       NULL AS entry_identity, NULL AS run_identity,
		       scope_identity_digest AS deletion_scope_digest,
		       NULL AS aggregate_owner_identity, collected_data_epoch,
		       source_kind, purpose, scope_kind, fence_generation AS generation
		FROM source_deletion_fence
		WHERE scope_identity_digest IN (:identities)
		UNION ALL
		SELECT 'DELETION_RECEIPT' AS owner_kind, entry_identity AS protected_identity,
		       entry_identity, NULL AS run_identity, NULL AS deletion_scope_digest,
		       NULL AS aggregate_owner_identity, collected_data_epoch,
		       NULL AS source_kind, NULL AS purpose, NULL AS scope_kind,
		       NULL AS generation
		FROM imported_cell_entry_deletion_receipt
		WHERE entry_identity IN (:identities)
		UNION ALL
		SELECT 'DELETED_IDENTITY' AS owner_kind, protected_identity,
		       entry_identity, run_identity,
		       CASE WHEN identity_kind = 'DELETION_SCOPE' THEN protected_identity ELSE NULL END,
		       aggregate_owner_identity, NULL AS collected_data_epoch,
		       NULL AS source_kind, NULL AS purpose, NULL AS scope_kind,
		       NULL AS generation
		FROM imported_cell_deleted_identity
		WHERE protected_identity IN (:identities) OR entry_identity IN (:identities)
		   OR run_identity IN (:identities) OR aggregate_owner_identity IN (:identities)
		ORDER BY owner_kind, protected_identity, entry_identity, run_identity
		LIMIT :limit
		""",
	)
	abstract suspend fun opaqueOwnerProbe(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellOpaqueOwnerRow>

	/**
	 * Deletes `imported_cell_entry_revision` rows matching the supplied scope. Query conditions
	 * include `identity = :identity`. Returns the affected row count.
	 */
	@Query("DELETE FROM imported_cell_entry_revision WHERE identity = :identity")
	abstract suspend fun deleteEntryRevisions(identity: String): Int

	/**
	 * Deletes `imported_cell_receipt` rows.
	 */
	@Query("DELETE FROM imported_cell_receipt")
	abstract fun deleteAllReceipts()

	/**
	 * Deletes `imported_cell_entry_revision` rows.
	 */
	@Query("DELETE FROM imported_cell_entry_revision")
	abstract fun deleteAllEntries()

	/**
	 * Deletes `imported_cell_entry_deletion` rows.
	 */
	@Query("DELETE FROM imported_cell_entry_deletion")
	abstract fun deleteAllEntryDeletions()

	/**
	 * Deletes `imported_cell_deletion_generation` rows.
	 */
	@Query("DELETE FROM imported_cell_deletion_generation")
	abstract fun deleteAllDeletionGenerations()

	/**
	 * Reads `imported_cell_entry_revision` rows. Query conditions include `identity = :identity`.
	 * Orders by `import_revision`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_cell_entry_revision WHERE identity = :identity " +
			"ORDER BY import_revision LIMIT :limit",
	)
	abstract fun entryRevisionsForFullClear(
		identity: String,
		limit: Int,
	): List<ImportedCellEntryRevisionEntity>

	/**
	 * Reads `imported_cell_receipt` rows. Query conditions include `entry_identity = :identity`.
	 * Orders by `entry_import_revision, import_job_id, import_entry_key`. Returns at most `limit`
	 * rows.
	 */
	@Query(
		"SELECT * FROM imported_cell_receipt WHERE entry_identity = :identity " +
			"ORDER BY entry_import_revision, import_job_id, import_entry_key LIMIT :limit",
	)
	abstract fun receiptsForFullClear(
		identity: String,
		limit: Int,
	): List<ImportedCellReceiptEntity>

	/**
	 * Reads `imported_cell_run` rows. Query conditions include `entry_identity = :identity`. Orders
	 * by `entry_import_revision, start_time_ms, end_time_ms, identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_cell_run WHERE entry_identity = :identity " +
			"ORDER BY entry_import_revision, start_time_ms, end_time_ms, identity LIMIT :limit",
	)
	abstract fun runsForFullClear(
		identity: String,
		limit: Int,
	): List<ImportedCellRunEntity>

	/**
	 * Reads `imported_cell_observation` rows. Query conditions include `entry_identity = :identity`.
	 * Orders by `entry_import_revision, run_identity, coverage_start_time_ms, observed_time_ms,
	 * identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_cell_observation WHERE entry_identity = :identity " +
			"ORDER BY entry_import_revision, run_identity, coverage_start_time_ms, observed_time_ms, " +
			"identity LIMIT :limit",
	)
	abstract fun observationsForFullClear(
		identity: String,
		limit: Int,
	): List<ImportedCellObservationEntity>

	/**
	 * Inserts [ImportedCellEntryDeletionReceiptEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract fun insertEntryDeletionReceiptForFullClear(
		receipt: ImportedCellEntryDeletionReceiptEntity,
	)

	/**
	 * Inserts [ImportedCellDeletedIdentityEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract fun insertDeletedIdentitiesForFullClear(
		identities: List<ImportedCellDeletedIdentityEntity>,
	)

	/**
	 * Limits for bounded Cell admission, lineage and maintenance reads; callers detect overflow
	 * before loading more dependencies.
	 */
	companion object {
		const val MAX_REVISIONS_PER_ENTRY = 16
		const val MAX_RECEIPTS_PER_ENTRY = 256
		const val MAX_RUNS_PER_REVISION = 64
		const val MAX_OBSERVATIONS_PER_REVISION = 4_096
		const val MAX_RUN_ROWS_PER_LINEAGE = MAX_REVISIONS_PER_ENTRY * MAX_RUNS_PER_REVISION
		const val MAX_OBSERVATION_ROWS_PER_LINEAGE =
			MAX_REVISIONS_PER_ENTRY * MAX_OBSERVATIONS_PER_REVISION
		const val MAX_LIVE_OWNER_ROWS = 4_096
		const val MAX_IDENTITY_QUERY_CHUNK = 256
		const val MAX_HISTORY_ENTRY_CANDIDATES = 100
		const val HISTORY_EVALUATION_BATCH_SIZE = 4
	}
}

/**
 * Saved Cell import identity, revision, checksum and UTC bounds selected before retained payload
 * qualification.
 */
data class ImportedCellHistoryCandidate(
	val identity: String,
	@ColumnInfo(name = "import_revision") val importRevision: Long,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
)

/**
 * Row and text-byte totals used to bound the complete retained Cell import lineage.
 */
data class ImportedCellHistoryPreflight(
	@ColumnInfo(name = "revision_rows") val revisionRows: Long,
	@ColumnInfo(name = "receipt_rows") val receiptRows: Long,
	@ColumnInfo(name = "run_rows") val runRows: Long,
	@ColumnInfo(name = "observation_rows") val observationRows: Long,
	@ColumnInfo(name = "entry_deletion_rows") val entryDeletionRows: Long,
	@ColumnInfo(name = "run_deletion_rows") val runDeletionRows: Long,
	@ColumnInfo(name = "text_bytes") val textBytes: Long,
)

/**
 * Associates an imported Cell run identity with its entry and deletion scope.
 */
data class ImportedCellRunIdentityOwner(
	val identity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String,
)

/**
 * Associates a Cell deletion-scope digest with its imported run and entry.
 */
data class ImportedCellRunScopeOwner(
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String,
	val identity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
)

/**
 * Associates an imported Cell observation identity with its entry, run and optional aggregate
 * owner.
 */
data class ImportedCellObservationIdentityOwner(
	val identity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "run_identity") val runIdentity: String,
	@ColumnInfo(name = "aggregate_owner_identity") val aggregateOwnerIdentity: String?,
)

/**
 * Original local Cell run and logical recording referenced by retained ownership rows.
 */
data class ImportedCellLiveRunOwner(
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
)

/**
 * Original local Cell fact with its recording and optional aggregate revision identity.
 */
data class ImportedCellLiveFactOwner(
	@ColumnInfo(name = "logical_fact_id") val logicalFactId: String,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "aggregate_owner_logical_fact_id") val aggregateOwnerLogicalFactId: String?,
	@ColumnInfo(name = "aggregate_owner_semantic_revision") val aggregateOwnerSemanticRevision: Long?,
)

/**
 * Original Cell recording/run pair used to locate its saved completeness ownership.
 */
data class ImportedCellLiveCompletenessOwner(
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
)

/**
 * Payload-free Cell reverse ownership result; optional fields depend on whether the owner is a
 * run, observation or deletion fence.
 */
data class ImportedCellOpaqueOwnerRow(
	@ColumnInfo(name = "owner_kind") val ownerKind: String,
	@ColumnInfo(name = "protected_identity") val protectedIdentity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String?,
	@ColumnInfo(name = "run_identity") val runIdentity: String?,
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String?,
	@ColumnInfo(name = "aggregate_owner_identity") val aggregateOwnerIdentity: String?,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long?,
	@ColumnInfo(name = "source_kind") val sourceKind: Int?,
	val purpose: String?,
	@ColumnInfo(name = "scope_kind") val scopeKind: String?,
	val generation: Long?,
) {
	/**
	 * Stored ownership-kind codes used to classify Cell identity and deletion references.
	 */
	companion object {
		const val ENTRY = "ENTRY"
		const val RUN = "RUN"
		const val SCOPE = "SCOPE"
		const val OBSERVATION = "OBSERVATION"
		const val ENTRY_DELETION = "ENTRY_DELETION"
		const val RUN_DELETION = "RUN_DELETION"
		const val SOURCE_FENCE = "SOURCE_FENCE"
		const val DELETION_RECEIPT = "DELETION_RECEIPT"
		const val DELETED_IDENTITY = "DELETED_IDENTITY"
	}
}
