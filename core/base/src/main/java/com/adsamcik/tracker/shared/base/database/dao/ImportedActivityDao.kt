package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.adsamcik.tracker.shared.base.database.ActivityCapturedPortableFormatV1
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityWindowEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityZoneEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity

/** Activity-specific imported-origin primitives. Admission owns all transaction semantics. */
// Keep the ImportedActivityDao SQL contract together; query text accounts for this declaration's size.
@Suppress("LargeClass")
@Dao
abstract class ImportedActivityDao {
	/**
	 * Inserts [ImportedActivityEntryRevisionEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertEntryRevision(value: ImportedActivityEntryRevisionEntity)

	/**
	 * Inserts [ImportedActivityReceiptEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertReceipt(value: ImportedActivityReceiptEntity)

	/**
	 * Inserts [ImportedActivityRunEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertRun(value: ImportedActivityRunEntity)

	/**
	 * Inserts [ImportedActivityZoneEpochEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertZoneEpoch(value: ImportedActivityZoneEpochEntity)

	/**
	 * Inserts [ImportedActivityWindowEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertWindow(value: ImportedActivityWindowEntity)

	/**
	 * Inserts [ImportedActivityWindowEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertWindows(values: List<ImportedActivityWindowEntity>)

	/**
	 * Inserts [ImportedActivityFragmentEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertFragment(value: ImportedActivityFragmentEntity)

	/**
	 * Inserts [ImportedActivityFragmentEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertFragments(values: List<ImportedActivityFragmentEntity>)

	/**
	 * Inserts [ImportedActivityEntryDeletionEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertEntryDeletion(value: ImportedActivityEntryDeletionEntity)

	/**
	 * Inserts [ImportedActivityEntryDeletionReceiptEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertEntryDeletionReceipt(value: ImportedActivityEntryDeletionReceiptEntity)

	/**
	 * Inserts [ImportedActivityRetentionReceiptEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertRetentionReceipts(values: List<ImportedActivityRetentionReceiptEntity>)

	/**
	 * Updates existing [ImportedActivityRetentionReceiptEntity] rows by primary key. Returns the
	 * affected row count.
	 */
	@Update(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun updateRetentionReceipt(value: ImportedActivityRetentionReceiptEntity): Int

	/**
	 * Inserts [ImportedActivityRetainedIdentityEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertRetainedIdentities(values: List<ImportedActivityRetainedIdentityEntity>)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	protected abstract suspend fun insertDeletionGenerationRows(
		values: List<ImportedActivityDeletionGenerationEntity>,
	)

	/**
	 * Records one initial run-deletion fence after requiring generation 1; higher generations must
	 * use the dedicated revision operation.
	 */
	suspend fun insertDeletionGeneration(value: ImportedActivityDeletionGenerationEntity) {
		insertDeletionGenerations(listOf(value))
	}

	/**
	 * Adds a nonempty bounded set of distinct run fences, each at initial deletion generation 1.
	 */
	suspend fun insertDeletionGenerations(values: List<ImportedActivityDeletionGenerationEntity>) {
		require(values.isNotEmpty())
		require(values.size <= ActivityCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY)
		require(values.distinctBy { it.runIdentity }.size == values.size)
		require(values.all { it.generation == 1L })
		insertDeletionGenerationRows(values)
	}

	/**
	 * Reads `imported_activity_entry_revision` rows. Query conditions include `identity = :identity`,
	 * `import_revision = :revision`. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM imported_activity_entry_revision " +
			"WHERE identity = :identity AND import_revision = :revision",
	)
	abstract suspend fun entryRevision(identity: String, revision: Long):
		ImportedActivityEntryRevisionEntity?

	/**
	 * Reads `imported_activity_entry_revision` rows. Query conditions include `identity = :identity`.
	 * Orders by `import_revision DESC`. Returns at most 1 row. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM imported_activity_entry_revision WHERE identity = :identity " +
			"ORDER BY import_revision DESC LIMIT 1",
	)
	abstract suspend fun latestEntryRevision(identity: String): ImportedActivityEntryRevisionEntity?

	/**
	 * Reads `imported_activity_entry_revision` rows. Query conditions include `identity = :identity`.
	 * Orders by `import_revision DESC`. Returns at most 1 row. Returns null when no row matches.
	 */
	@Query(
		"SELECT identity, import_revision, content_checksum, start_time_ms, end_time_ms, " +
			"received_at_ms, 'LIVE' AS candidate_state " +
			"FROM imported_activity_entry_revision WHERE identity = :identity " +
			"ORDER BY import_revision DESC LIMIT 1",
	)
	abstract suspend fun latestHistoryCandidate(identity: String): ImportedActivityHistoryCandidate?

	/**
	 * Reads `imported_activity_retention_receipt` rows. Query conditions include `entry_identity =
	 * :identity`. Returns null when no row matches.
	 */
	@Query(
		"SELECT entry_identity AS identity, latest_import_revision AS import_revision, " +
			"latest_content_checksum AS content_checksum, start_time_ms, end_time_ms, received_at_ms, " +
			"'RETAINED' AS candidate_state FROM imported_activity_retention_receipt " +
			"WHERE entry_identity = :identity AND NOT EXISTS (" +
			"SELECT 1 FROM imported_activity_entry_deletion AS deletion " +
			"WHERE deletion.entry_identity = imported_activity_retention_receipt.entry_identity)",
	)
	abstract suspend fun retainedHistoryCandidate(identity: String): ImportedActivityHistoryCandidate?

	/** One latest-revision seed per imported logical entry, ordered for product history. */
	@Query(
		"""
		WITH latest_revision AS (
		  SELECT identity, MAX(import_revision) AS import_revision
		  FROM imported_activity_entry_revision
		  GROUP BY identity
		)
		SELECT entry.identity AS identity,
		       entry.import_revision,
		       entry.content_checksum,
		       entry.start_time_ms,
		       entry.end_time_ms,
		       entry.received_at_ms,
		       'LIVE' AS candidate_state
		FROM imported_activity_entry_revision AS entry
		INNER JOIN latest_revision AS latest
		  ON latest.identity = entry.identity
		 AND latest.import_revision = entry.import_revision
		WHERE :beforeStartTimeMs IS NULL
		   OR entry.start_time_ms < :beforeStartTimeMs
		   OR (
		     entry.start_time_ms = :beforeStartTimeMs
		     AND entry.identity < COALESCE(:beforeIdentity, '')
		   )
		UNION ALL
		SELECT retained.entry_identity AS identity,
		       retained.latest_import_revision,
		       retained.latest_content_checksum,
		       retained.start_time_ms,
		       retained.end_time_ms,
		       retained.received_at_ms,
		       'RETAINED' AS candidate_state
		FROM imported_activity_retention_receipt AS retained
		WHERE NOT EXISTS (
		  SELECT 1 FROM imported_activity_entry_deletion AS deletion
		  WHERE deletion.entry_identity = retained.entry_identity
		)
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
	abstract suspend fun recentHistoryCandidatePage(
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
	): List<ImportedActivityHistoryCandidate>

	/** Complete imported candidate source with a cursor that cannot skip live/retained conflicts. */
	@Query(
		"""
		WITH latest_revision AS (
		  SELECT identity, MAX(import_revision) AS import_revision
		  FROM imported_activity_entry_revision
		  GROUP BY identity
		), candidate AS (
		  SELECT entry.identity,
		         entry.import_revision,
		         entry.content_checksum,
		         entry.start_time_ms,
		         entry.end_time_ms,
		         entry.received_at_ms,
		         'LIVE' AS candidate_state
		  FROM imported_activity_entry_revision AS entry
		  INNER JOIN latest_revision AS latest
		    ON latest.identity = entry.identity
		   AND latest.import_revision = entry.import_revision
		  UNION ALL
		  SELECT retained.entry_identity,
		         retained.latest_import_revision,
		         retained.latest_content_checksum,
		         retained.start_time_ms,
		         retained.end_time_ms,
		         retained.received_at_ms,
		         'RETAINED' AS candidate_state
		  FROM imported_activity_retention_receipt AS retained
		  WHERE NOT EXISTS (
		    SELECT 1 FROM imported_activity_entry_deletion AS deletion
		    WHERE deletion.entry_identity = retained.entry_identity
		  )
		)
		SELECT * FROM candidate
		WHERE :beforeStartTimeMs IS NULL
		   OR start_time_ms < :beforeStartTimeMs
		   OR (
		     start_time_ms = :beforeStartTimeMs
		     AND (
		       identity < COALESCE(:beforeIdentity, '')
		       OR (
		         identity = COALESCE(:beforeIdentity, '')
		         AND candidate_state > COALESCE(:beforeCandidateState, '')
		       )
		     )
		   )
		ORDER BY start_time_ms DESC, identity DESC, candidate_state
		LIMIT :limit
		""",
	)
	abstract suspend fun allHistoryCandidatePage(
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
		beforeCandidateState: String?,
	): List<ImportedActivityHistoryCandidate>

	/** Latest imported entries whose complete logical range overlaps an export request. */
	@Query(
		"""
		WITH latest_revision AS (
		  SELECT identity, MAX(import_revision) AS import_revision
		  FROM imported_activity_entry_revision
		  GROUP BY identity
		)
		SELECT entry.identity AS identity,
		       entry.import_revision,
		       entry.content_checksum,
		       entry.start_time_ms,
		       entry.end_time_ms,
		       entry.received_at_ms,
		       'LIVE' AS candidate_state
		FROM imported_activity_entry_revision AS entry
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
		SELECT retained.entry_identity AS identity,
		       retained.latest_import_revision,
		       retained.latest_content_checksum,
		       retained.start_time_ms,
		       retained.end_time_ms,
		       retained.received_at_ms,
		       'RETAINED' AS candidate_state
		FROM imported_activity_retention_receipt AS retained
		WHERE retained.start_time_ms < :toExclusiveMs
		  AND retained.end_time_ms > :fromInclusiveMs
		  AND NOT EXISTS (
		    SELECT 1 FROM imported_activity_entry_deletion AS deletion
		    WHERE deletion.entry_identity = retained.entry_identity
		  )
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
	): List<ImportedActivityHistoryCandidate>

	/** Latest imported entries ordered by their newest physical member tuple before LIMIT. */
	@Query(
		"""
		WITH latest_revision AS (
		  SELECT identity, MAX(import_revision) AS import_revision
		  FROM imported_activity_entry_revision
		  GROUP BY identity
		), newest_run AS (
		  SELECT run.*
		  FROM imported_activity_run AS run
		  INNER JOIN latest_revision AS latest
		    ON latest.identity = run.entry_identity
		   AND latest.import_revision = run.entry_import_revision
		  WHERE NOT EXISTS (
		    SELECT 1
		    FROM imported_activity_run AS newer
		    WHERE newer.entry_identity = run.entry_identity
		      AND newer.entry_import_revision = run.entry_import_revision
		      AND (
		        newer.start_time_ms > run.start_time_ms
		        OR (newer.start_time_ms = run.start_time_ms AND newer.identity > run.identity)
		      )
		  )
		), candidate AS (
		  SELECT entry.identity,
		         entry.import_revision,
		         entry.content_checksum,
		         entry.start_time_ms,
		         entry.end_time_ms,
		         entry.received_at_ms,
		         'LIVE' AS candidate_state,
		         COALESCE(newest.start_time_ms, entry.start_time_ms) AS recency_start_time_ms,
		         COALESCE(newest.identity, entry.identity) AS recency_member_identity,
		         NULL AS temporal_authority_state
		  FROM imported_activity_entry_revision AS entry
		  INNER JOIN latest_revision AS latest
		    ON latest.identity = entry.identity
		   AND latest.import_revision = entry.import_revision
		  LEFT JOIN newest_run AS newest
		    ON newest.entry_identity = entry.identity
		   AND newest.entry_import_revision = entry.import_revision
		  UNION ALL
		  SELECT retained.entry_identity,
		         retained.latest_import_revision,
		         retained.latest_content_checksum,
		         retained.start_time_ms,
		         retained.end_time_ms,
		         retained.received_at_ms,
		         'RETAINED' AS candidate_state,
		         COALESCE(retained.latest_member_start_time_ms, retained.start_time_ms),
		         COALESCE(retained.latest_member_identity, retained.entry_identity),
		         retained.temporal_authority_state
		  FROM imported_activity_retention_receipt AS retained
		  WHERE NOT EXISTS (
		      SELECT 1 FROM imported_activity_entry_deletion AS deletion
		      WHERE deletion.entry_identity = retained.entry_identity
		    )
		)
		SELECT * FROM candidate
		WHERE :beforeRecencyStartTimeMs IS NULL
		   OR recency_start_time_ms < :beforeRecencyStartTimeMs
		   OR (
		     recency_start_time_ms = :beforeRecencyStartTimeMs
		     AND recency_member_identity < COALESCE(:beforeRecencyMemberIdentity, '')
		   )
		ORDER BY recency_start_time_ms DESC, recency_member_identity DESC
		LIMIT :limit
		""",
	)
	abstract suspend fun orderedRecentCandidatePage(
		limit: Int,
		beforeRecencyStartTimeMs: Long?,
		beforeRecencyMemberIdentity: String?,
	): List<ImportedActivityOrderedHistoryCandidate>

	/** Wall-range candidates are filtered before LIMIT and ordered by newest physical member. */
	@Query(
		"""
		WITH latest_revision AS (
		  SELECT identity, MAX(import_revision) AS import_revision
		  FROM imported_activity_entry_revision
		  GROUP BY identity
		), newest_run AS (
		  SELECT run.*
		  FROM imported_activity_run AS run
		  INNER JOIN latest_revision AS latest
		    ON latest.identity = run.entry_identity
		   AND latest.import_revision = run.entry_import_revision
		  WHERE NOT EXISTS (
		    SELECT 1
		    FROM imported_activity_run AS newer
		    WHERE newer.entry_identity = run.entry_identity
		      AND newer.entry_import_revision = run.entry_import_revision
		      AND (
		        newer.start_time_ms > run.start_time_ms
		        OR (newer.start_time_ms = run.start_time_ms AND newer.identity > run.identity)
		      )
		  )
		), candidate AS (
		  SELECT entry.identity,
		         entry.import_revision,
		         entry.content_checksum,
		         entry.start_time_ms,
		         entry.end_time_ms,
		         entry.received_at_ms,
		         'LIVE' AS candidate_state,
		         COALESCE(newest.start_time_ms, entry.start_time_ms) AS recency_start_time_ms,
		         COALESCE(newest.identity, entry.identity) AS recency_member_identity,
		         NULL AS temporal_authority_state
		  FROM imported_activity_entry_revision AS entry
		  INNER JOIN latest_revision AS latest
		    ON latest.identity = entry.identity
		   AND latest.import_revision = entry.import_revision
		  LEFT JOIN newest_run AS newest
		    ON newest.entry_identity = entry.identity
		   AND newest.entry_import_revision = entry.import_revision
		  WHERE entry.start_time_ms < :toExclusiveMs
		    AND entry.end_time_ms > :fromInclusiveMs
		  UNION ALL
		  SELECT retained.entry_identity,
		         retained.latest_import_revision,
		         retained.latest_content_checksum,
		         retained.start_time_ms,
		         retained.end_time_ms,
		         retained.received_at_ms,
		         'RETAINED' AS candidate_state,
		         COALESCE(retained.latest_member_start_time_ms, retained.start_time_ms),
		         COALESCE(retained.latest_member_identity, retained.entry_identity),
		         retained.temporal_authority_state
		  FROM imported_activity_retention_receipt AS retained
		  WHERE retained.start_time_ms < :toExclusiveMs
		    AND retained.end_time_ms > :fromInclusiveMs
		    AND NOT EXISTS (
		      SELECT 1 FROM imported_activity_entry_deletion AS deletion
		      WHERE deletion.entry_identity = retained.entry_identity
		    )
		)
		SELECT * FROM candidate
		WHERE :beforeRecencyStartTimeMs IS NULL
		   OR recency_start_time_ms < :beforeRecencyStartTimeMs
		   OR (
		     recency_start_time_ms = :beforeRecencyStartTimeMs
		     AND recency_member_identity < COALESCE(:beforeRecencyMemberIdentity, '')
		   )
		ORDER BY recency_start_time_ms DESC, recency_member_identity DESC
		LIMIT :limit
		""",
	)
	abstract suspend fun orderedRangeCandidatePage(
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
		beforeRecencyStartTimeMs: Long?,
		beforeRecencyMemberIdentity: String?,
	): List<ImportedActivityOrderedHistoryCandidate>

	/** One-row imported Activity mutation fingerprint for continuation invalidation. */
	@Query(
		"""
		SELECT
		  (SELECT COUNT(*) FROM imported_activity_entry_revision) AS entry_revision_count,
		  (SELECT COUNT(*) FROM imported_activity_receipt) AS receipt_count,
		  (SELECT COUNT(*) FROM imported_activity_run) AS run_count,
		  (SELECT COUNT(*) FROM imported_activity_zone_epoch) AS zone_epoch_count,
		  (SELECT COUNT(*) FROM imported_activity_window) AS window_count,
		  (SELECT COUNT(*) FROM imported_activity_fragment) AS fragment_count,
		  (SELECT COUNT(*) FROM imported_activity_entry_deletion) AS entry_deletion_count,
		  (SELECT COUNT(*) FROM imported_activity_entry_deletion_receipt) AS
		    entry_deletion_receipt_count,
		  (SELECT COUNT(*) FROM imported_activity_deletion_generation) AS
		    deletion_generation_count,
		  (SELECT COUNT(*) FROM imported_activity_retention_receipt) AS retention_receipt_count,
		  (SELECT COUNT(*) FROM imported_activity_retained_identity) AS retained_identity_count
		""",
	)
	abstract suspend fun productRevisionSnapshot(): ImportedActivityProductRevisionSnapshot

	/** Read-only continuation probe; deleted retained shells are intentionally not actionable. */
	@Query(
		"""
		SELECT (
		  EXISTS(SELECT 1 FROM imported_activity_entry_revision LIMIT 1)
		  OR EXISTS(
		    SELECT 1
		    FROM imported_activity_retention_receipt AS retained
		    WHERE NOT EXISTS (
		      SELECT 1
		      FROM imported_activity_entry_deletion AS deletion
		      WHERE deletion.entry_identity = retained.entry_identity
		    )
		    LIMIT 1
		  )
		)
		""",
	)
	abstract suspend fun hasErasableProductCandidate(): Boolean

	/**
	 * Reads one imported Activity entry in revision order with one overflow row beyond the admission
	 * limit.
	 */
	suspend fun entryRevisionsForAdmission(identity: String): List<ImportedActivityEntryRevisionEntity> =
		loadEntryRevisions(identity, MAX_REVISIONS_PER_ENTRY + 1)

	@Query(
		"SELECT * FROM imported_activity_entry_revision WHERE identity = :identity " +
			"ORDER BY import_revision LIMIT :limit",
	)
	protected abstract suspend fun loadEntryRevisions(
		identity: String,
		limit: Int,
	): List<ImportedActivityEntryRevisionEntity>

	/**
	 * Scalar-only preflight for one selected Activity product batch.
	 *
	 * Text totals use SQLite BLOB length so they are exact UTF-8 byte counts. Detail rows are not
	 * materialized by this query.
	 */
	@Query(
		"""
		WITH latest_revision AS (
		  SELECT identity, MAX(import_revision) AS import_revision
		  FROM imported_activity_entry_revision
		  WHERE identity IN (:identities)
		  GROUP BY identity
		)
		SELECT
		  (SELECT COUNT(*) FROM imported_activity_entry_revision
		   WHERE identity IN (:identities)) AS entry_revision_count,
		  (SELECT COUNT(*) FROM imported_activity_receipt
		   WHERE entry_identity IN (:identities)) AS import_receipt_count,
		  (SELECT COUNT(*) FROM imported_activity_run
		   WHERE entry_identity IN (:identities)) AS run_count,
		  (SELECT COUNT(*) FROM imported_activity_zone_epoch
		   WHERE entry_identity IN (:identities)) AS zone_epoch_count,
		  (SELECT COUNT(*) FROM imported_activity_window
		   WHERE entry_identity IN (:identities)) AS window_count,
		  (SELECT COUNT(*) FROM imported_activity_fragment
		   WHERE entry_identity IN (:identities)) AS fragment_count,
		  (SELECT COUNT(*) FROM imported_activity_retention_receipt
		   WHERE entry_identity IN (:identities)) AS retention_receipt_count,
		  (SELECT COUNT(*) FROM imported_activity_retained_identity
		   WHERE entry_identity IN (:identities)) AS retained_identity_count,
		  (SELECT COALESCE(SUM(structural_zone_range_count), 0)
		   FROM imported_activity_retention_receipt
		   WHERE entry_identity IN (:identities)) AS retained_structural_range_count,
		  (SELECT COUNT(*) FROM imported_activity_entry_deletion
		   WHERE entry_identity IN (:identities)) AS entry_deletion_count,
		  (SELECT COUNT(*) FROM imported_activity_entry_deletion_receipt
		   WHERE entry_identity IN (:identities)) AS entry_deletion_receipt_count,
		  (SELECT COUNT(*) FROM imported_activity_deletion_generation
		   WHERE run_identity IN (
		     SELECT identity FROM imported_activity_run WHERE entry_identity IN (:identities)
		     UNION
		     SELECT protected_identity FROM imported_activity_retained_identity
		     WHERE entry_identity IN (:identities) AND identity_kind = 'RUN'
		   )) AS run_deletion_count,
		  (SELECT COUNT(*) FROM source_deletion_fence
		   WHERE source_kind = :activitySourceKind
		     AND purpose = :sessionCapturePurpose
		     AND scope_kind = :logicalRunScopeKind
		     AND scope_identity_digest IN (
		       SELECT deletion_scope_digest FROM imported_activity_run
		       WHERE entry_identity IN (:identities)
		       UNION
		       SELECT protected_identity FROM imported_activity_retained_identity
		       WHERE entry_identity IN (:identities) AND identity_kind = 'DELETION_SCOPE'
		     )) AS source_fence_count,
		  (SELECT COUNT(*) FROM latest_revision) AS latest_entry_count,
		  (SELECT COUNT(*) FROM imported_activity_run AS run
		   INNER JOIN latest_revision AS latest
		     ON latest.identity = run.entry_identity
		    AND latest.import_revision = run.entry_import_revision) AS latest_run_count,
		  (SELECT COUNT(*) FROM imported_activity_zone_epoch AS zone
		   INNER JOIN latest_revision AS latest
		     ON latest.identity = zone.entry_identity
		    AND latest.import_revision = zone.entry_import_revision) AS latest_zone_epoch_count,
		  (SELECT COUNT(*) FROM imported_activity_window AS window_row
		   INNER JOIN latest_revision AS latest
		     ON latest.identity = window_row.entry_identity
		    AND latest.import_revision = window_row.entry_import_revision) AS latest_window_count,
		  (SELECT COUNT(*) FROM imported_activity_fragment AS fragment
		   INNER JOIN latest_revision AS latest
		     ON latest.identity = fragment.entry_identity
		    AND latest.import_revision = fragment.entry_import_revision) AS latest_fragment_count,
		  (
		    SELECT COALESCE(SUM(
		      length(CAST(identity AS BLOB)) +
		      length(CAST(content_checksum AS BLOB)) +
		      length(CAST(source_format AS BLOB)) +
		      length(CAST(session_mode AS BLOB)) +
		      length(CAST(import_job_id AS BLOB)) +
		      length(CAST(import_entry_key AS BLOB)) +
		      length(CAST(import_source_name AS BLOB))
		    ), 0) FROM imported_activity_entry_revision WHERE identity IN (:identities)
		  ) + (
		    SELECT COALESCE(SUM(
		      length(CAST(import_job_id AS BLOB)) +
		      length(CAST(import_entry_key AS BLOB)) +
		      length(CAST(import_source_name AS BLOB)) +
		      length(CAST(entry_identity AS BLOB)) +
		      length(CAST(entry_content_checksum AS BLOB))
		    ), 0) FROM imported_activity_receipt WHERE entry_identity IN (:identities)
		  ) + (
		    SELECT COALESCE(SUM(
		      length(CAST(entry_identity AS BLOB)) +
		      length(CAST(identity AS BLOB)) +
		      length(CAST(deletion_scope_digest AS BLOB)) +
		      length(CAST(content_checksum AS BLOB)) +
		      length(CAST(capture_coverage AS BLOB))
		    ), 0) FROM imported_activity_run WHERE entry_identity IN (:identities)
		  ) + (
		    SELECT COALESCE(SUM(
		      length(CAST(entry_identity AS BLOB)) +
		      length(CAST(run_identity AS BLOB)) +
		      length(CAST(zone_id AS BLOB))
		    ), 0) FROM imported_activity_zone_epoch WHERE entry_identity IN (:identities)
		  ) + (
		    SELECT COALESCE(SUM(
		      length(CAST(entry_identity AS BLOB)) +
		      length(CAST(run_identity AS BLOB)) +
		      length(CAST(identity AS BLOB)) +
		      length(CAST(content_checksum AS BLOB)) +
		      length(CAST(stored_zone_id AS BLOB)) +
		      length(CAST(coverage AS BLOB))
		    ), 0) FROM imported_activity_window WHERE entry_identity IN (:identities)
		  ) + (
		    SELECT COALESCE(SUM(
		      length(CAST(entry_identity AS BLOB)) +
		      length(CAST(run_identity AS BLOB)) +
		      length(CAST(window_identity AS BLOB)) +
		      length(CAST(fragment_kind AS BLOB)) +
		      length(CAST(COALESCE(gap_reason, '') AS BLOB)) +
		      length(CAST(COALESCE(activity, '') AS BLOB)) +
		      length(CAST(COALESCE(mechanism, '') AS BLOB)) +
		      length(CAST(COALESCE(refined_transition_activity, '') AS BLOB)) +
		      length(CAST(COALESCE(confidence_kind, '') AS BLOB)) +
		      length(CAST(COALESCE(start_boundary_kind, '') AS BLOB)) +
		      length(CAST(COALESCE(end_boundary_kind, '') AS BLOB)) +
		      length(CAST(COALESCE(wall_time_continuity, '') AS BLOB))
		    ), 0) FROM imported_activity_fragment WHERE entry_identity IN (:identities)
		  ) + (
		    SELECT COALESCE(SUM(
		      length(CAST(entry_identity AS BLOB)) +
		      length(CAST(latest_content_checksum AS BLOB)) +
		      length(CAST(temporal_authority_state AS BLOB)) +
		      length(CAST(COALESCE(latest_member_identity, '') AS BLOB)) +
		      length(CAST(structural_zone_ranges_payload AS BLOB)) +
		      length(CAST(run_deletion_set_checksum AS BLOB)) +
		      length(CAST(source_fence_set_checksum AS BLOB)) +
		      length(CAST(protected_identity_set_checksum AS BLOB)) +
		      length(CAST(lineage_authority_checksum AS BLOB)) +
		      length(CAST(effect_checksum AS BLOB))
		    ), 0) FROM imported_activity_retention_receipt
		    WHERE entry_identity IN (:identities)
		  ) + (
		    SELECT COALESCE(SUM(
		      length(CAST(protected_identity AS BLOB)) +
		      length(CAST(entry_identity AS BLOB)) +
		      length(CAST(identity_kind AS BLOB))
		    ), 0) FROM imported_activity_retained_identity
		    WHERE entry_identity IN (:identities)
		  ) + (
		    SELECT COALESCE(SUM(
		      length(CAST(entry_identity AS BLOB)) +
		      length(CAST(effect_checksum AS BLOB))
		    ), 0) FROM imported_activity_entry_deletion
		    WHERE entry_identity IN (:identities)
		  ) + (
		    SELECT COALESCE(SUM(
		      length(CAST(entry_identity AS BLOB)) +
		      length(CAST(deleted_content_checksum AS BLOB)) +
		      length(CAST(run_scope_set_checksum AS BLOB)) +
		      length(CAST(window_identity_set_checksum AS BLOB)) +
		      length(CAST(run_deletion_set_checksum AS BLOB)) +
		      length(CAST(source_fence_set_checksum AS BLOB)) +
		      length(CAST(effect_checksum AS BLOB))
		    ), 0) FROM imported_activity_entry_deletion_receipt
		    WHERE entry_identity IN (:identities)
		  ) + (
		    SELECT COALESCE(SUM(
		      length(CAST(run_identity AS BLOB)) +
		      length(CAST(effect_checksum AS BLOB))
		    ), 0) FROM imported_activity_deletion_generation
		    WHERE run_identity IN (
		      SELECT identity FROM imported_activity_run WHERE entry_identity IN (:identities)
		      UNION
		      SELECT protected_identity FROM imported_activity_retained_identity
		      WHERE entry_identity IN (:identities) AND identity_kind = 'RUN'
		    )
		  ) + (
		    SELECT COALESCE(SUM(
		      length(CAST(purpose AS BLOB)) +
		      length(CAST(scope_kind AS BLOB)) +
		      length(CAST(scope_identity_digest AS BLOB)) +
		      length(CAST(effect_checksum AS BLOB))
		    ), 0) FROM source_deletion_fence
		    WHERE source_kind = :activitySourceKind
		      AND purpose = :sessionCapturePurpose
		      AND scope_kind = :logicalRunScopeKind
		      AND scope_identity_digest IN (
		        SELECT deletion_scope_digest FROM imported_activity_run
		        WHERE entry_identity IN (:identities)
		        UNION
		        SELECT protected_identity FROM imported_activity_retained_identity
		        WHERE entry_identity IN (:identities) AND identity_kind = 'DELETION_SCOPE'
		      )
		  ) AS source_text_bytes
		""",
	)
	abstract suspend fun productBatchPreflight(
		identities: List<String>,
		activitySourceKind: Int,
		sessionCapturePurpose: String,
		logicalRunScopeKind: String,
	): ImportedActivityProductBatchPreflight

	/**
	 * Reads revisions for validated, distinct Activity entry IDs, ordered by entry and revision with
	 * an overflow row per batch.
	 */
	suspend fun entryRevisionsForHistory(
		identities: List<String>,
	): List<ImportedActivityEntryRevisionEntity> = loadHistoryEntryRevisions(
		checkedHistoryIdentities(identities),
		historyLimit(identities.size, MAX_REVISIONS_PER_ENTRY),
	)

	/**
	 * Reads Activity entry revisions after validating IDs; caps the result at the requested history
	 * budget plus an overflow row.
	 */
	suspend fun entryRevisionsForBoundedHistory(
		identities: List<String>,
		maximumRows: Int,
	): List<ImportedActivityEntryRevisionEntity> = loadHistoryEntryRevisions(
		checkedHistoryIdentities(identities),
		boundedHistoryLimit(identities.size, MAX_REVISIONS_PER_ENTRY, maximumRows),
	)

	@Query(
		"SELECT * FROM imported_activity_entry_revision WHERE identity IN (:identities) " +
			"ORDER BY identity, import_revision LIMIT :limit",
	)
	protected abstract suspend fun loadHistoryEntryRevisions(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityEntryRevisionEntity>

	/**
	 * Reads `imported_activity_receipt` rows. Query conditions include `import_job_id = :jobId`,
	 * `import_entry_key = :entryKey`. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM imported_activity_receipt " +
			"WHERE import_job_id = :jobId AND import_entry_key = :entryKey",
	)
	abstract suspend fun receipt(jobId: String, entryKey: String): ImportedActivityReceiptEntity?

	/**
	 * Reads import receipts for one entry in revision/job/key order, including one overflow row to
	 * detect excessive lineage.
	 */
	suspend fun receiptsForAdmission(identity: String): List<ImportedActivityReceiptEntity> =
		loadReceipts(identity, MAX_RECEIPTS_PER_ENTRY + 1)

	@Query(
		"SELECT * FROM imported_activity_receipt WHERE entry_identity = :identity " +
			"ORDER BY entry_import_revision, import_job_id, import_entry_key LIMIT :limit",
	)
	protected abstract suspend fun loadReceipts(
		identity: String,
		limit: Int,
	): List<ImportedActivityReceiptEntity>

	/**
	 * Reads receipts for validated distinct Activity entries in entry/revision/job/key order, with
	 * one overflow row beyond the combined per-entry limits.
	 */
	suspend fun receiptsForHistory(identities: List<String>): List<ImportedActivityReceiptEntity> =
		loadHistoryReceipts(
			checkedHistoryIdentities(identities),
			historyLimit(identities.size, MAX_RECEIPTS_PER_ENTRY),
		)

	/**
	 * Reads receipts for validated Activity entry IDs in entry/revision/job/key order within the
	 * requested history budget.
	 */
	suspend fun receiptsForBoundedHistory(
		identities: List<String>,
		maximumRows: Int,
	): List<ImportedActivityReceiptEntity> = loadHistoryReceipts(
		checkedHistoryIdentities(identities),
		boundedHistoryLimit(identities.size, MAX_RECEIPTS_PER_ENTRY, maximumRows),
	)

	@Query(
		"SELECT * FROM imported_activity_receipt WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, import_job_id, import_entry_key LIMIT :limit",
	)
	protected abstract suspend fun loadHistoryReceipts(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityReceiptEntity>

	/**
	 * Reads all saved runs for one Activity entry across revisions, ordered by revision and measured
	 * UTC bounds, with an overflow row beyond the lineage limit.
	 */
	suspend fun allRunsForAdmission(identity: String): List<ImportedActivityRunEntity> =
		loadAllRuns(identity, MAX_TOTAL_RUNS_PER_LINEAGE + 1)

	@Query(
		"SELECT * FROM imported_activity_run WHERE entry_identity = :identity " +
			"ORDER BY entry_import_revision, start_time_ms, end_time_ms, identity LIMIT :limit",
	)
	protected abstract suspend fun loadAllRuns(
		identity: String,
		limit: Int,
	): List<ImportedActivityRunEntity>

	/**
	 * Reads runs for validated distinct Activity entries in entry/revision/measured-bound order, with
	 * one overflow row beyond the combined lineage limits.
	 */
	suspend fun runsForHistory(identities: List<String>): List<ImportedActivityRunEntity> =
		loadHistoryRuns(
			checkedHistoryIdentities(identities),
			historyLimit(identities.size, MAX_TOTAL_RUNS_PER_LINEAGE),
		)

	/**
	 * Reads saved Activity runs for validated entry IDs within the history budget, ordered by entry,
	 * revision and measured bounds.
	 */
	suspend fun runsForBoundedHistory(
		identities: List<String>,
		maximumRows: Int,
	): List<ImportedActivityRunEntity> = loadHistoryRuns(
		checkedHistoryIdentities(identities),
		boundedHistoryLimit(identities.size, MAX_TOTAL_RUNS_PER_LINEAGE, maximumRows),
	)

	@Query(
		"SELECT * FROM imported_activity_run WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, start_time_ms, end_time_ms, identity LIMIT :limit",
	)
	protected abstract suspend fun loadHistoryRuns(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityRunEntity>

	/**
	 * Reads every clock/zone epoch for one Activity entry in revision/run/ordinal order, including
	 * one overflow row beyond the lineage limit.
	 */
	suspend fun allZoneEpochsForAdmission(identity: String): List<ImportedActivityZoneEpochEntity> =
		loadAllZoneEpochs(identity, MAX_TOTAL_ZONE_EPOCHS_PER_LINEAGE + 1)

	@Query(
		"SELECT * FROM imported_activity_zone_epoch WHERE entry_identity = :identity " +
			"ORDER BY entry_import_revision, run_identity, ordinal LIMIT :limit",
	)
	protected abstract suspend fun loadAllZoneEpochs(
		identity: String,
		limit: Int,
	): List<ImportedActivityZoneEpochEntity>

	/**
	 * Reads clock/zone epochs for validated distinct Activity entries in entry/revision/run/ordinal
	 * order with an overflow row beyond the combined limits.
	 */
	suspend fun zoneEpochsForHistory(identities: List<String>): List<ImportedActivityZoneEpochEntity> =
		loadHistoryZoneEpochs(
			checkedHistoryIdentities(identities),
			historyLimit(identities.size, MAX_TOTAL_ZONE_EPOCHS_PER_LINEAGE),
		)

	/**
	 * Reads saved Activity clock/zone epochs for validated entry IDs, ordered by entry, revision, run
	 * and ordinal within the history budget.
	 */
	suspend fun zoneEpochsForBoundedHistory(
		identities: List<String>,
		maximumRows: Int,
	): List<ImportedActivityZoneEpochEntity> = loadHistoryZoneEpochs(
		checkedHistoryIdentities(identities),
		boundedHistoryLimit(identities.size, MAX_TOTAL_ZONE_EPOCHS_PER_LINEAGE, maximumRows),
	)

	@Query(
		"SELECT * FROM imported_activity_zone_epoch WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, run_identity, ordinal LIMIT :limit",
	)
	protected abstract suspend fun loadHistoryZoneEpochs(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityZoneEpochEntity>

	/**
	 * Reads windows across one Activity entry lineage in revision/run/original-offset order with one
	 * overflow row beyond the lineage limit.
	 */
	suspend fun allWindowsForAdmission(identity: String): List<ImportedActivityWindowEntity> =
		loadAllWindows(identity, MAX_TOTAL_WINDOWS_PER_LINEAGE + 1)

	@Query(
		"SELECT * FROM imported_activity_window WHERE entry_identity = :identity " +
			"ORDER BY entry_import_revision, run_identity, start_offset_nanos, end_offset_nanos, " +
			"identity LIMIT :limit",
	)
	protected abstract suspend fun loadAllWindows(
		identity: String,
		limit: Int,
	): List<ImportedActivityWindowEntity>

	/**
	 * Reads windows for validated distinct Activity entries in entry/revision/run/original-offset
	 * order with one overflow row beyond the combined limits.
	 */
	suspend fun windowsForHistory(identities: List<String>): List<ImportedActivityWindowEntity> =
		loadHistoryWindows(
			checkedHistoryIdentities(identities),
			historyLimit(identities.size, MAX_TOTAL_WINDOWS_PER_LINEAGE),
		)

	/**
	 * Reads Activity windows for validated entry IDs within the history budget; preserves revision,
	 * run and window ordering.
	 */
	suspend fun windowsForBoundedHistory(
		identities: List<String>,
		maximumRows: Int,
	): List<ImportedActivityWindowEntity> = loadHistoryWindows(
		checkedHistoryIdentities(identities),
		boundedHistoryLimit(identities.size, MAX_TOTAL_WINDOWS_PER_LINEAGE, maximumRows),
	)

	@Query(
		"SELECT * FROM imported_activity_window WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, run_identity, start_offset_nanos, " +
			"end_offset_nanos, identity LIMIT :limit",
	)
	protected abstract suspend fun loadHistoryWindows(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityWindowEntity>

	/**
	 * Reads fragments across one Activity entry lineage in revision/run/window/ordinal order with one
	 * overflow row beyond the lineage limit.
	 */
	suspend fun allFragmentsForAdmission(identity: String): List<ImportedActivityFragmentEntity> =
		loadAllFragments(identity, MAX_TOTAL_FRAGMENTS_PER_LINEAGE + 1)

	@Query(
		"SELECT * FROM imported_activity_fragment WHERE entry_identity = :identity " +
			"ORDER BY entry_import_revision, run_identity, window_identity, ordinal LIMIT :limit",
	)
	protected abstract suspend fun loadAllFragments(
		identity: String,
		limit: Int,
	): List<ImportedActivityFragmentEntity>

	/**
	 * Reads fragments for validated distinct Activity entries in entry/revision/run/window/ordinal
	 * order with one overflow row beyond the combined limits.
	 */
	suspend fun fragmentsForHistory(identities: List<String>): List<ImportedActivityFragmentEntity> =
		loadHistoryFragments(
			checkedHistoryIdentities(identities),
			historyLimit(identities.size, MAX_TOTAL_FRAGMENTS_PER_LINEAGE),
		)

	/**
	 * Reads Activity fragments for validated entry IDs within the history budget, retaining their
	 * original run/window membership.
	 */
	suspend fun fragmentsForBoundedHistory(
		identities: List<String>,
		maximumRows: Int,
	): List<ImportedActivityFragmentEntity> = loadHistoryFragments(
		checkedHistoryIdentities(identities),
		boundedHistoryLimit(identities.size, MAX_TOTAL_FRAGMENTS_PER_LINEAGE, maximumRows),
	)

	@Query(
		"SELECT * FROM imported_activity_fragment WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, run_identity, window_identity, ordinal " +
			"LIMIT :limit",
	)
	protected abstract suspend fun loadHistoryFragments(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityFragmentEntity>

	/**
	 * Reads distinct `identity` values from `imported_activity_entry_revision`. Query conditions
	 * include `identity IN (:identities)`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT DISTINCT identity FROM imported_activity_entry_revision " +
			"WHERE identity IN (:identities) LIMIT :limit",
	)
	abstract suspend fun existingEntryIdentities(identities: List<String>, limit: Int): List<String>

	/**
	 * Reads `imported_activity_run` rows. Query conditions include `identity IN (:identities)`.
	 * Returns at most `limit` rows.
	 */
	@Query(
		"SELECT DISTINCT identity, entry_identity, deletion_scope_digest FROM imported_activity_run " +
			"WHERE identity IN (:identities) LIMIT :limit",
	)
	abstract suspend fun existingRunIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityRunIdentityOwner>

	/**
	 * Reads `imported_activity_run` rows. Query conditions include `deletion_scope_digest IN
	 * (:digests)`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT DISTINCT deletion_scope_digest, identity, entry_identity FROM imported_activity_run " +
			"WHERE deletion_scope_digest IN (:digests) LIMIT :limit",
	)
	abstract suspend fun existingRunScopeOwners(
		digests: List<String>,
		limit: Int,
	): List<ImportedActivityRunScopeOwner>

	/**
	 * Reads `imported_activity_window` rows. Query conditions include `identity IN (:identities)`.
	 * Returns at most `limit` rows.
	 */
	@Query(
		"SELECT DISTINCT identity, entry_identity, run_identity FROM imported_activity_window " +
			"WHERE identity IN (:identities) LIMIT :limit",
	)
	abstract suspend fun existingWindowIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityWindowIdentityOwner>

	@Query(
		"""
		SELECT owner_kind, protected_identity, source_kind, purpose, scope_kind
		FROM (
			SELECT 'ENTRY_IDENTITY' AS owner_kind,
			       identity AS protected_identity,
			       CAST(NULL AS INTEGER) AS source_kind,
			       CAST(NULL AS TEXT) AS purpose,
			       CAST(NULL AS TEXT) AS scope_kind
			FROM imported_activity_entry_revision
			UNION ALL
			SELECT 'RECEIPT_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
			FROM imported_activity_receipt
			UNION ALL
			SELECT 'RETENTION_RECEIPT_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
			FROM imported_activity_retention_receipt
			UNION ALL
			SELECT 'RETAINED_IDENTITY', protected_identity, NULL, NULL, NULL
			FROM imported_activity_retained_identity
			UNION ALL
			SELECT 'RETAINED_IDENTITY_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
			FROM imported_activity_retained_identity
			UNION ALL
			SELECT 'RUN_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
			FROM imported_activity_run
			UNION ALL
			SELECT 'RUN_IDENTITY', identity, NULL, NULL, NULL
			FROM imported_activity_run
			UNION ALL
			SELECT 'RUN_SCOPE_OWNER', deletion_scope_digest, NULL, NULL, NULL
			FROM imported_activity_run
			UNION ALL
			SELECT 'ZONE_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
			FROM imported_activity_zone_epoch
			UNION ALL
			SELECT 'ZONE_RUN_OWNER', run_identity, NULL, NULL, NULL
			FROM imported_activity_zone_epoch
			UNION ALL
			SELECT 'WINDOW_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
			FROM imported_activity_window
			UNION ALL
			SELECT 'WINDOW_RUN_OWNER', run_identity, NULL, NULL, NULL
			FROM imported_activity_window
			UNION ALL
			SELECT 'WINDOW_IDENTITY', identity, NULL, NULL, NULL
			FROM imported_activity_window
			UNION ALL
			SELECT 'FRAGMENT_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
			FROM imported_activity_fragment
			UNION ALL
			SELECT 'FRAGMENT_RUN_OWNER', run_identity, NULL, NULL, NULL
			FROM imported_activity_fragment
			UNION ALL
			SELECT 'FRAGMENT_WINDOW_OWNER', window_identity, NULL, NULL, NULL
			FROM imported_activity_fragment
			UNION ALL
			SELECT 'ENTRY_DELETION', entry_identity, NULL, NULL, NULL
			FROM imported_activity_entry_deletion
			UNION ALL
			SELECT 'ENTRY_DELETION_RECEIPT', entry_identity, NULL, NULL, NULL
			FROM imported_activity_entry_deletion_receipt
			UNION ALL
			SELECT 'RUN_DELETION', run_identity, NULL, NULL, NULL
			FROM imported_activity_deletion_generation
			UNION ALL
			SELECT 'SOURCE_DELETION_SCOPE', scope_identity_digest, source_kind, purpose, scope_kind
			FROM source_deletion_fence
		) AS owner_facts
		WHERE protected_identity IN (:identities)
		ORDER BY owner_kind, protected_identity, source_kind, purpose, scope_kind
		LIMIT :limit
		""",
	)
	protected abstract suspend fun loadProtectedIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityProtectedIdentityOwner>

	/**
	 * Reads identity-owner candidates after requiring a nonempty, distinct batch of at most 400
	 * identities and a positive result limit no larger than the batch plus two.
	 */
	suspend fun protectedIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityProtectedIdentityOwner> {
		require(identities.size in 1..PROTECTED_IDENTITY_AUDIT_BATCH_SIZE)
		require(identities.distinct().size == identities.size)
		require(limit in 1..(identities.size + 2))
		return loadProtectedIdentityOwners(identities, limit)
	}

	/**
	 * Counts rows in `imported_activity_entry_revision`. Query conditions include `protected_identity
	 * IN (:identities)`.
	 */
	@Query(
		"""
		SELECT COUNT(*) AS result_count,
		       COALESCE(SUM(
		         length(CAST(owner_kind AS BLOB)) +
		         length(CAST(protected_identity AS BLOB)) +
		         length(CAST(COALESCE(purpose, '') AS BLOB)) +
		         length(CAST(COALESCE(scope_kind, '') AS BLOB))
		       ), 0) AS result_text_bytes
		FROM (
		  SELECT owner_kind, protected_identity, source_kind, purpose, scope_kind, COUNT(*) AS owner_count
		  FROM (
		    SELECT 'ENTRY_IDENTITY' AS owner_kind, identity AS protected_identity,
		           CAST(NULL AS INTEGER) AS source_kind, CAST(NULL AS TEXT) AS purpose,
		           CAST(NULL AS TEXT) AS scope_kind
		    FROM imported_activity_entry_revision
		    UNION ALL SELECT 'RECEIPT_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
		    FROM imported_activity_receipt
		    UNION ALL SELECT 'RUN_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
		    FROM imported_activity_run
		    UNION ALL SELECT 'RUN_IDENTITY', identity, NULL, NULL, NULL
		    FROM imported_activity_run
		    UNION ALL SELECT 'RUN_SCOPE_OWNER', deletion_scope_digest, NULL, NULL, NULL
		    FROM imported_activity_run
		    UNION ALL SELECT 'ZONE_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
		    FROM imported_activity_zone_epoch
		    UNION ALL SELECT 'ZONE_RUN_OWNER', run_identity, NULL, NULL, NULL
		    FROM imported_activity_zone_epoch
		    UNION ALL SELECT 'WINDOW_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
		    FROM imported_activity_window
		    UNION ALL SELECT 'WINDOW_RUN_OWNER', run_identity, NULL, NULL, NULL
		    FROM imported_activity_window
		    UNION ALL SELECT 'WINDOW_IDENTITY', identity, NULL, NULL, NULL
		    FROM imported_activity_window
		    UNION ALL SELECT 'FRAGMENT_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
		    FROM imported_activity_fragment
		    UNION ALL SELECT 'FRAGMENT_RUN_OWNER', run_identity, NULL, NULL, NULL
		    FROM imported_activity_fragment
		    UNION ALL SELECT 'FRAGMENT_WINDOW_OWNER', window_identity, NULL, NULL, NULL
		    FROM imported_activity_fragment
		    UNION ALL SELECT 'ENTRY_DELETION', entry_identity, NULL, NULL, NULL
		    FROM imported_activity_entry_deletion
		    UNION ALL SELECT 'ENTRY_DELETION_RECEIPT', entry_identity, NULL, NULL, NULL
		    FROM imported_activity_entry_deletion_receipt
		    UNION ALL SELECT 'RUN_DELETION', run_identity, NULL, NULL, NULL
		    FROM imported_activity_deletion_generation
		    UNION ALL SELECT 'SOURCE_DELETION_SCOPE', scope_identity_digest,
		      source_kind, purpose, scope_kind
		    FROM source_deletion_fence
		    UNION ALL SELECT 'RETENTION_RECEIPT_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
		    FROM imported_activity_retention_receipt
		    UNION ALL SELECT 'RETAINED_IDENTITY', protected_identity, NULL, NULL, NULL
		    FROM imported_activity_retained_identity
		    UNION ALL SELECT 'RETAINED_IDENTITY_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
		    FROM imported_activity_retained_identity
		  ) AS owner_facts
		  WHERE protected_identity IN (:identities)
		  GROUP BY owner_kind, protected_identity, source_kind, purpose, scope_kind
		) AS grouped_owner_facts
		""",
	)
	abstract suspend fun protectedIdentityOwnerCountPreflight(
		identities: List<String>,
	): ImportedActivityOwnerAuditPreflight

	/**
	 * Reads `imported_activity_retention_receipt` rows. Query conditions include `entry_identity =
	 * :identity`. Returns null when no row matches.
	 */
	@Query("SELECT * FROM imported_activity_retention_receipt WHERE entry_identity = :identity")
	abstract suspend fun retentionReceipt(identity: String): ImportedActivityRetentionReceiptEntity?

	/**
	 * Reads `imported_activity_retention_receipt` rows. Query conditions include `entry_identity IN
	 * (:identities)`.
	 */
	@Query("SELECT * FROM imported_activity_retention_receipt WHERE entry_identity IN (:identities)")
	abstract suspend fun retentionReceipts(
		identities: List<String>,
	): List<ImportedActivityRetentionReceiptEntity>

	@Query(
		"SELECT * FROM imported_activity_retention_receipt WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity LIMIT :limit",
	)
	protected abstract suspend fun loadRetentionReceiptsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityRetentionReceiptEntity>

	/**
	 * Reads retention witnesses for validated Activity entries within the requested row budget plus
	 * one overflow row.
	 */
	suspend fun retentionReceiptsForHistory(
		identities: List<String>,
		maximumRows: Int,
	): List<ImportedActivityRetentionReceiptEntity> = loadRetentionReceiptsForHistory(
		checkedHistoryIdentities(identities),
		boundedHistoryLimit(identities.size, 1, maximumRows),
	)

	/**
	 * Reads `imported_activity_retention_receipt` rows. Query conditions include `entry_identity >
	 * :afterIdentity`. Orders by `entry_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_activity_retention_receipt " +
			"WHERE :afterIdentity IS NULL OR entry_identity > :afterIdentity " +
			"ORDER BY entry_identity LIMIT :limit",
	)
	abstract suspend fun retentionReceiptPage(
		afterIdentity: String?,
		limit: Int,
	): List<ImportedActivityRetentionReceiptEntity>

	/**
	 * Reads `imported_activity_retained_identity` rows. Query conditions include `entry_identity IN
	 * (:entryIdentities)`. Orders by `entry_identity, identity_kind, protected_identity`. Returns at
	 * most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_activity_retained_identity WHERE entry_identity IN (:entryIdentities) " +
			"ORDER BY entry_identity, identity_kind, protected_identity LIMIT :limit",
	)
	abstract suspend fun retainedIdentitiesForEntries(
		entryIdentities: List<String>,
		limit: Int,
	): List<ImportedActivityRetainedIdentityEntity>

	/**
	 * Reads `imported_activity_retained_identity` rows. Query conditions include `protected_identity
	 * IN (:identities)`. Orders by `protected_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_activity_retained_identity WHERE protected_identity IN (:identities) " +
			"ORDER BY protected_identity LIMIT :limit",
	)
	abstract suspend fun retainedIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityRetainedIdentityEntity>

	/**
	 * Counts rows in `imported_activity_retention_receipt`.
	 */
	@Query("SELECT COUNT(*) FROM imported_activity_retention_receipt")
	abstract suspend fun retentionReceiptCount(): Long

	/**
	 * Counts rows in `imported_activity_retained_identity`.
	 */
	@Query("SELECT COUNT(*) FROM imported_activity_retained_identity")
	abstract suspend fun retainedIdentityCount(): Long

	/**
	 * Counts rows in `imported_activity_retained_identity`.
	 */
	@Query(
		"SELECT COUNT(*) FROM imported_activity_retained_identity AS marker " +
			"LEFT JOIN imported_activity_retention_receipt AS receipt " +
			"ON receipt.entry_identity = marker.entry_identity " +
			"WHERE receipt.entry_identity IS NULL",
	)
	abstract suspend fun orphanRetainedIdentityCount(): Long

	@Query(
		"""
		SELECT owner_kind, protected_identity, source_kind, purpose, scope_kind, COUNT(*) AS owner_count
		FROM (
			SELECT 'ENTRY_IDENTITY' AS owner_kind, identity AS protected_identity,
			       CAST(NULL AS INTEGER) AS source_kind, CAST(NULL AS TEXT) AS purpose,
			       CAST(NULL AS TEXT) AS scope_kind
			FROM imported_activity_entry_revision
			UNION ALL SELECT 'RECEIPT_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
			FROM imported_activity_receipt
			UNION ALL SELECT 'RUN_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
			FROM imported_activity_run
			UNION ALL SELECT 'RUN_IDENTITY', identity, NULL, NULL, NULL FROM imported_activity_run
			UNION ALL SELECT 'RUN_SCOPE_OWNER', deletion_scope_digest, NULL, NULL, NULL
			FROM imported_activity_run
			UNION ALL SELECT 'ZONE_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
			FROM imported_activity_zone_epoch
			UNION ALL SELECT 'ZONE_RUN_OWNER', run_identity, NULL, NULL, NULL
			FROM imported_activity_zone_epoch
			UNION ALL SELECT 'WINDOW_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
			FROM imported_activity_window
			UNION ALL SELECT 'WINDOW_RUN_OWNER', run_identity, NULL, NULL, NULL
			FROM imported_activity_window
			UNION ALL SELECT 'WINDOW_IDENTITY', identity, NULL, NULL, NULL
			FROM imported_activity_window
			UNION ALL SELECT 'FRAGMENT_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
			FROM imported_activity_fragment
			UNION ALL SELECT 'FRAGMENT_RUN_OWNER', run_identity, NULL, NULL, NULL
			FROM imported_activity_fragment
			UNION ALL SELECT 'FRAGMENT_WINDOW_OWNER', window_identity, NULL, NULL, NULL
			FROM imported_activity_fragment
			UNION ALL SELECT 'ENTRY_DELETION', entry_identity, NULL, NULL, NULL
			FROM imported_activity_entry_deletion
			UNION ALL SELECT 'ENTRY_DELETION_RECEIPT', entry_identity, NULL, NULL, NULL
			FROM imported_activity_entry_deletion_receipt
			UNION ALL SELECT 'RUN_DELETION', run_identity, NULL, NULL, NULL
			FROM imported_activity_deletion_generation
			UNION ALL SELECT 'SOURCE_DELETION_SCOPE', scope_identity_digest, source_kind, purpose, scope_kind
			FROM source_deletion_fence
			UNION ALL SELECT 'RETENTION_RECEIPT_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
			FROM imported_activity_retention_receipt
			UNION ALL SELECT 'RETAINED_IDENTITY', protected_identity, NULL, NULL, NULL
			FROM imported_activity_retained_identity
			UNION ALL SELECT 'RETAINED_IDENTITY_ENTRY_OWNER', entry_identity, NULL, NULL, NULL
			FROM imported_activity_retained_identity
		) AS owner_facts
		WHERE protected_identity IN (:identities)
		GROUP BY owner_kind, protected_identity, source_kind, purpose, scope_kind
		ORDER BY owner_kind, protected_identity, source_kind, purpose, scope_kind
		LIMIT :limit
		""",
	)
	protected abstract suspend fun loadProtectedIdentityOwnerCounts(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityProtectedIdentityOwnerCount>

	/**
	 * Counts each stored identity ownership category after validating a nonempty, distinct batch of
	 * at most 400 identities and a positive result limit.
	 */
	suspend fun protectedIdentityOwnerCounts(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityProtectedIdentityOwnerCount> {
		require(identities.size in 1..PROTECTED_IDENTITY_AUDIT_BATCH_SIZE)
		require(identities.distinct().size == identities.size)
		require(limit > 0)
		return loadProtectedIdentityOwnerCounts(identities, limit)
	}

	/**
	 * Reads `imported_activity_entry_deletion` rows. Query conditions include `entry_identity =
	 * :identity`. Returns null when no row matches.
	 */
	@Query("SELECT * FROM imported_activity_entry_deletion WHERE entry_identity = :identity")
	abstract suspend fun entryDeletion(identity: String): ImportedActivityEntryDeletionEntity?

	/**
	 * Reads `imported_activity_entry_deletion_receipt` rows. Query conditions include `entry_identity
	 * = :identity`. Returns null when no row matches.
	 */
	@Query("SELECT * FROM imported_activity_entry_deletion_receipt WHERE entry_identity = :identity")
	abstract suspend fun entryDeletionReceipt(identity: String): ImportedActivityEntryDeletionReceiptEntity?

	/**
	 * Reads `imported_activity_entry_deletion` rows. Query conditions include `entry_identity IN
	 * (:identities)`.
	 */
	@Query("SELECT * FROM imported_activity_entry_deletion WHERE entry_identity IN (:identities)")
	abstract suspend fun entryDeletions(
		identities: List<String>,
	): List<ImportedActivityEntryDeletionEntity>

	@Query(
		"SELECT * FROM imported_activity_entry_deletion WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity LIMIT :limit",
	)
	protected abstract suspend fun loadEntryDeletionsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityEntryDeletionEntity>

	/**
	 * Reads deletion markers for validated Activity entries within the requested row budget plus one
	 * overflow row.
	 */
	suspend fun entryDeletionsForBoundedHistory(
		identities: List<String>,
		maximumRows: Int,
	): List<ImportedActivityEntryDeletionEntity> = loadEntryDeletionsForHistory(
		checkedHistoryIdentities(identities),
		boundedHistoryLimit(identities.size, 1, maximumRows),
	)

	/**
	 * Reads `imported_activity_entry_deletion_receipt` rows. Query conditions include `entry_identity
	 * IN (:identities)`.
	 */
	@Query("SELECT * FROM imported_activity_entry_deletion_receipt WHERE entry_identity IN (:identities)")
	abstract suspend fun entryDeletionReceipts(
		identities: List<String>,
	): List<ImportedActivityEntryDeletionReceiptEntity>

	@Query(
		"SELECT * FROM imported_activity_entry_deletion_receipt " +
			"WHERE entry_identity IN (:identities) ORDER BY entry_identity LIMIT :limit",
	)
	protected abstract suspend fun loadEntryDeletionReceiptsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityEntryDeletionReceiptEntity>

	/**
	 * Reads deletion receipts for validated Activity entries within the requested row budget plus one
	 * overflow row.
	 */
	suspend fun entryDeletionReceiptsForBoundedHistory(
		identities: List<String>,
		maximumRows: Int,
	): List<ImportedActivityEntryDeletionReceiptEntity> = loadEntryDeletionReceiptsForHistory(
		checkedHistoryIdentities(identities),
		boundedHistoryLimit(identities.size, 1, maximumRows),
	)

	/**
	 * Reads deletion markers for validated Activity entry IDs in bounded parameter chunks.
	 */
	suspend fun entryDeletionsForHistory(
		identities: List<String>,
	): List<ImportedActivityEntryDeletionEntity> = checkedHistoryIdentities(identities)
		.chunked(HISTORY_ID_QUERY_CHUNK_SIZE)
		.flatMap { entryDeletions(it) }

	/**
	 * Reads `imported_activity_deletion_generation` rows. Query conditions include `run_identity IN
	 * (:identities)`.
	 */
	@Query("SELECT * FROM imported_activity_deletion_generation WHERE run_identity IN (:identities)")
	abstract suspend fun deletionGenerations(
		identities: List<String>,
	): List<ImportedActivityDeletionGenerationEntity>

	/**
	 * Reads `imported_activity_deletion_generation` rows. Query conditions include `run_identity IN
	 * (:identities)`. Orders by `run_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_activity_deletion_generation WHERE run_identity IN (:identities) " +
			"ORDER BY run_identity LIMIT :limit",
	)
	abstract suspend fun boundedDeletionGenerations(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityDeletionGenerationEntity>

	/**
	 * Reads `source_deletion_fence` rows. Query conditions include `source_kind =
	 * :activitySourceKind`, `purpose = :sessionCapturePurpose`, `scope_kind = :logicalRunScopeKind`,
	 * `scope_identity_digest IN (:scopeDigests)`. Orders by `scope_identity_digest`. Returns at most
	 * `limit` rows.
	 */
	@Query(
		"""
		SELECT * FROM source_deletion_fence
		WHERE source_kind = :activitySourceKind
		  AND purpose = :sessionCapturePurpose
		  AND scope_kind = :logicalRunScopeKind
		  AND scope_identity_digest IN (:scopeDigests)
		ORDER BY scope_identity_digest
		LIMIT :limit
		""",
	)
	abstract suspend fun boundedActivitySourceFences(
		scopeDigests: List<String>,
		activitySourceKind: Int,
		sessionCapturePurpose: String,
		logicalRunScopeKind: String,
		limit: Int,
	): List<SourceDeletionFenceEntity>

	/**
	 * Reads deletion generations for distinct Activity run IDs in bounded parameter chunks after
	 * checking the overall history limit.
	 */
	suspend fun deletionGenerationsForHistory(
		runIdentities: List<String>,
	): List<ImportedActivityDeletionGenerationEntity> {
		require(runIdentities.size <= MAX_HISTORY_ENTRY_CANDIDATES * MAX_TOTAL_RUNS_PER_LINEAGE)
		return runIdentities.distinct().chunked(HISTORY_ID_QUERY_CHUNK_SIZE)
			.flatMap { deletionGenerations(it) }
	}

	/**
	 * Reads `imported_activity_run` rows. Query conditions include `entry_identity = :identity`,
	 * `entry_import_revision = :revision`. Orders by `start_time_ms, end_time_ms, identity`.
	 */
	@Query(
		"SELECT * FROM imported_activity_run WHERE entry_identity = :identity " +
			"AND entry_import_revision = :revision ORDER BY start_time_ms, end_time_ms, identity",
	)
	abstract suspend fun runs(identity: String, revision: Long): List<ImportedActivityRunEntity>

	/**
	 * Reads `imported_activity_zone_epoch` rows. Query conditions include `entry_identity =
	 * :entryIdentity`, `entry_import_revision = :revision`, `run_identity = :runIdentity`. Orders by
	 * `ordinal`.
	 */
	@Query(
		"SELECT * FROM imported_activity_zone_epoch WHERE entry_identity = :entryIdentity " +
			"AND entry_import_revision = :revision AND run_identity = :runIdentity ORDER BY ordinal",
	)
	abstract suspend fun zoneEpochs(
		entryIdentity: String,
		revision: Long,
		runIdentity: String,
	): List<ImportedActivityZoneEpochEntity>

	/**
	 * Reads `imported_activity_window` rows. Query conditions include `entry_identity =
	 * :entryIdentity`, `entry_import_revision = :revision`, `run_identity = :runIdentity`. Orders by
	 * `start_offset_nanos, end_offset_nanos, identity`.
	 */
	@Query(
		"SELECT * FROM imported_activity_window WHERE entry_identity = :entryIdentity " +
			"AND entry_import_revision = :revision AND run_identity = :runIdentity " +
			"ORDER BY start_offset_nanos, end_offset_nanos, identity",
	)
	abstract suspend fun windows(
		entryIdentity: String,
		revision: Long,
		runIdentity: String,
	): List<ImportedActivityWindowEntity>

	/**
	 * Reads `imported_activity_fragment` rows. Query conditions include `entry_identity =
	 * :entryIdentity`, `entry_import_revision = :revision`, `run_identity = :runIdentity`,
	 * `window_identity = :windowIdentity`. Orders by `ordinal`.
	 */
	@Query(
		"SELECT * FROM imported_activity_fragment WHERE entry_identity = :entryIdentity " +
			"AND entry_import_revision = :revision AND run_identity = :runIdentity " +
			"AND window_identity = :windowIdentity ORDER BY ordinal",
	)
	abstract suspend fun fragments(
		entryIdentity: String,
		revision: Long,
		runIdentity: String,
		windowIdentity: String,
	): List<ImportedActivityFragmentEntity>

	/** Cascades only the selected imported entry's receipts, runs, windows, zones, and fragments. */
	@Query("DELETE FROM imported_activity_entry_revision WHERE identity = :identity")
	abstract suspend fun deleteEntryRevisions(identity: String): Int

	/**
	 * Deletes `imported_activity_entry_revision` rows matching the supplied scope. Query conditions
	 * include `identity IN (:identities)`. Returns the affected row count.
	 */
	@Query("DELETE FROM imported_activity_entry_revision WHERE identity IN (:identities)")
	abstract suspend fun deleteEntryRevisionLineages(identities: List<String>): Int

	/**
	 * Deletes `imported_activity_entry_revision` rows.
	 */
	@Query("DELETE FROM imported_activity_entry_revision")
	abstract fun deleteAllEntries()

	/**
	 * Deletes `imported_activity_receipt` rows.
	 */
	@Query("DELETE FROM imported_activity_receipt")
	abstract fun deleteAllReceipts()

	/**
	 * Deletes `imported_activity_entry_deletion` rows.
	 */
	@Query("DELETE FROM imported_activity_entry_deletion")
	abstract fun deleteAllEntryDeletions()

	/**
	 * Deletes `imported_activity_entry_deletion_receipt` rows.
	 */
	@Query("DELETE FROM imported_activity_entry_deletion_receipt")
	abstract fun deleteAllEntryDeletionReceipts()

	/**
	 * Deletes `imported_activity_deletion_generation` rows.
	 */
	@Query("DELETE FROM imported_activity_deletion_generation")
	abstract fun deleteAllDeletionGenerations()

	/**
	 * Deletes `imported_activity_retained_identity` rows.
	 */
	@Query("DELETE FROM imported_activity_retained_identity")
	abstract fun deleteAllRetainedIdentities()

	/**
	 * Deletes `imported_activity_retention_receipt` rows.
	 */
	@Query("DELETE FROM imported_activity_retention_receipt")
	abstract fun deleteAllRetentionReceipts()

	private fun checkedHistoryIdentities(identities: List<String>): List<String> {
		require(identities.isNotEmpty())
		require(identities.size <= HISTORY_EVALUATION_BATCH_SIZE)
		require(identities.distinct().size == identities.size)
		return identities
	}

	private fun historyLimit(identityCount: Int, maximumPerIdentity: Int): Int =
		Math.addExact(Math.multiplyExact(identityCount, maximumPerIdentity), 1)

	private fun boundedHistoryLimit(
		identityCount: Int,
		maximumPerIdentity: Int,
		maximumRows: Int,
	): Int {
		require(maximumRows >= 0)
		val overflowSentinel = if (maximumRows == Int.MAX_VALUE) {
			Int.MAX_VALUE
		} else {
			maximumRows + 1
		}
		return minOf(historyLimit(identityCount, maximumPerIdentity), overflowSentinel)
	}

	/**
	 * Limits for bounded Activity admission, lineage and maintenance reads; callers detect overflow
	 * before loading more dependencies.
	 */
	companion object {
		const val MAX_REVISIONS_PER_ENTRY = 16
		const val MAX_RECEIPTS_PER_ENTRY = 256
		const val MAX_TOTAL_WINDOWS_PER_ENTRY = 16_384
		const val MAX_TOTAL_FRAGMENTS_PER_ENTRY = 262_144
		const val MAX_TOTAL_RUNS_PER_LINEAGE = MAX_REVISIONS_PER_ENTRY * 64
		const val MAX_TOTAL_ZONE_EPOCHS_PER_LINEAGE = 16_384
		const val MAX_TOTAL_WINDOWS_PER_LINEAGE = 65_536
		const val MAX_TOTAL_FRAGMENTS_PER_LINEAGE = 524_288
		const val MAX_HISTORY_ENTRY_CANDIDATES = 100
		const val HISTORY_EVALUATION_BATCH_SIZE = 4
		const val PROTECTED_IDENTITY_AUDIT_BATCH_SIZE = 400
		const val MAX_RETAINED_IDENTITY_ROWS = 262_144
		private const val HISTORY_ID_QUERY_CHUNK_SIZE = 400
	}
}

/**
 * Saved import identity, revision, checksum and time bounds selected for Activity history;
 * candidate discovery is not payload qualification.
 */
data class ImportedActivityHistoryCandidate(
	val identity: String,
	@ColumnInfo(name = "import_revision") val importRevision: Long,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
	@ColumnInfo(name = "candidate_state") val candidateState: String = IMPORTED_ACTIVITY_CANDIDATE_LIVE,
)

/**
 * Activity history candidate with optional recency and temporal-authority metadata for ordering
 * retained entries.
 */
data class ImportedActivityOrderedHistoryCandidate(
	val identity: String,
	@ColumnInfo(name = "import_revision") val importRevision: Long,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
	@ColumnInfo(name = "candidate_state") val candidateState: String,
	@ColumnInfo(name = "recency_start_time_ms") val recencyStartTimeMs: Long?,
	@ColumnInfo(name = "recency_member_identity") val recencyMemberIdentity: String?,
	@ColumnInfo(name = "temporal_authority_state") val temporalAuthorityState: String?,
) {
	/**
	 * Drops ordering-only recency metadata while preserving the imported entry identity, revision,
	 * original time bounds and candidate state.
	 */
	fun toHistoryCandidate() = ImportedActivityHistoryCandidate(
		identity = identity,
		importRevision = importRevision,
		contentChecksum = contentChecksum,
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		receivedAtMs = receivedAtMs,
		candidateState = candidateState,
	)
}

/**
 * Row and UTF-8 text-byte totals for the selected imported Activity dependency batch, before
 * allocating payload records.
 */
data class ImportedActivityProductBatchPreflight(
	@ColumnInfo(name = "entry_revision_count") val entryRevisionCount: Long,
	@ColumnInfo(name = "import_receipt_count") val importReceiptCount: Long,
	@ColumnInfo(name = "run_count") val runCount: Long,
	@ColumnInfo(name = "zone_epoch_count") val zoneEpochCount: Long,
	@ColumnInfo(name = "window_count") val windowCount: Long,
	@ColumnInfo(name = "fragment_count") val fragmentCount: Long,
	@ColumnInfo(name = "retention_receipt_count") val retentionReceiptCount: Long,
	@ColumnInfo(name = "retained_identity_count") val retainedIdentityCount: Long,
	@ColumnInfo(name = "retained_structural_range_count") val retainedStructuralRangeCount: Long,
	@ColumnInfo(name = "entry_deletion_count") val entryDeletionCount: Long,
	@ColumnInfo(name = "entry_deletion_receipt_count") val entryDeletionReceiptCount: Long,
	@ColumnInfo(name = "run_deletion_count") val runDeletionCount: Long,
	@ColumnInfo(name = "source_fence_count") val sourceFenceCount: Long,
	@ColumnInfo(name = "latest_entry_count") val latestEntryCount: Long,
	@ColumnInfo(name = "latest_run_count") val latestRunCount: Long,
	@ColumnInfo(name = "latest_zone_epoch_count") val latestZoneEpochCount: Long,
	@ColumnInfo(name = "latest_window_count") val latestWindowCount: Long,
	@ColumnInfo(name = "latest_fragment_count") val latestFragmentCount: Long,
	@ColumnInfo(name = "source_text_bytes") val sourceTextBytes: Long,
) {
	init {
		require(
			listOf(
				entryRevisionCount,
				importReceiptCount,
				runCount,
				zoneEpochCount,
				windowCount,
				fragmentCount,
				retentionReceiptCount,
				retainedIdentityCount,
				retainedStructuralRangeCount,
				entryDeletionCount,
				entryDeletionReceiptCount,
				runDeletionCount,
				sourceFenceCount,
				latestEntryCount,
				latestRunCount,
				latestZoneEpochCount,
				latestWindowCount,
				latestFragmentCount,
				sourceTextBytes,
			).all { it >= 0L },
		)
	}
}

/**
 * Counts of imported Activity rows used to detect changes to a retained product snapshot.
 */
data class ImportedActivityProductRevisionSnapshot(
	@ColumnInfo(name = "entry_revision_count") val entryRevisionCount: Long,
	@ColumnInfo(name = "receipt_count") val receiptCount: Long,
	@ColumnInfo(name = "run_count") val runCount: Long,
	@ColumnInfo(name = "zone_epoch_count") val zoneEpochCount: Long,
	@ColumnInfo(name = "window_count") val windowCount: Long,
	@ColumnInfo(name = "fragment_count") val fragmentCount: Long,
	@ColumnInfo(name = "entry_deletion_count") val entryDeletionCount: Long,
	@ColumnInfo(name = "entry_deletion_receipt_count") val entryDeletionReceiptCount: Long,
	@ColumnInfo(name = "deletion_generation_count") val deletionGenerationCount: Long,
	@ColumnInfo(name = "retention_receipt_count") val retentionReceiptCount: Long,
	@ColumnInfo(name = "retained_identity_count") val retainedIdentityCount: Long,
) {
	init {
		require(
			listOf(
				entryRevisionCount,
				receiptCount,
				runCount,
				zoneEpochCount,
				windowCount,
				fragmentCount,
				entryDeletionCount,
				entryDeletionReceiptCount,
				deletionGenerationCount,
				retentionReceiptCount,
				retainedIdentityCount,
			).all { it >= 0L },
		)
	}
}

const val IMPORTED_ACTIVITY_CANDIDATE_LIVE = "LIVE"
const val IMPORTED_ACTIVITY_CANDIDATE_RETAINED = "RETAINED"

/**
 * Reverse association from an imported Activity run identity to its entry and deletion scope.
 */
data class ImportedActivityRunIdentityOwner(
	val identity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String,
)

/**
 * Reverse association from an Activity deletion-scope digest to the imported run and entry that
 * own it.
 */
data class ImportedActivityRunScopeOwner(
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String,
	val identity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
)

/**
 * Reverse association from an imported Activity window identity to its run and entry.
 */
data class ImportedActivityWindowIdentityOwner(
	val identity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "run_identity") val runIdentity: String,
)

/**
 * Opaque protected identity with optional source/purpose/scope metadata for retained ownership
 * audits.
 */
data class ImportedActivityProtectedIdentityOwner(
	@ColumnInfo(name = "owner_kind") val ownerKind: String,
	@ColumnInfo(name = "protected_identity") val protectedIdentity: String,
	@ColumnInfo(name = "source_kind") val sourceKind: Int?,
	val purpose: String?,
	@ColumnInfo(name = "scope_kind") val scopeKind: String?,
) {
	/**
	 * Stored ownership-kind codes used to classify Activity identity and deletion references.
	 */
	companion object {
		const val ENTRY_DELETION = "ENTRY_DELETION"
		const val ENTRY_DELETION_RECEIPT = "ENTRY_DELETION_RECEIPT"
		const val RUN_DELETION = "RUN_DELETION"
		const val SOURCE_DELETION_SCOPE = "SOURCE_DELETION_SCOPE"
	}
}

/**
 * Protected identity ownership tuple and its matching row count, used to detect ambiguous reverse
 * ownership.
 */
data class ImportedActivityProtectedIdentityOwnerCount(
	@ColumnInfo(name = "owner_kind") val ownerKind: String,
	@ColumnInfo(name = "protected_identity") val protectedIdentity: String,
	@ColumnInfo(name = "source_kind") val sourceKind: Int?,
	val purpose: String?,
	@ColumnInfo(name = "scope_kind") val scopeKind: String?,
	@ColumnInfo(name = "owner_count") val ownerCount: Long,
)

/**
 * Result cardinality and UTF-8 byte budget for the selected Activity ownership audit.
 */
data class ImportedActivityOwnerAuditPreflight(
	@ColumnInfo(name = "result_count") val resultCount: Long,
	@ColumnInfo(name = "result_text_bytes") val resultTextBytes: Long,
) {
	init {
		require(resultCount >= 0L && resultTextBytes >= 0L)
	}
}
