package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.ActivityCapturedPortableFormatV1
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityWindowEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityZoneEpochEntity

/** Activity-specific imported-origin primitives. Admission owns all transaction semantics. */
@Dao
abstract class ImportedActivityDao {
	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertEntryRevision(value: ImportedActivityEntryRevisionEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertReceipt(value: ImportedActivityReceiptEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertRun(value: ImportedActivityRunEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertZoneEpoch(value: ImportedActivityZoneEpochEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertWindow(value: ImportedActivityWindowEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertFragment(value: ImportedActivityFragmentEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertEntryDeletion(value: ImportedActivityEntryDeletionEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insertEntryDeletionReceipt(value: ImportedActivityEntryDeletionReceiptEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	protected abstract suspend fun insertDeletionGenerationRows(
		values: List<ImportedActivityDeletionGenerationEntity>,
	)

	suspend fun insertDeletionGeneration(value: ImportedActivityDeletionGenerationEntity) {
		insertDeletionGenerations(listOf(value))
	}

	suspend fun insertDeletionGenerations(values: List<ImportedActivityDeletionGenerationEntity>) {
		require(values.isNotEmpty())
		require(values.size <= ActivityCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY)
		require(values.distinctBy { it.runIdentity }.size == values.size)
		require(values.all { it.generation == 1L })
		insertDeletionGenerationRows(values)
	}

	@Query(
		"SELECT * FROM imported_activity_entry_revision " +
			"WHERE identity = :identity AND import_revision = :revision",
	)
	abstract suspend fun entryRevision(identity: String, revision: Long):
		ImportedActivityEntryRevisionEntity?

	@Query(
		"SELECT * FROM imported_activity_entry_revision WHERE identity = :identity " +
			"ORDER BY import_revision DESC LIMIT 1",
	)
	abstract suspend fun latestEntryRevision(identity: String): ImportedActivityEntryRevisionEntity?

	@Query(
		"SELECT identity, import_revision, content_checksum, start_time_ms, end_time_ms, " +
			"received_at_ms FROM imported_activity_entry_revision WHERE identity = :identity " +
			"ORDER BY import_revision DESC LIMIT 1",
	)
	abstract suspend fun latestHistoryCandidate(identity: String): ImportedActivityHistoryCandidate?

	/** One latest-revision seed per imported logical entry, ordered for product history. */
	@Query(
		"""
		WITH latest_revision AS (
		  SELECT identity, MAX(import_revision) AS import_revision
		  FROM imported_activity_entry_revision
		  GROUP BY identity
		)
		SELECT entry.identity,
		       entry.import_revision,
		       entry.content_checksum,
		       entry.start_time_ms,
		       entry.end_time_ms,
		       entry.received_at_ms
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
		ORDER BY entry.start_time_ms DESC, entry.identity DESC
		LIMIT :limit
		""",
	)
	abstract suspend fun recentHistoryCandidatePage(
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
	): List<ImportedActivityHistoryCandidate>

	/** Latest imported entries whose complete logical range overlaps an export request. */
	@Query(
		"""
		WITH latest_revision AS (
		  SELECT identity, MAX(import_revision) AS import_revision
		  FROM imported_activity_entry_revision
		  GROUP BY identity
		)
		SELECT entry.identity,
		       entry.import_revision,
		       entry.content_checksum,
		       entry.start_time_ms,
		       entry.end_time_ms,
		       entry.received_at_ms
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
		ORDER BY entry.start_time_ms DESC, entry.identity DESC
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

	suspend fun entryRevisionsForHistory(
		identities: List<String>,
	): List<ImportedActivityEntryRevisionEntity> = loadHistoryEntryRevisions(
		checkedHistoryIdentities(identities),
		historyLimit(identities.size, MAX_REVISIONS_PER_ENTRY),
	)

	@Query(
		"SELECT * FROM imported_activity_entry_revision WHERE identity IN (:identities) " +
			"ORDER BY identity, import_revision LIMIT :limit",
	)
	protected abstract suspend fun loadHistoryEntryRevisions(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityEntryRevisionEntity>

	@Query(
		"SELECT * FROM imported_activity_receipt " +
			"WHERE import_job_id = :jobId AND import_entry_key = :entryKey",
	)
	abstract suspend fun receipt(jobId: String, entryKey: String): ImportedActivityReceiptEntity?

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

	suspend fun receiptsForHistory(identities: List<String>): List<ImportedActivityReceiptEntity> =
		loadHistoryReceipts(
			checkedHistoryIdentities(identities),
			historyLimit(identities.size, MAX_RECEIPTS_PER_ENTRY),
		)

	@Query(
		"SELECT * FROM imported_activity_receipt WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, import_job_id, import_entry_key LIMIT :limit",
	)
	protected abstract suspend fun loadHistoryReceipts(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityReceiptEntity>

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

	suspend fun runsForHistory(identities: List<String>): List<ImportedActivityRunEntity> =
		loadHistoryRuns(
			checkedHistoryIdentities(identities),
			historyLimit(identities.size, MAX_TOTAL_RUNS_PER_LINEAGE),
		)

	@Query(
		"SELECT * FROM imported_activity_run WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, start_time_ms, end_time_ms, identity LIMIT :limit",
	)
	protected abstract suspend fun loadHistoryRuns(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityRunEntity>

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

	suspend fun zoneEpochsForHistory(identities: List<String>): List<ImportedActivityZoneEpochEntity> =
		loadHistoryZoneEpochs(
			checkedHistoryIdentities(identities),
			historyLimit(identities.size, MAX_TOTAL_ZONE_EPOCHS_PER_LINEAGE),
		)

	@Query(
		"SELECT * FROM imported_activity_zone_epoch WHERE entry_identity IN (:identities) " +
			"ORDER BY entry_identity, entry_import_revision, run_identity, ordinal LIMIT :limit",
	)
	protected abstract suspend fun loadHistoryZoneEpochs(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityZoneEpochEntity>

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

	suspend fun windowsForHistory(identities: List<String>): List<ImportedActivityWindowEntity> =
		loadHistoryWindows(
			checkedHistoryIdentities(identities),
			historyLimit(identities.size, MAX_TOTAL_WINDOWS_PER_LINEAGE),
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

	suspend fun fragmentsForHistory(identities: List<String>): List<ImportedActivityFragmentEntity> =
		loadHistoryFragments(
			checkedHistoryIdentities(identities),
			historyLimit(identities.size, MAX_TOTAL_FRAGMENTS_PER_LINEAGE),
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

	@Query(
		"SELECT DISTINCT identity FROM imported_activity_entry_revision " +
			"WHERE identity IN (:identities) LIMIT :limit",
	)
	abstract suspend fun existingEntryIdentities(identities: List<String>, limit: Int): List<String>

	@Query(
		"SELECT DISTINCT identity, entry_identity, deletion_scope_digest FROM imported_activity_run " +
			"WHERE identity IN (:identities) LIMIT :limit",
	)
	abstract suspend fun existingRunIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityRunIdentityOwner>

	@Query(
		"SELECT DISTINCT deletion_scope_digest, identity, entry_identity FROM imported_activity_run " +
			"WHERE deletion_scope_digest IN (:digests) LIMIT :limit",
	)
	abstract suspend fun existingRunScopeOwners(
		digests: List<String>,
		limit: Int,
	): List<ImportedActivityRunScopeOwner>

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

	suspend fun protectedIdentityOwners(
		identities: List<String>,
		limit: Int,
	): List<ImportedActivityProtectedIdentityOwner> {
		require(identities.size in 1..PROTECTED_IDENTITY_AUDIT_BATCH_SIZE)
		require(identities.distinct().size == identities.size)
		require(limit in 1..(identities.size + 2))
		return loadProtectedIdentityOwners(identities, limit)
	}

	@Query("SELECT * FROM imported_activity_entry_deletion WHERE entry_identity = :identity")
	abstract suspend fun entryDeletion(identity: String): ImportedActivityEntryDeletionEntity?

	@Query("SELECT * FROM imported_activity_entry_deletion_receipt WHERE entry_identity = :identity")
	abstract suspend fun entryDeletionReceipt(identity: String): ImportedActivityEntryDeletionReceiptEntity?

	@Query("SELECT * FROM imported_activity_entry_deletion WHERE entry_identity IN (:identities)")
	abstract suspend fun entryDeletions(
		identities: List<String>,
	): List<ImportedActivityEntryDeletionEntity>

	@Query("SELECT * FROM imported_activity_entry_deletion_receipt WHERE entry_identity IN (:identities)")
	abstract suspend fun entryDeletionReceipts(
		identities: List<String>,
	): List<ImportedActivityEntryDeletionReceiptEntity>

	suspend fun entryDeletionsForHistory(
		identities: List<String>,
	): List<ImportedActivityEntryDeletionEntity> = checkedHistoryIdentities(identities)
		.chunked(HISTORY_ID_QUERY_CHUNK_SIZE)
		.flatMap { entryDeletions(it) }

	@Query("SELECT * FROM imported_activity_deletion_generation WHERE run_identity IN (:identities)")
	abstract suspend fun deletionGenerations(
		identities: List<String>,
	): List<ImportedActivityDeletionGenerationEntity>

	suspend fun deletionGenerationsForHistory(
		runIdentities: List<String>,
	): List<ImportedActivityDeletionGenerationEntity> {
		require(runIdentities.size <= MAX_HISTORY_ENTRY_CANDIDATES * MAX_TOTAL_RUNS_PER_LINEAGE)
		return runIdentities.distinct().chunked(HISTORY_ID_QUERY_CHUNK_SIZE)
			.flatMap { deletionGenerations(it) }
	}

	@Query(
		"SELECT * FROM imported_activity_run WHERE entry_identity = :identity " +
			"AND entry_import_revision = :revision ORDER BY start_time_ms, end_time_ms, identity",
	)
	abstract suspend fun runs(identity: String, revision: Long): List<ImportedActivityRunEntity>

	@Query(
		"SELECT * FROM imported_activity_zone_epoch WHERE entry_identity = :entryIdentity " +
			"AND entry_import_revision = :revision AND run_identity = :runIdentity ORDER BY ordinal",
	)
	abstract suspend fun zoneEpochs(
		entryIdentity: String,
		revision: Long,
		runIdentity: String,
	): List<ImportedActivityZoneEpochEntity>

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

	@Query("DELETE FROM imported_activity_entry_revision")
	abstract fun deleteAllEntries()

	@Query("DELETE FROM imported_activity_receipt")
	abstract fun deleteAllReceipts()

	@Query("DELETE FROM imported_activity_entry_deletion")
	abstract fun deleteAllEntryDeletions()

	@Query("DELETE FROM imported_activity_entry_deletion_receipt")
	abstract fun deleteAllEntryDeletionReceipts()

	@Query("DELETE FROM imported_activity_deletion_generation")
	abstract fun deleteAllDeletionGenerations()

	private fun checkedHistoryIdentities(identities: List<String>): List<String> {
		require(identities.isNotEmpty())
		require(identities.size <= HISTORY_EVALUATION_BATCH_SIZE)
		require(identities.distinct().size == identities.size)
		return identities
	}

	private fun historyLimit(identityCount: Int, maximumPerIdentity: Int): Int =
		Math.addExact(Math.multiplyExact(identityCount, maximumPerIdentity), 1)

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
		private const val HISTORY_ID_QUERY_CHUNK_SIZE = 400
	}
}

data class ImportedActivityHistoryCandidate(
	val identity: String,
	@ColumnInfo(name = "import_revision") val importRevision: Long,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
)

data class ImportedActivityRunIdentityOwner(
	val identity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String,
)

data class ImportedActivityRunScopeOwner(
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String,
	val identity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
)

data class ImportedActivityWindowIdentityOwner(
	val identity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "run_identity") val runIdentity: String,
)

data class ImportedActivityProtectedIdentityOwner(
	@ColumnInfo(name = "owner_kind") val ownerKind: String,
	@ColumnInfo(name = "protected_identity") val protectedIdentity: String,
	@ColumnInfo(name = "source_kind") val sourceKind: Int?,
	val purpose: String?,
	@ColumnInfo(name = "scope_kind") val scopeKind: String?,
) {
	companion object {
		const val ENTRY_DELETION = "ENTRY_DELETION"
		const val ENTRY_DELETION_RECEIPT = "ENTRY_DELETION_RECEIPT"
		const val RUN_DELETION = "RUN_DELETION"
		const val SOURCE_DELETION_SCOPE = "SOURCE_DELETION_SCOPE"
	}
}
