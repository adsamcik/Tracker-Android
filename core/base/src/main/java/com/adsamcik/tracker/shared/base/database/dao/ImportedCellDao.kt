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
@Dao
abstract class ImportedCellDao {
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertEntryRevision(entry: ImportedCellEntryRevisionEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertRun(run: ImportedCellRunEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertObservation(observation: ImportedCellObservationEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertReceipt(receipt: ImportedCellReceiptEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	protected abstract suspend fun insertDeletionGenerationRow(
		generation: ImportedCellDeletionGenerationEntity,
	)

	suspend fun insertDeletionGeneration(generation: ImportedCellDeletionGenerationEntity) {
		require(generation.generation == 1L)
		insertDeletionGenerationRow(generation)
	}

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertEntryDeletion(deletion: ImportedCellEntryDeletionEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertEntryDeletionReceipt(
		receipt: ImportedCellEntryDeletionReceiptEntity,
	)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertDeletedIdentities(
		identities: List<ImportedCellDeletedIdentityEntity>,
	)

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

	suspend fun boundedEntryRevisions(identity: String): List<ImportedCellEntryRevisionEntity> =
		loadEntryRevisions(identity, MAX_REVISIONS_PER_ENTRY + 1)

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

	@Query("SELECT * FROM imported_cell_entry_deletion WHERE entry_identity = :identity LIMIT 1")
	abstract suspend fun entryDeletion(identity: String): ImportedCellEntryDeletionEntity?

	@Query(
		"SELECT * FROM imported_cell_entry_deletion_receipt WHERE entry_identity = :identity LIMIT 1",
	)
	abstract suspend fun entryDeletionReceipt(
		identity: String,
	): ImportedCellEntryDeletionReceiptEntity?

	@Query(
		"SELECT * FROM imported_cell_deleted_identity WHERE entry_identity = :identity " +
			"ORDER BY protected_identity LIMIT :limit",
	)
	abstract suspend fun deletedIdentitiesForEntry(
		identity: String,
		limit: Int,
	): List<ImportedCellDeletedIdentityEntity>

	@Query(
		"SELECT * FROM imported_cell_entry_deletion_receipt WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity LIMIT :limit",
	)
	abstract suspend fun entryDeletionReceiptOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellEntryDeletionReceiptEntity>

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

	@Query(
		"SELECT entry.identity, entry.import_revision, entry.content_checksum, " +
			"entry.start_time_ms, entry.end_time_ms, entry.received_at_ms " +
			"FROM imported_cell_entry_revision AS entry " +
			"WHERE entry.identity = :identity AND entry.import_revision = (" +
			"SELECT MAX(candidate.import_revision) FROM imported_cell_entry_revision AS candidate " +
			"WHERE candidate.identity = entry.identity) LIMIT 1",
	)
	abstract suspend fun latestHistoryCandidate(identity: String): ImportedCellHistoryCandidate?

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

	@Query(
		"SELECT * FROM imported_cell_entry_revision WHERE identity IN (:identities) " +
			"ORDER BY identity, import_revision LIMIT :limit",
	)
	abstract suspend fun entryRevisionsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellEntryRevisionEntity>

	@Query("SELECT COUNT(*) FROM imported_cell_entry_revision WHERE identity IN (:identities)")
	abstract suspend fun entryRevisionHistoryCount(identities: List<String>): Long

	@Query(
		"SELECT * FROM imported_cell_receipt WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, import_job_id, import_entry_key LIMIT :limit",
	)
	abstract suspend fun receiptsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellReceiptEntity>

	@Query("SELECT COUNT(*) FROM imported_cell_receipt WHERE entry_identity IN (:identities)")
	abstract suspend fun receiptHistoryCount(identities: List<String>): Long

	@Query(
		"SELECT * FROM imported_cell_run WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, start_time_ms, end_time_ms, identity LIMIT :limit",
	)
	abstract suspend fun runsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellRunEntity>

	@Query("SELECT COUNT(*) FROM imported_cell_run WHERE entry_identity IN (:identities)")
	abstract suspend fun runHistoryCount(identities: List<String>): Long

	@Query(
		"SELECT * FROM imported_cell_observation WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, run_identity, coverage_start_time_ms, " +
			"observed_time_ms, identity LIMIT :limit",
	)
	abstract suspend fun observationsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellObservationEntity>

	@Query("SELECT COUNT(*) FROM imported_cell_observation WHERE entry_identity IN (:identities)")
	abstract suspend fun observationHistoryCount(identities: List<String>): Long

	@Query(
		"SELECT * FROM imported_cell_entry_deletion WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity LIMIT :limit",
	)
	abstract suspend fun entryDeletionsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellEntryDeletionEntity>

	@Query("SELECT COUNT(*) FROM imported_cell_entry_deletion WHERE entry_identity IN (:identities)")
	abstract suspend fun entryDeletionHistoryCount(identities: List<String>): Long

	@Query(
		"SELECT * FROM imported_cell_deletion_generation WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, run_identity LIMIT :limit",
	)
	abstract suspend fun deletionGenerationsForHistory(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellDeletionGenerationEntity>

	@Query("SELECT COUNT(*) FROM imported_cell_deletion_generation WHERE entry_identity IN (:identities)")
	abstract suspend fun deletionGenerationHistoryCount(identities: List<String>): Long

	@Query(
		"SELECT * FROM imported_cell_deletion_generation WHERE run_identity IN (:identities) " +
			"OR entry_identity IN (:identities) OR deletion_scope_digest IN (:identities) " +
			"ORDER BY run_identity LIMIT :limit",
	)
	abstract suspend fun deletionGenerationOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellDeletionGenerationEntity>

	@Query(
		"SELECT DISTINCT identity FROM imported_cell_entry_revision " +
			"WHERE identity IN (:identities) ORDER BY identity LIMIT :limit",
	)
	abstract suspend fun existingEntryIdentities(
		identities: List<String>,
		limit: Int,
	): List<String>

	@Query(
		"SELECT DISTINCT identity, entry_identity, deletion_scope_digest FROM imported_cell_run " +
			"WHERE identity IN (:identities) ORDER BY identity, entry_identity, deletion_scope_digest " +
			"LIMIT :limit",
	)
	abstract suspend fun existingRunIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedCellRunIdentityOwner>

	@Query(
		"SELECT DISTINCT deletion_scope_digest, identity, entry_identity FROM imported_cell_run " +
			"WHERE deletion_scope_digest IN (:digests) ORDER BY deletion_scope_digest, identity, " +
			"entry_identity LIMIT :limit",
	)
	abstract suspend fun existingRunScopeOwners(
		digests: List<String>,
		limit: Int,
	): List<ImportedCellRunScopeOwner>

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

	@Query("DELETE FROM imported_cell_entry_revision WHERE identity = :identity")
	abstract suspend fun deleteEntryRevisions(identity: String): Int

	@Query("DELETE FROM imported_cell_receipt")
	abstract fun deleteAllReceipts()

	@Query("DELETE FROM imported_cell_entry_revision")
	abstract fun deleteAllEntries()

	@Query("DELETE FROM imported_cell_entry_deletion")
	abstract fun deleteAllEntryDeletions()

	@Query("DELETE FROM imported_cell_deletion_generation")
	abstract fun deleteAllDeletionGenerations()

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

data class ImportedCellHistoryCandidate(
	val identity: String,
	@ColumnInfo(name = "import_revision") val importRevision: Long,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
)

data class ImportedCellRunIdentityOwner(
	val identity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String,
)

data class ImportedCellRunScopeOwner(
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String,
	val identity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
)

data class ImportedCellObservationIdentityOwner(
	val identity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "run_identity") val runIdentity: String,
	@ColumnInfo(name = "aggregate_owner_identity") val aggregateOwnerIdentity: String?,
)

data class ImportedCellLiveRunOwner(
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
)

data class ImportedCellLiveFactOwner(
	@ColumnInfo(name = "logical_fact_id") val logicalFactId: String,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "aggregate_owner_logical_fact_id") val aggregateOwnerLogicalFactId: String?,
	@ColumnInfo(name = "aggregate_owner_semantic_revision") val aggregateOwnerSemanticRevision: Long?,
)

data class ImportedCellLiveCompletenessOwner(
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
)
