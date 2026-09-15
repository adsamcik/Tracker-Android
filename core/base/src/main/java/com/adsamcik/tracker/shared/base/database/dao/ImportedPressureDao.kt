package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureIdentityFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureSourceEraseEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureSourceEraseWitnessEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureWindowEntity

/**
 * Pressure-specific portable-origin storage primitives. This DAO does not grant admission; the
 * source-local writer validates the complete lineage, receipt, epoch, deletion generation, and
 * checksums in one transaction before using these inserts.
 */
@Dao
abstract class ImportedPressureDao {
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertEntryRevision(entry: ImportedPressureEntryRevisionEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertRun(run: ImportedPressureRunEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertWindow(window: ImportedPressureWindowEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertReceipt(receipt: ImportedPressureReceiptEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	protected abstract suspend fun insertDeletionGenerationRow(
		generation: ImportedPressureDeletionGenerationEntity,
	)

	/** A previously absent privacy fence may only be created at its exact first generation. */
	suspend fun insertDeletionGeneration(generation: ImportedPressureDeletionGenerationEntity) {
		require(generation.generation == 1L)
		insertDeletionGenerationRow(generation)
	}

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertEntryDeletion(deletion: ImportedPressureEntryDeletionEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertRetentionReceipt(receipt: ImportedPressureRetentionReceiptEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertRetainedIdentities(values: List<ImportedPressureRetainedIdentityEntity>)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertSourceErase(value: ImportedPressureSourceEraseEntity)

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	abstract suspend fun insertIdentityFences(
		values: List<ImportedPressureIdentityFenceEntity>,
	): List<Long>

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertSourceEraseWitnesses(
		values: List<ImportedPressureSourceEraseWitnessEntity>,
	)

	@Query(
		"SELECT * FROM imported_pressure_entry_revision " +
			"WHERE identity = :identity AND import_revision = :revision",
	)
	abstract suspend fun entryRevision(
		identity: String,
		revision: Long,
	): ImportedPressureEntryRevisionEntity?

	@Query(
		"SELECT * FROM imported_pressure_entry_revision WHERE identity = :identity " +
			"ORDER BY import_revision DESC LIMIT 1",
	)
	abstract suspend fun latestEntryRevision(identity: String): ImportedPressureEntryRevisionEntity?

	/** One latest-revision seed per imported logical entry, ordered for a source-local product page. */
	@Query(
		"""
		WITH latest_revision AS (
		  SELECT identity, MAX(import_revision) AS import_revision
		  FROM imported_pressure_entry_revision
		  GROUP BY identity
		)
		SELECT entry.identity,
		       entry.import_revision,
		       entry.content_checksum,
		       entry.start_time_ms,
		       entry.end_time_ms,
		       entry.received_at_ms,
		       'LIVE' AS candidate_state
		FROM imported_pressure_entry_revision AS entry
		INNER JOIN latest_revision AS latest
		  ON latest.identity = entry.identity
		 AND latest.import_revision = entry.import_revision
		WHERE (
		  :beforeStartTimeMs IS NULL
		  OR entry.start_time_ms < :beforeStartTimeMs
		  OR (
		    entry.start_time_ms = :beforeStartTimeMs
		    AND entry.identity < COALESCE(:beforeIdentity, '')
		  )
		)
		UNION ALL
		SELECT retained.entry_identity,
		       retained.latest_import_revision,
		       retained.latest_content_checksum,
		       retained.start_time_ms,
		       retained.end_time_ms,
		       retained.received_at_ms,
		       'RETAINED' AS candidate_state
		FROM imported_pressure_retention_receipt AS retained
		WHERE (
		  :beforeStartTimeMs IS NULL
		  OR retained.start_time_ms < :beforeStartTimeMs
		  OR (
		    retained.start_time_ms = :beforeStartTimeMs
		    AND retained.entry_identity < COALESCE(:beforeIdentity, '')
		  )
		)
		ORDER BY start_time_ms DESC, identity DESC, candidate_state
		LIMIT :limit
		""",
	)
	abstract suspend fun recentHistoryCandidatePage(
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
	): List<ImportedPressureHistoryCandidate>

	/** Latest imported entries whose complete logical time range overlaps the requested range. */
	@Query(
		"""
		WITH latest_revision AS (
		  SELECT identity, MAX(import_revision) AS import_revision
		  FROM imported_pressure_entry_revision
		  GROUP BY identity
		)
		SELECT entry.identity,
		       entry.import_revision,
		       entry.content_checksum,
		       entry.start_time_ms,
		       entry.end_time_ms,
		       entry.received_at_ms,
		       'LIVE' AS candidate_state
		FROM imported_pressure_entry_revision AS entry
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
		UNION ALL
		SELECT retained.entry_identity,
		       retained.latest_import_revision,
		       retained.latest_content_checksum,
		       retained.start_time_ms,
		       retained.end_time_ms,
		       retained.received_at_ms,
		       'RETAINED' AS candidate_state
		FROM imported_pressure_retention_receipt AS retained
		WHERE retained.start_time_ms < :toExclusiveMs
		  AND retained.end_time_ms > :fromInclusiveMs
		  AND (
		    :beforeStartTimeMs IS NULL
		    OR retained.start_time_ms < :beforeStartTimeMs
		    OR (
		      retained.start_time_ms = :beforeStartTimeMs
		      AND retained.entry_identity < COALESCE(:beforeIdentity, '')
		    )
		  )
		ORDER BY start_time_ms DESC, identity DESC, candidate_state
		LIMIT :limit
		""",
	)
	abstract suspend fun historyCandidatePageInRange(
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
	): List<ImportedPressureHistoryCandidate>

	/** Header-only keyset used before one complete lineage is materialized for retention. */
	@Query(
		"""
		WITH latest_revision AS (
		  SELECT identity, MAX(import_revision) AS import_revision
		  FROM imported_pressure_entry_revision
		  GROUP BY identity
		)
		SELECT entry.identity,
		       entry.import_revision,
		       entry.content_checksum,
		       entry.start_time_ms,
		       entry.end_time_ms,
		       entry.received_at_ms,
		       'LIVE' AS candidate_state
		FROM imported_pressure_entry_revision AS entry
		INNER JOIN latest_revision AS latest
		  ON latest.identity = entry.identity
		 AND latest.import_revision = entry.import_revision
		WHERE :afterIdentity IS NULL OR entry.identity > :afterIdentity
		ORDER BY entry.identity
		LIMIT :limit
		""",
	)
	abstract suspend fun retentionCandidatePage(
		afterIdentity: String?,
		limit: Int,
	): List<ImportedPressureHistoryCandidate>

	/** Numeric owner cursor; no attacker-controlled identity text leaves SQLite before preflight. */
	@Query(
		"SELECT MIN(rowid) FROM imported_pressure_entry_revision GROUP BY identity " +
			"HAVING MIN(rowid) > :afterOwnerRowId ORDER BY MIN(rowid) LIMIT :limit",
	)
	abstract suspend fun liveOwnerRowIdPage(
		afterOwnerRowId: Long,
		limit: Int,
	): List<Long>

	@Query(
		"""
		WITH owner(identity) AS (
		  SELECT identity FROM imported_pressure_entry_revision WHERE rowid = :ownerRowId
		)
		SELECT
		  (SELECT COUNT(*) FROM imported_pressure_entry_revision
		    WHERE identity = (SELECT identity FROM owner)) AS header_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(identity AS BLOB)) + LENGTH(CAST(content_checksum AS BLOB)) +
		    LENGTH(CAST(source_format AS BLOB)) + LENGTH(CAST(import_job_id AS BLOB)) +
		    LENGTH(CAST(import_entry_key AS BLOB)) + LENGTH(CAST(import_source_name AS BLOB))
		  ), 0) FROM imported_pressure_entry_revision
		    WHERE identity = (SELECT identity FROM owner)) AS header_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_receipt
		    WHERE entry_identity = (SELECT identity FROM owner)) AS receipt_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(import_job_id AS BLOB)) + LENGTH(CAST(import_entry_key AS BLOB)) +
		    LENGTH(CAST(import_source_name AS BLOB)) + LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(entry_content_checksum AS BLOB))
		  ), 0) FROM imported_pressure_receipt
		    WHERE entry_identity = (SELECT identity FROM owner)) AS receipt_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_run
		    WHERE entry_identity = (SELECT identity FROM owner)) AS run_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(entry_identity AS BLOB)) + LENGTH(CAST(identity AS BLOB)) +
		    LENGTH(CAST(availability AS BLOB)) + LENGTH(CAST(coverage AS BLOB))
		  ), 0) FROM imported_pressure_run
		    WHERE entry_identity = (SELECT identity FROM owner)) AS run_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_window
		    WHERE entry_identity = (SELECT identity FROM owner)) AS window_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(entry_identity AS BLOB)) + LENGTH(CAST(run_identity AS BLOB)) +
		    LENGTH(CAST(identity AS BLOB)) + LENGTH(CAST(content_checksum AS BLOB)) +
		    LENGTH(CAST(sensor_accuracy AS BLOB)) + LENGTH(CAST(closure_kind AS BLOB)) +
		    LENGTH(CAST(qualification AS BLOB)) + LENGTH(CAST(stored_zone_id AS BLOB))
		  ), 0) FROM imported_pressure_window
		    WHERE entry_identity = (SELECT identity FROM owner)) AS window_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_identity_fence
		    WHERE entry_identity = (SELECT identity FROM owner)) AS identity_fence_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(protected_identity AS BLOB)) + LENGTH(CAST(identity_kind AS BLOB)) +
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    COALESCE(LENGTH(CAST(run_identity AS BLOB)), 0) +
		    LENGTH(CAST(fence_reason AS BLOB)) + LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_identity_fence
		    WHERE entry_identity = (SELECT identity FROM owner)) AS identity_fence_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_entry_deletion
		    WHERE entry_identity = (SELECT identity FROM owner)) AS entry_deletion_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(run_deletion_set_checksum AS BLOB)) +
		    LENGTH(CAST(identity_fence_set_checksum AS BLOB)) +
		    LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_entry_deletion
		    WHERE entry_identity = (SELECT identity FROM owner)) AS entry_deletion_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_deletion_generation
		    WHERE run_identity IN (
		      SELECT identity FROM imported_pressure_run
		      WHERE entry_identity = (SELECT identity FROM owner)
		    )) AS run_deletion_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(run_identity AS BLOB)) + LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_deletion_generation
		    WHERE run_identity IN (
		      SELECT identity FROM imported_pressure_run
		      WHERE entry_identity = (SELECT identity FROM owner)
		    )) AS run_deletion_text_bytes
		""",
	)
	abstract suspend fun liveOwnerFootprint(
		ownerRowId: Long,
	): ImportedPressureLineageFootprint

	@Query(
		"""
		WITH owner(identity) AS (
		  SELECT identity FROM imported_pressure_entry_revision WHERE rowid = :ownerRowId
		), latest_revision AS (
		  SELECT MAX(import_revision) AS import_revision
		  FROM imported_pressure_entry_revision
		  WHERE identity = (SELECT identity FROM owner)
		)
		SELECT entry.identity, entry.import_revision, entry.content_checksum,
		       entry.start_time_ms, entry.end_time_ms, entry.received_at_ms,
		       'LIVE' AS candidate_state
		FROM imported_pressure_entry_revision AS entry
		WHERE entry.identity = (SELECT identity FROM owner)
		  AND entry.import_revision = (SELECT import_revision FROM latest_revision)
		LIMIT 1
		""",
	)
	abstract suspend fun liveCandidateByOwnerRowId(
		ownerRowId: Long,
	): ImportedPressureHistoryCandidate?

	@Query(
		"""
		WITH owner(identity) AS (
		  SELECT identity FROM imported_pressure_entry_revision WHERE rowid = :ownerRowId
		)
		SELECT COUNT(*) FROM imported_pressure_entry_revision
		WHERE identity = (SELECT identity FROM owner)
		""",
	)
	abstract suspend fun liveOwnerHeaderCount(ownerRowId: Long): Long

	@Query(
		"""
		WITH owner(identity) AS (
		  SELECT identity FROM imported_pressure_entry_revision WHERE rowid = :ownerRowId
		)
		DELETE FROM imported_pressure_entry_revision
		WHERE identity = (SELECT identity FROM owner)
		""",
	)
	abstract suspend fun deleteLiveOwnerByRowId(ownerRowId: Long): Int

	/**
	 * Counts rows and raw UTF-8 metadata bytes before any live imported hierarchy is materialized.
	 *
	 * Numeric payload columns are fixed-width SQLite values; every attacker-controlled TEXT column
	 * participates in the byte total.
	 */
	@Query(
		"""
		SELECT
		  (SELECT COUNT(*) FROM imported_pressure_entry_revision WHERE identity = :identity)
		    AS header_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(identity AS BLOB)) +
		    LENGTH(CAST(content_checksum AS BLOB)) +
		    LENGTH(CAST(source_format AS BLOB)) +
		    LENGTH(CAST(import_job_id AS BLOB)) +
		    LENGTH(CAST(import_entry_key AS BLOB)) +
		    LENGTH(CAST(import_source_name AS BLOB))
		  ), 0) FROM imported_pressure_entry_revision WHERE identity = :identity)
		    AS header_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_receipt WHERE entry_identity = :identity)
		    AS receipt_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(import_job_id AS BLOB)) +
		    LENGTH(CAST(import_entry_key AS BLOB)) +
		    LENGTH(CAST(import_source_name AS BLOB)) +
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(entry_content_checksum AS BLOB))
		  ), 0) FROM imported_pressure_receipt WHERE entry_identity = :identity)
		    AS receipt_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_run WHERE entry_identity = :identity)
		    AS run_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(identity AS BLOB)) +
		    LENGTH(CAST(availability AS BLOB)) +
		    LENGTH(CAST(coverage AS BLOB))
		  ), 0) FROM imported_pressure_run WHERE entry_identity = :identity)
		    AS run_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_window WHERE entry_identity = :identity)
		    AS window_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(run_identity AS BLOB)) +
		    LENGTH(CAST(identity AS BLOB)) +
		    LENGTH(CAST(content_checksum AS BLOB)) +
		    LENGTH(CAST(sensor_accuracy AS BLOB)) +
		    LENGTH(CAST(closure_kind AS BLOB)) +
		    LENGTH(CAST(qualification AS BLOB)) +
		    LENGTH(CAST(stored_zone_id AS BLOB))
		  ), 0) FROM imported_pressure_window WHERE entry_identity = :identity)
		    AS window_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_identity_fence WHERE entry_identity = :identity)
		    AS identity_fence_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(protected_identity AS BLOB)) +
		    LENGTH(CAST(identity_kind AS BLOB)) +
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    COALESCE(LENGTH(CAST(run_identity AS BLOB)), 0) +
		    LENGTH(CAST(fence_reason AS BLOB)) +
		    LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_identity_fence WHERE entry_identity = :identity)
		    AS identity_fence_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_entry_deletion WHERE entry_identity = :identity)
		    AS entry_deletion_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(run_deletion_set_checksum AS BLOB)) +
		    LENGTH(CAST(identity_fence_set_checksum AS BLOB)) +
		    LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_entry_deletion WHERE entry_identity = :identity)
		    AS entry_deletion_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_deletion_generation
		    WHERE run_identity IN (
		      SELECT protected_identity FROM imported_pressure_identity_fence
		      WHERE entry_identity = :identity AND identity_kind = 'RUN'
		    )) AS run_deletion_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(run_identity AS BLOB)) + LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_deletion_generation
		    WHERE run_identity IN (
		      SELECT protected_identity FROM imported_pressure_identity_fence
		      WHERE entry_identity = :identity AND identity_kind = 'RUN'
		    )) AS run_deletion_text_bytes
		""",
	)
	abstract suspend fun lineageFootprint(identity: String): ImportedPressureLineageFootprint

	/** Global byte/count preflight before keyset paging allocates any imported identity text. */
	@Query(
		"""
		SELECT
		  (SELECT COUNT(*) FROM imported_pressure_entry_revision) AS header_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(identity AS BLOB)) +
		    LENGTH(CAST(content_checksum AS BLOB)) +
		    LENGTH(CAST(source_format AS BLOB)) +
		    LENGTH(CAST(import_job_id AS BLOB)) +
		    LENGTH(CAST(import_entry_key AS BLOB)) +
		    LENGTH(CAST(import_source_name AS BLOB))
		  ), 0) FROM imported_pressure_entry_revision) AS header_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_receipt) AS receipt_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(import_job_id AS BLOB)) +
		    LENGTH(CAST(import_entry_key AS BLOB)) +
		    LENGTH(CAST(import_source_name AS BLOB)) +
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(entry_content_checksum AS BLOB))
		  ), 0) FROM imported_pressure_receipt) AS receipt_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_run) AS run_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(identity AS BLOB)) +
		    LENGTH(CAST(availability AS BLOB)) +
		    LENGTH(CAST(coverage AS BLOB))
		  ), 0) FROM imported_pressure_run) AS run_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_window) AS window_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(run_identity AS BLOB)) +
		    LENGTH(CAST(identity AS BLOB)) +
		    LENGTH(CAST(content_checksum AS BLOB)) +
		    LENGTH(CAST(sensor_accuracy AS BLOB)) +
		    LENGTH(CAST(closure_kind AS BLOB)) +
		    LENGTH(CAST(qualification AS BLOB)) +
		    LENGTH(CAST(stored_zone_id AS BLOB))
		  ), 0) FROM imported_pressure_window) AS window_text_bytes,
		  0 AS identity_fence_count,
		  0 AS identity_fence_text_bytes,
		  0 AS entry_deletion_count,
		  0 AS entry_deletion_text_bytes,
		  0 AS run_deletion_count,
		  0 AS run_deletion_text_bytes
		""",
	)
	abstract suspend fun liveMaintenanceFootprint(): ImportedPressureLineageFootprint

	/** Complete ordered lineage plus one overflow row; callers must reject any non-contiguous head. */
	suspend fun entryRevisionsForAdmission(
		identity: String,
	): List<ImportedPressureEntryRevisionEntity> = loadEntryRevisions(
		identity,
		MAX_REVISIONS_PER_ENTRY + 1,
	)

	@Query(
		"SELECT * FROM imported_pressure_entry_revision WHERE identity = :identity " +
			"ORDER BY import_revision LIMIT :limit",
	)
	protected abstract suspend fun loadEntryRevisions(
		identity: String,
		limit: Int,
	): List<ImportedPressureEntryRevisionEntity>

	/** Complete bounded revision headers for a finite imported-history candidate batch. */
	suspend fun entryRevisionsForHistory(
		identities: List<String>,
	): List<ImportedPressureEntryRevisionEntity> = loadHistoryEntryRevisions(
		identities = checkedHistoryIdentities(identities),
		limit = historyLimit(identities.size, MAX_REVISIONS_PER_ENTRY),
	)

	@Query(
		"SELECT * FROM imported_pressure_entry_revision WHERE identity IN (:identities) " +
			"ORDER BY identity, import_revision LIMIT :limit",
	)
	protected abstract suspend fun loadHistoryEntryRevisions(
		identities: List<String>,
		limit: Int,
	): List<ImportedPressureEntryRevisionEntity>

	@Query(
		"SELECT * FROM imported_pressure_receipt " +
			"WHERE import_job_id = :jobId AND import_entry_key = :entryKey",
	)
	abstract suspend fun receipt(
		jobId: String,
		entryKey: String,
	): ImportedPressureReceiptEntity?

	/** Complete bounded receipt authority for one opaque entry, including alternate claims. */
	suspend fun receiptsForAdmission(
		identity: String,
	): List<ImportedPressureReceiptEntity> = loadReceipts(
		identity,
		MAX_RECEIPTS_PER_ENTRY + 1,
	)

	@Query(
		"SELECT * FROM imported_pressure_receipt WHERE entry_identity = :identity " +
			"ORDER BY entry_import_revision, import_job_id, import_entry_key LIMIT :limit",
	)
	protected abstract suspend fun loadReceipts(
		identity: String,
		limit: Int,
	): List<ImportedPressureReceiptEntity>

	/** Complete bounded receipt authority for a finite imported-history candidate batch. */
	suspend fun receiptsForHistory(identities: List<String>): List<ImportedPressureReceiptEntity> =
		loadHistoryReceipts(
			identities = checkedHistoryIdentities(identities),
			limit = historyLimit(identities.size, MAX_RECEIPTS_PER_ENTRY),
		)

	@Query(
		"SELECT * FROM imported_pressure_receipt WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, import_job_id, import_entry_key LIMIT :limit",
	)
	protected abstract suspend fun loadHistoryReceipts(
		identities: List<String>,
		limit: Int,
	): List<ImportedPressureReceiptEntity>

	suspend fun runs(identity: String, revision: Long): List<ImportedPressureRunEntity> =
		loadRuns(identity, revision, MAX_RUNS_PER_ENTRY)

	@Query(
		"SELECT * FROM imported_pressure_run " +
			"WHERE entry_identity = :identity AND entry_import_revision = :revision " +
			"ORDER BY start_time_ms, identity LIMIT :limit",
	)
	protected abstract suspend fun loadRuns(
		identity: String,
		revision: Long,
		limit: Int,
	): List<ImportedPressureRunEntity>

	/** Complete bounded run set for all retained revisions of one opaque entry. */
	suspend fun allRunsForAdmission(identity: String): List<ImportedPressureRunEntity> =
		loadAllRuns(identity, MAX_TOTAL_RUNS_PER_ENTRY_LINEAGE + 1)

	@Query(
		"SELECT * FROM imported_pressure_run WHERE entry_identity = :identity " +
			"ORDER BY entry_import_revision, start_time_ms, identity LIMIT :limit",
	)
	protected abstract suspend fun loadAllRuns(
		identity: String,
		limit: Int,
	): List<ImportedPressureRunEntity>

	/** Complete bounded physical membership for a finite imported-history candidate batch. */
	suspend fun runsForHistory(identities: List<String>): List<ImportedPressureRunEntity> =
		loadHistoryRuns(
			identities = checkedHistoryIdentities(identities),
			limit = historyLimit(identities.size, MAX_TOTAL_RUNS_PER_ENTRY_LINEAGE),
		)

	@Query(
		"SELECT * FROM imported_pressure_run WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, start_time_ms, identity LIMIT :limit",
	)
	protected abstract suspend fun loadHistoryRuns(
		identities: List<String>,
		limit: Int,
	): List<ImportedPressureRunEntity>

	suspend fun windows(
		entryIdentity: String,
		entryRevision: Long,
		runIdentity: String,
	): List<ImportedPressureWindowEntity> = loadWindows(
		entryIdentity,
		entryRevision,
		runIdentity,
		MAX_WINDOWS_PER_RUN,
	)

	@Query(
		"SELECT * FROM imported_pressure_window " +
			"WHERE entry_identity = :entryIdentity AND entry_import_revision = :entryRevision " +
			"AND run_identity = :runIdentity ORDER BY interval_start_time_ms, identity LIMIT :limit",
	)
	protected abstract suspend fun loadWindows(
		entryIdentity: String,
		entryRevision: Long,
		runIdentity: String,
		limit: Int,
	): List<ImportedPressureWindowEntity>

	/** Complete bounded window set for all retained revisions of one opaque entry. */
	suspend fun allWindowsForAdmission(identity: String): List<ImportedPressureWindowEntity> =
		loadAllWindows(identity, MAX_TOTAL_WINDOWS_PER_ENTRY_LINEAGE + 1)

	@Query(
		"SELECT * FROM imported_pressure_window WHERE entry_identity = :identity " +
			"ORDER BY entry_import_revision, run_identity, interval_start_time_ms, identity LIMIT :limit",
	)
	protected abstract suspend fun loadAllWindows(
		identity: String,
		limit: Int,
	): List<ImportedPressureWindowEntity>

	/** Complete bounded windows for a finite imported-history candidate batch. */
	suspend fun windowsForHistory(identities: List<String>): List<ImportedPressureWindowEntity> =
		loadHistoryWindows(
			identities = checkedHistoryIdentities(identities),
			limit = historyLimit(identities.size, MAX_TOTAL_WINDOWS_PER_ENTRY_LINEAGE),
		)

	@Query(
		"SELECT * FROM imported_pressure_window WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, run_identity, " +
			"interval_start_time_ms, identity LIMIT :limit",
	)
	protected abstract suspend fun loadHistoryWindows(
		identities: List<String>,
		limit: Int,
	): List<ImportedPressureWindowEntity>

	@Query(
		"SELECT DISTINCT identity FROM imported_pressure_entry_revision " +
			"WHERE identity IN (:identities) LIMIT :limit",
	)
	abstract suspend fun existingEntryIdentities(
		identities: List<String>,
		limit: Int,
	): List<String>

	@Query(
		"SELECT DISTINCT identity, entry_identity FROM imported_pressure_run " +
			"WHERE identity IN (:identities) LIMIT :limit",
	)
	abstract suspend fun existingRunIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedPressureRunIdentityOwner>

	@Query(
		"SELECT DISTINCT identity, entry_identity, run_identity FROM imported_pressure_window " +
			"WHERE identity IN (:identities) LIMIT :limit",
	)
	abstract suspend fun existingWindowIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedPressureWindowIdentityOwner>

	@Query(
		"SELECT COUNT(*) FROM imported_pressure_run " +
			"WHERE entry_identity = :identity AND entry_import_revision = :revision",
	)
	abstract suspend fun runCount(identity: String, revision: Long): Int

	@Query(
		"SELECT COUNT(*) FROM imported_pressure_window " +
			"WHERE entry_identity = :entryIdentity AND entry_import_revision = :entryRevision " +
			"AND run_identity = :runIdentity",
	)
	abstract suspend fun windowCount(
		entryIdentity: String,
		entryRevision: Long,
		runIdentity: String,
	): Int

	@Query("SELECT * FROM imported_pressure_deletion_generation WHERE run_identity = :runIdentity")
	abstract suspend fun deletionGeneration(runIdentity: String): ImportedPressureDeletionGenerationEntity?

	@Query("SELECT * FROM imported_pressure_deletion_generation WHERE run_identity IN (:runIdentities)")
	abstract suspend fun deletionGenerations(
		runIdentities: List<String>,
	): List<ImportedPressureDeletionGenerationEntity>

	@Query("SELECT * FROM imported_pressure_entry_deletion WHERE entry_identity = :entryIdentity")
	abstract suspend fun entryDeletion(entryIdentity: String): ImportedPressureEntryDeletionEntity?

	@Query("SELECT * FROM imported_pressure_entry_deletion WHERE entry_identity IN (:entryIdentities)")
	abstract suspend fun entryDeletions(
		entryIdentities: List<String>,
	): List<ImportedPressureEntryDeletionEntity>

	@Query("SELECT * FROM imported_pressure_retention_receipt WHERE entry_identity = :entryIdentity")
	abstract suspend fun retentionReceipt(
		entryIdentity: String,
	): ImportedPressureRetentionReceiptEntity?

	@Query(
		"SELECT * FROM imported_pressure_retention_receipt " +
			"WHERE entry_identity IN (:entryIdentities)",
	)
	abstract suspend fun retentionReceipts(
		entryIdentities: List<String>,
	): List<ImportedPressureRetentionReceiptEntity>

	@Query(
		"SELECT * FROM imported_pressure_retention_receipt " +
			"WHERE :afterIdentity IS NULL OR entry_identity > :afterIdentity " +
			"ORDER BY entry_identity LIMIT :limit",
	)
	abstract suspend fun retentionReceiptPage(
		afterIdentity: String?,
		limit: Int,
	): List<ImportedPressureRetentionReceiptEntity>

	/** Numeric retained owner cursor; receipt text is loaded only after its complete preflight. */
	@Query(
		"SELECT rowid FROM imported_pressure_retention_receipt WHERE rowid > :afterOwnerRowId " +
			"ORDER BY rowid LIMIT :limit",
	)
	abstract suspend fun retainedOwnerRowIdPage(
		afterOwnerRowId: Long,
		limit: Int,
	): List<Long>

	@Query(
		"""
		WITH owner(entry_identity) AS (
		  SELECT entry_identity FROM imported_pressure_retention_receipt WHERE rowid = :ownerRowId
		)
		SELECT
		  (SELECT COUNT(*) FROM imported_pressure_retention_receipt
		    WHERE entry_identity = (SELECT entry_identity FROM owner)) AS receipt_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(latest_content_checksum AS BLOB)) +
		    LENGTH(CAST(recency_tie_identity AS BLOB)) +
		    LENGTH(CAST(run_deletion_set_checksum AS BLOB)) +
		    LENGTH(CAST(protected_identity_set_checksum AS BLOB)) +
		    LENGTH(CAST(identity_fence_set_checksum AS BLOB)) +
		    LENGTH(CAST(lineage_authority_checksum AS BLOB)) +
		    LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_retention_receipt
		    WHERE entry_identity = (SELECT entry_identity FROM owner)) AS receipt_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_retained_identity
		    WHERE entry_identity = (SELECT entry_identity FROM owner)) AS marker_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(protected_identity AS BLOB)) +
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(identity_kind AS BLOB))
		  ), 0) FROM imported_pressure_retained_identity
		    WHERE entry_identity = (SELECT entry_identity FROM owner)) AS marker_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_identity_fence
		    WHERE entry_identity = (SELECT entry_identity FROM owner)) AS identity_fence_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(protected_identity AS BLOB)) + LENGTH(CAST(identity_kind AS BLOB)) +
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    COALESCE(LENGTH(CAST(run_identity AS BLOB)), 0) +
		    LENGTH(CAST(fence_reason AS BLOB)) + LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_identity_fence
		    WHERE entry_identity = (SELECT entry_identity FROM owner)) AS identity_fence_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_entry_deletion
		    WHERE entry_identity = (SELECT entry_identity FROM owner)) AS entry_deletion_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(run_deletion_set_checksum AS BLOB)) +
		    LENGTH(CAST(identity_fence_set_checksum AS BLOB)) +
		    LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_entry_deletion
		    WHERE entry_identity = (SELECT entry_identity FROM owner)) AS entry_deletion_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_deletion_generation
		    WHERE run_identity IN (
		      SELECT protected_identity FROM imported_pressure_identity_fence
		      WHERE entry_identity = (SELECT entry_identity FROM owner) AND identity_kind = 'RUN'
		    )) AS run_deletion_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(run_identity AS BLOB)) + LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_deletion_generation
		    WHERE run_identity IN (
		      SELECT protected_identity FROM imported_pressure_identity_fence
		      WHERE entry_identity = (SELECT entry_identity FROM owner) AND identity_kind = 'RUN'
		    )) AS run_deletion_text_bytes
		""",
	)
	abstract suspend fun retainedOwnerFootprint(
		ownerRowId: Long,
	): ImportedPressureRetainedFootprint

	@Query("SELECT * FROM imported_pressure_retention_receipt WHERE rowid = :ownerRowId LIMIT 1")
	abstract suspend fun retentionReceiptByOwnerRowId(
		ownerRowId: Long,
	): ImportedPressureRetentionReceiptEntity?

	@Query(
		"""
		WITH owner(entry_identity) AS (
		  SELECT entry_identity FROM imported_pressure_retention_receipt WHERE rowid = :ownerRowId
		)
		DELETE FROM imported_pressure_retained_identity
		WHERE entry_identity = (SELECT entry_identity FROM owner)
		""",
	)
	abstract suspend fun deleteRetainedOwnerMarkersByRowId(ownerRowId: Long): Int

	@Query("DELETE FROM imported_pressure_retention_receipt WHERE rowid = :ownerRowId")
	abstract suspend fun deleteRetentionReceiptByOwnerRowId(ownerRowId: Long): Int

	@Query(
		"SELECT * FROM imported_pressure_retained_identity " +
			"WHERE entry_identity IN (:entryIdentities) " +
			"ORDER BY entry_identity, identity_kind, protected_identity LIMIT :limit",
	)
	abstract suspend fun retainedIdentitiesForEntries(
		entryIdentities: List<String>,
		limit: Int,
	): List<ImportedPressureRetainedIdentityEntity>

	@Query(
		"SELECT * FROM imported_pressure_retained_identity " +
			"WHERE protected_identity IN (:identities) ORDER BY protected_identity LIMIT :limit",
	)
	abstract suspend fun retainedIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedPressureRetainedIdentityEntity>

	@Query(
		"SELECT * FROM imported_pressure_identity_fence " +
			"WHERE protected_identity IN (:identities) ORDER BY protected_identity LIMIT :limit",
	)
	abstract suspend fun identityFences(
		identities: List<String>,
		limit: Int,
	): List<ImportedPressureIdentityFenceEntity>

	@Query(
		"SELECT * FROM imported_pressure_identity_fence WHERE entry_identity = :entryIdentity " +
			"ORDER BY identity_kind, protected_identity LIMIT :limit",
	)
	abstract suspend fun identityFencesForEntry(
		entryIdentity: String,
		limit: Int,
	): List<ImportedPressureIdentityFenceEntity>

	@Query(
		"SELECT * FROM imported_pressure_identity_fence " +
			"WHERE :afterIdentity IS NULL OR protected_identity > :afterIdentity " +
			"ORDER BY protected_identity LIMIT :limit",
	)
	abstract suspend fun identityFencePage(
		afterIdentity: String?,
		limit: Int,
	): List<ImportedPressureIdentityFenceEntity>

	@Query("SELECT COUNT(*) FROM imported_pressure_identity_fence")
	abstract suspend fun identityFenceCount(): Long

	@Query(
		"""
		SELECT
		  (SELECT COUNT(*) FROM imported_pressure_retention_receipt
		    WHERE entry_identity = :entryIdentity) AS receipt_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(latest_content_checksum AS BLOB)) +
		    LENGTH(CAST(recency_tie_identity AS BLOB)) +
		    LENGTH(CAST(run_deletion_set_checksum AS BLOB)) +
		    LENGTH(CAST(protected_identity_set_checksum AS BLOB)) +
		    LENGTH(CAST(identity_fence_set_checksum AS BLOB)) +
		    LENGTH(CAST(lineage_authority_checksum AS BLOB)) +
		    LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_retention_receipt
		    WHERE entry_identity = :entryIdentity) AS receipt_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_retained_identity
		    WHERE entry_identity = :entryIdentity) AS marker_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(protected_identity AS BLOB)) +
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(identity_kind AS BLOB))
		  ), 0) FROM imported_pressure_retained_identity
		    WHERE entry_identity = :entryIdentity) AS marker_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_identity_fence
		    WHERE entry_identity = :entryIdentity) AS identity_fence_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(protected_identity AS BLOB)) +
		    LENGTH(CAST(identity_kind AS BLOB)) +
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    COALESCE(LENGTH(CAST(run_identity AS BLOB)), 0) +
		    LENGTH(CAST(fence_reason AS BLOB)) +
		    LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_identity_fence
		    WHERE entry_identity = :entryIdentity) AS identity_fence_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_entry_deletion
		    WHERE entry_identity = :entryIdentity) AS entry_deletion_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(run_deletion_set_checksum AS BLOB)) +
		    LENGTH(CAST(identity_fence_set_checksum AS BLOB)) +
		    LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_entry_deletion
		    WHERE entry_identity = :entryIdentity) AS entry_deletion_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_deletion_generation
		    WHERE run_identity IN (
		      SELECT protected_identity FROM imported_pressure_identity_fence
		      WHERE entry_identity = :entryIdentity AND identity_kind = 'RUN'
		    )) AS run_deletion_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(run_identity AS BLOB)) + LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_deletion_generation
		    WHERE run_identity IN (
		      SELECT protected_identity FROM imported_pressure_identity_fence
		      WHERE entry_identity = :entryIdentity AND identity_kind = 'RUN'
		    )) AS run_deletion_text_bytes
		""",
	)
	abstract suspend fun retainedFootprint(
		entryIdentity: String,
	): ImportedPressureRetainedFootprint

	@Query(
		"""
		SELECT
		  (SELECT COUNT(*) FROM imported_pressure_retention_receipt) AS receipt_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(latest_content_checksum AS BLOB)) +
		    LENGTH(CAST(recency_tie_identity AS BLOB)) +
		    LENGTH(CAST(run_deletion_set_checksum AS BLOB)) +
		    LENGTH(CAST(protected_identity_set_checksum AS BLOB)) +
		    LENGTH(CAST(identity_fence_set_checksum AS BLOB)) +
		    LENGTH(CAST(lineage_authority_checksum AS BLOB)) +
		    LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_retention_receipt) AS receipt_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_retained_identity) AS marker_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(protected_identity AS BLOB)) +
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(identity_kind AS BLOB))
		  ), 0) FROM imported_pressure_retained_identity) AS marker_text_bytes,
		  0 AS identity_fence_count,
		  0 AS identity_fence_text_bytes,
		  0 AS entry_deletion_count,
		  0 AS entry_deletion_text_bytes,
		  0 AS run_deletion_count,
		  0 AS run_deletion_text_bytes
		""",
	)
	abstract suspend fun retainedMaintenanceFootprint(): ImportedPressureRetainedFootprint

	@Query(
		"""
		SELECT
		  (SELECT COUNT(*) FROM imported_pressure_identity_fence) AS identity_fence_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(protected_identity AS BLOB)) +
		    LENGTH(CAST(identity_kind AS BLOB)) +
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    COALESCE(LENGTH(CAST(run_identity AS BLOB)), 0) +
		    LENGTH(CAST(fence_reason AS BLOB)) +
		    LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_identity_fence) AS identity_fence_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_entry_deletion) AS entry_deletion_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(run_deletion_set_checksum AS BLOB)) +
		    LENGTH(CAST(identity_fence_set_checksum AS BLOB)) +
		    LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_entry_deletion) AS entry_deletion_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_deletion_generation) AS run_deletion_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(run_identity AS BLOB)) +
		    LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_deletion_generation) AS run_deletion_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_source_erase_witness) AS witness_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(witness_kind AS BLOB)) +
		    LENGTH(CAST(witness_identity AS BLOB)) +
		    LENGTH(CAST(authority_checksum AS BLOB)) +
		    LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_source_erase_witness) AS witness_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_source_erase) AS source_erase_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(legacy_sample_set_checksum AS BLOB)) +
		    LENGTH(CAST(local_scope_set_checksum AS BLOB)) +
		    LENGTH(CAST(entry_deletion_set_checksum AS BLOB)) +
		    LENGTH(CAST(run_deletion_set_checksum AS BLOB)) +
		    LENGTH(CAST(identity_fence_set_checksum AS BLOB)) +
		    LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_source_erase) AS source_erase_text_bytes
		""",
	)
	abstract suspend fun privacyMaintenanceFootprint(): ImportedPressurePrivacyFootprint

	/** Complete authority bytes for caller-known opaque identities before authority rows are loaded. */
	@Query(
		"""
		SELECT
		  (SELECT COUNT(*) FROM imported_pressure_retained_identity
		    WHERE protected_identity IN (:identities)) AS retained_marker_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(protected_identity AS BLOB)) +
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(identity_kind AS BLOB))
		  ), 0) FROM imported_pressure_retained_identity
		    WHERE protected_identity IN (:identities)) AS retained_marker_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_identity_fence
		    WHERE protected_identity IN (:identities)) AS identity_fence_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(protected_identity AS BLOB)) +
		    LENGTH(CAST(identity_kind AS BLOB)) +
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    COALESCE(LENGTH(CAST(run_identity AS BLOB)), 0) +
		    LENGTH(CAST(fence_reason AS BLOB)) +
		    LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_identity_fence
		    WHERE protected_identity IN (:identities)) AS identity_fence_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_entry_deletion
		    WHERE entry_identity IN (:identities)) AS entry_deletion_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(entry_identity AS BLOB)) +
		    LENGTH(CAST(run_deletion_set_checksum AS BLOB)) +
		    LENGTH(CAST(identity_fence_set_checksum AS BLOB)) +
		    LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_entry_deletion
		    WHERE entry_identity IN (:identities)) AS entry_deletion_text_bytes,
		  (SELECT COUNT(*) FROM imported_pressure_deletion_generation
		    WHERE run_identity IN (:identities)) AS run_deletion_count,
		  (SELECT COALESCE(SUM(
		    LENGTH(CAST(run_identity AS BLOB)) + LENGTH(CAST(effect_checksum AS BLOB))
		  ), 0) FROM imported_pressure_deletion_generation
		    WHERE run_identity IN (:identities)) AS run_deletion_text_bytes
		""",
	)
	abstract suspend fun identityAuthorityFootprint(
		identities: List<String>,
	): ImportedPressureIdentityAuthorityFootprint

	@Query("SELECT COUNT(*) FROM imported_pressure_retention_receipt")
	abstract suspend fun retentionReceiptCount(): Long

	@Query("SELECT COUNT(*) FROM imported_pressure_retained_identity")
	abstract suspend fun retainedIdentityCount(): Long

	@Query(
		"SELECT COUNT(*) FROM imported_pressure_retained_identity AS marker " +
			"LEFT JOIN imported_pressure_retention_receipt AS receipt " +
			"ON receipt.entry_identity = marker.entry_identity " +
			"WHERE receipt.entry_identity IS NULL",
	)
	abstract suspend fun orphanRetainedIdentityCount(): Long

	@Query("SELECT * FROM imported_pressure_source_erase WHERE id = 1")
	abstract suspend fun sourceErase(): ImportedPressureSourceEraseEntity?

	@Query(
		"SELECT * FROM imported_pressure_source_erase_witness " +
			"WHERE :afterKind IS NULL OR witness_kind > :afterKind OR " +
			"(witness_kind = :afterKind AND witness_identity > COALESCE(:afterIdentity, '')) " +
			"ORDER BY witness_kind, witness_identity LIMIT :limit",
	)
	abstract suspend fun sourceEraseWitnessPage(
		afterKind: String?,
		afterIdentity: String?,
		limit: Int,
	): List<ImportedPressureSourceEraseWitnessEntity>

	@Query("SELECT COUNT(*) FROM imported_pressure_source_erase_witness")
	abstract suspend fun sourceEraseWitnessCount(): Long

	@Query("DELETE FROM imported_pressure_source_erase_witness")
	abstract fun deleteSourceEraseWitnesses(): Int

	@Query(
		"UPDATE imported_pressure_source_erase SET " +
			"collected_data_epoch = :replacementCollectedDataEpoch, " +
			"source_evidence_revision = :replacementSourceEvidenceRevision, " +
			"erased_at_ms = :replacementErasedAtMs, " +
			"provider_registration_generation = :providerRegistrationGeneration, " +
			"legacy_write_fence_generation = :legacyWriteFenceGeneration, " +
			"local_fact_revision_count = :localFactRevisionCount, " +
			"local_wal_event_count = :localWalEventCount, " +
			"legacy_sample_count = :legacySampleCount, " +
			"legacy_sample_set_checksum = :legacySampleSetChecksum, " +
			"imported_entry_count = :importedEntryCount, " +
			"imported_revision_count = :importedRevisionCount, " +
			"imported_run_count = :importedRunCount, " +
			"imported_window_count = :importedWindowCount, " +
			"fenced_local_run_count = :fencedLocalRunCount, " +
			"local_scope_set_checksum = :localScopeSetChecksum, " +
			"entry_deletion_count = :entryDeletionCount, " +
			"entry_deletion_set_checksum = :entryDeletionSetChecksum, " +
			"run_deletion_count = :runDeletionCount, " +
			"run_deletion_set_checksum = :runDeletionSetChecksum, " +
			"identity_fence_count = :identityFenceCount, " +
			"identity_fence_set_checksum = :identityFenceSetChecksum, " +
			"effect_checksum = :effectChecksum " +
			"WHERE id = 1 AND source_evidence_revision = :expectedSourceEvidenceRevision " +
			"AND collected_data_epoch = :expectedCollectedDataEpoch",
	)
	protected abstract suspend fun updateSourceErase(
		expectedSourceEvidenceRevision: Long,
		expectedCollectedDataEpoch: Long,
		replacementCollectedDataEpoch: Long,
		replacementSourceEvidenceRevision: Long,
		replacementErasedAtMs: Long,
		providerRegistrationGeneration: Long?,
		legacyWriteFenceGeneration: Long,
		localFactRevisionCount: Int,
		localWalEventCount: Int,
		legacySampleCount: Int,
		legacySampleSetChecksum: String,
		importedEntryCount: Int,
		importedRevisionCount: Int,
		importedRunCount: Int,
		importedWindowCount: Int,
		fencedLocalRunCount: Int,
		localScopeSetChecksum: String,
		entryDeletionCount: Int,
		entryDeletionSetChecksum: String,
		runDeletionCount: Int,
		runDeletionSetChecksum: String,
		identityFenceCount: Int,
		identityFenceSetChecksum: String,
		effectChecksum: String,
	): Int

	suspend fun replaceSourceErase(
		expected: ImportedPressureSourceEraseEntity,
		replacement: ImportedPressureSourceEraseEntity,
	): Int = updateSourceErase(
		expectedSourceEvidenceRevision = expected.sourceEvidenceRevision,
		expectedCollectedDataEpoch = expected.collectedDataEpoch,
		replacementCollectedDataEpoch = replacement.collectedDataEpoch,
		replacementSourceEvidenceRevision = replacement.sourceEvidenceRevision,
		replacementErasedAtMs = replacement.erasedAtMs,
		providerRegistrationGeneration = replacement.providerRegistrationGeneration,
		legacyWriteFenceGeneration = replacement.legacyWriteFenceGeneration,
		localFactRevisionCount = replacement.localFactRevisionCount,
		localWalEventCount = replacement.localWalEventCount,
		legacySampleCount = replacement.legacySampleCount,
		legacySampleSetChecksum = replacement.legacySampleSetChecksum,
		importedEntryCount = replacement.importedEntryCount,
		importedRevisionCount = replacement.importedRevisionCount,
		importedRunCount = replacement.importedRunCount,
		importedWindowCount = replacement.importedWindowCount,
		fencedLocalRunCount = replacement.fencedLocalRunCount,
		localScopeSetChecksum = replacement.localScopeSetChecksum,
		entryDeletionCount = replacement.entryDeletionCount,
		entryDeletionSetChecksum = replacement.entryDeletionSetChecksum,
		runDeletionCount = replacement.runDeletionCount,
		runDeletionSetChecksum = replacement.runDeletionSetChecksum,
		identityFenceCount = replacement.identityFenceCount,
		identityFenceSetChecksum = replacement.identityFenceSetChecksum,
		effectChecksum = replacement.effectChecksum,
	)

	@Query(
		"SELECT * FROM imported_pressure_entry_deletion " +
			"WHERE :afterIdentity IS NULL OR entry_identity > :afterIdentity " +
			"ORDER BY entry_identity LIMIT :limit",
	)
	abstract suspend fun entryDeletionPage(
		afterIdentity: String?,
		limit: Int,
	): List<ImportedPressureEntryDeletionEntity>

	@Query(
		"SELECT * FROM imported_pressure_deletion_generation " +
			"WHERE :afterIdentity IS NULL OR run_identity > :afterIdentity " +
			"ORDER BY run_identity LIMIT :limit",
	)
	abstract suspend fun deletionGenerationPage(
		afterIdentity: String?,
		limit: Int,
	): List<ImportedPressureDeletionGenerationEntity>

	@Query("SELECT COUNT(*) FROM imported_pressure_entry_deletion")
	abstract suspend fun entryDeletionCount(): Long

	@Query("SELECT COUNT(*) FROM imported_pressure_deletion_generation")
	abstract suspend fun deletionGenerationCount(): Long

	/** Bounded logical-entry tombstones for one finite imported-history candidate batch. */
	suspend fun entryDeletionsForHistory(
		entryIdentities: List<String>,
	): List<ImportedPressureEntryDeletionEntity> =
		entryDeletions(checkedHistoryIdentities(entryIdentities))

	/** Bounded batched tombstone read; never fans out once per imported row. */
	suspend fun deletionGenerationsForHistory(
		runIdentities: List<String>,
	): List<ImportedPressureDeletionGenerationEntity> {
		require(runIdentities.size <= MAX_HISTORY_ENTRY_CANDIDATES * MAX_TOTAL_RUNS_PER_ENTRY_LINEAGE)
		return runIdentities.distinct().chunked(HISTORY_ID_QUERY_CHUNK_SIZE).flatMap { identities ->
			deletionGenerations(identities)
		}
	}

	/** Exact compare-and-set; a stale deleter cannot overwrite a newer privacy generation. */
	suspend fun advanceDeletionGeneration(
		expectedCollectedDataEpoch: Long,
		expectedGeneration: Long,
		replacement: ImportedPressureDeletionGenerationEntity,
	): Int {
		require(replacement.collectedDataEpoch == expectedCollectedDataEpoch)
		require(expectedGeneration in 0L until Long.MAX_VALUE)
		require(replacement.generation == expectedGeneration + 1L)
		return updateDeletionGeneration(
			replacement.runIdentity,
			expectedCollectedDataEpoch,
			expectedGeneration,
			replacement.collectedDataEpoch,
			replacement.generation,
			replacement.deletedAtMs,
			replacement.effectChecksum,
		)
	}

	@Query(
		"UPDATE imported_pressure_deletion_generation SET collected_data_epoch = :collectedDataEpoch, " +
			"generation = :generation, deleted_at_ms = :deletedAtMs, effect_checksum = :effectChecksum " +
			"WHERE run_identity = :runIdentity AND collected_data_epoch = :expectedCollectedDataEpoch " +
			"AND generation = :expectedGeneration",
	)
	protected abstract suspend fun updateDeletionGeneration(
		runIdentity: String,
		expectedCollectedDataEpoch: Long,
		expectedGeneration: Long,
		collectedDataEpoch: Long,
		generation: Long,
		deletedAtMs: Long,
		effectChecksum: String,
	): Int

	/** Cancellation rollback for one not-yet-admitted immutable receipt; children cascade. */
	@Query(
		"DELETE FROM imported_pressure_entry_revision " +
			"WHERE identity = :identity AND import_revision = :revision",
	)
	abstract suspend fun deleteEntryRevision(identity: String, revision: Long): Int

	@Query("DELETE FROM imported_pressure_entry_revision WHERE identity = :identity")
	abstract suspend fun deleteEntryRevisionLineage(identity: String): Int

	@Query("DELETE FROM imported_pressure_retained_identity WHERE entry_identity = :identity")
	abstract suspend fun deleteRetainedIdentities(identity: String): Int

	@Query("DELETE FROM imported_pressure_retention_receipt WHERE entry_identity = :identity")
	abstract suspend fun deleteRetentionReceipt(identity: String): Int

	/** Payload/provenance removal after full-clear identity preservation. */
	@Query("DELETE FROM imported_pressure_entry_revision")
	abstract fun deleteAllEntries()

	@Query("DELETE FROM imported_pressure_receipt")
	abstract fun deleteAllReceipts()

	/** Database teardown only. Full collected-data clear must preserve permanent run authority. */
	@Query("DELETE FROM imported_pressure_deletion_generation")
	abstract fun deleteAllDeletionGenerations()

	/** Database teardown only. Full collected-data clear must preserve permanent entry authority. */
	@Query("DELETE FROM imported_pressure_entry_deletion")
	abstract fun deleteAllEntryDeletions()

	@Query("DELETE FROM imported_pressure_retained_identity")
	abstract fun deleteAllRetainedIdentities()

	@Query("DELETE FROM imported_pressure_retention_receipt")
	abstract fun deleteAllRetentionReceipts()

	/** Full clear removes this receipt last, after its witnesses and typed fences are authenticated. */
	@Query("DELETE FROM imported_pressure_source_erase")
	abstract fun deleteSourceErase()

	private fun checkedHistoryIdentities(identities: List<String>): List<String> {
		require(identities.isNotEmpty())
		require(identities.size <= MAX_HISTORY_ENTRY_CANDIDATES)
		require(identities.distinct().size == identities.size)
		return identities
	}

	private fun historyLimit(identityCount: Int, maximumPerIdentity: Int): Int =
		Math.addExact(Math.multiplyExact(identityCount, maximumPerIdentity), 1)

	companion object {
		const val MAX_RUNS_PER_ENTRY = 64
		const val MAX_WINDOWS_PER_RUN = 2_048
		const val MAX_TOTAL_WINDOWS_PER_ENTRY = 16_384
		const val MAX_REVISIONS_PER_ENTRY = 16
		const val MAX_RECEIPTS_PER_ENTRY = 256
		const val MAX_TOTAL_RUNS_PER_ENTRY_LINEAGE = MAX_REVISIONS_PER_ENTRY * MAX_RUNS_PER_ENTRY
		const val MAX_TOTAL_WINDOWS_PER_ENTRY_LINEAGE =
			MAX_REVISIONS_PER_ENTRY * MAX_TOTAL_WINDOWS_PER_ENTRY
		const val MAX_HISTORY_ENTRY_CANDIDATES = 64
		const val MAX_LINEAGE_TEXT_BYTES = 128L * 1_024L * 1_024L
		const val MAX_MAINTENANCE_TEXT_BYTES = 256L * 1_024L * 1_024L
		private const val HISTORY_ID_QUERY_CHUNK_SIZE = 400
	}
}

data class ImportedPressureLineageFootprint(
	@ColumnInfo(name = "header_count") val headerCount: Long,
	@ColumnInfo(name = "header_text_bytes") val headerTextBytes: Long,
	@ColumnInfo(name = "receipt_count") val receiptCount: Long,
	@ColumnInfo(name = "receipt_text_bytes") val receiptTextBytes: Long,
	@ColumnInfo(name = "run_count") val runCount: Long,
	@ColumnInfo(name = "run_text_bytes") val runTextBytes: Long,
	@ColumnInfo(name = "window_count") val windowCount: Long,
	@ColumnInfo(name = "window_text_bytes") val windowTextBytes: Long,
	@ColumnInfo(name = "identity_fence_count") val identityFenceCount: Long = 0L,
	@ColumnInfo(name = "identity_fence_text_bytes") val identityFenceTextBytes: Long = 0L,
	@ColumnInfo(name = "entry_deletion_count") val entryDeletionCount: Long = 0L,
	@ColumnInfo(name = "entry_deletion_text_bytes") val entryDeletionTextBytes: Long = 0L,
	@ColumnInfo(name = "run_deletion_count") val runDeletionCount: Long = 0L,
	@ColumnInfo(name = "run_deletion_text_bytes") val runDeletionTextBytes: Long = 0L,
) {
	val totalTextBytes: Long
		get() = Math.addExact(
			Math.addExact(
				Math.addExact(headerTextBytes, receiptTextBytes),
				Math.addExact(runTextBytes, windowTextBytes),
			),
			Math.addExact(
				Math.addExact(identityFenceTextBytes, entryDeletionTextBytes),
				runDeletionTextBytes,
			),
		)
}

data class ImportedPressureRetainedFootprint(
	@ColumnInfo(name = "receipt_count") val receiptCount: Long,
	@ColumnInfo(name = "receipt_text_bytes") val receiptTextBytes: Long,
	@ColumnInfo(name = "marker_count") val markerCount: Long,
	@ColumnInfo(name = "marker_text_bytes") val markerTextBytes: Long,
	@ColumnInfo(name = "identity_fence_count") val identityFenceCount: Long = 0L,
	@ColumnInfo(name = "identity_fence_text_bytes") val identityFenceTextBytes: Long = 0L,
	@ColumnInfo(name = "entry_deletion_count") val entryDeletionCount: Long = 0L,
	@ColumnInfo(name = "entry_deletion_text_bytes") val entryDeletionTextBytes: Long = 0L,
	@ColumnInfo(name = "run_deletion_count") val runDeletionCount: Long = 0L,
	@ColumnInfo(name = "run_deletion_text_bytes") val runDeletionTextBytes: Long = 0L,
) {
	val totalTextBytes: Long
		get() = Math.addExact(
			Math.addExact(receiptTextBytes, markerTextBytes),
			Math.addExact(
				Math.addExact(identityFenceTextBytes, entryDeletionTextBytes),
				runDeletionTextBytes,
			),
		)
}

data class ImportedPressurePrivacyFootprint(
	@ColumnInfo(name = "identity_fence_count") val identityFenceCount: Long,
	@ColumnInfo(name = "identity_fence_text_bytes") val identityFenceTextBytes: Long,
	@ColumnInfo(name = "entry_deletion_count") val entryDeletionCount: Long,
	@ColumnInfo(name = "entry_deletion_text_bytes") val entryDeletionTextBytes: Long,
	@ColumnInfo(name = "run_deletion_count") val runDeletionCount: Long,
	@ColumnInfo(name = "run_deletion_text_bytes") val runDeletionTextBytes: Long,
	@ColumnInfo(name = "witness_count") val witnessCount: Long,
	@ColumnInfo(name = "witness_text_bytes") val witnessTextBytes: Long,
	@ColumnInfo(name = "source_erase_count") val sourceEraseCount: Long,
	@ColumnInfo(name = "source_erase_text_bytes") val sourceEraseTextBytes: Long,
) {
	val totalTextBytes: Long
		get() = Math.addExact(
			Math.addExact(
				Math.addExact(identityFenceTextBytes, entryDeletionTextBytes),
				Math.addExact(runDeletionTextBytes, witnessTextBytes),
			),
			sourceEraseTextBytes,
		)
}

data class ImportedPressureIdentityAuthorityFootprint(
	@ColumnInfo(name = "retained_marker_count") val retainedMarkerCount: Long,
	@ColumnInfo(name = "retained_marker_text_bytes") val retainedMarkerTextBytes: Long,
	@ColumnInfo(name = "identity_fence_count") val identityFenceCount: Long,
	@ColumnInfo(name = "identity_fence_text_bytes") val identityFenceTextBytes: Long,
	@ColumnInfo(name = "entry_deletion_count") val entryDeletionCount: Long,
	@ColumnInfo(name = "entry_deletion_text_bytes") val entryDeletionTextBytes: Long,
	@ColumnInfo(name = "run_deletion_count") val runDeletionCount: Long,
	@ColumnInfo(name = "run_deletion_text_bytes") val runDeletionTextBytes: Long,
) {
	val totalTextBytes: Long
		get() = Math.addExact(
			Math.addExact(retainedMarkerTextBytes, identityFenceTextBytes),
			Math.addExact(entryDeletionTextBytes, runDeletionTextBytes),
		)
}

data class ImportedPressureHistoryCandidate(
	val identity: String,
	@ColumnInfo(name = "import_revision") val importRevision: Long,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
	@ColumnInfo(name = "candidate_state") val candidateState: String,
)

data class ImportedPressureRunIdentityOwner(
	val identity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
)

data class ImportedPressureWindowIdentityOwner(
	val identity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "run_identity") val runIdentity: String,
)
