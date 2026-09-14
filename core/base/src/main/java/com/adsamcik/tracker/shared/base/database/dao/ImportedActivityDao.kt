package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionEntity
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
	protected abstract suspend fun insertDeletionGenerationRow(
		value: ImportedActivityDeletionGenerationEntity,
	)

	suspend fun insertDeletionGeneration(value: ImportedActivityDeletionGenerationEntity) {
		require(value.generation == 1L)
		insertDeletionGenerationRow(value)
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

	@Query("SELECT * FROM imported_activity_entry_deletion WHERE entry_identity = :identity")
	abstract suspend fun entryDeletion(identity: String): ImportedActivityEntryDeletionEntity?

	@Query("SELECT * FROM imported_activity_entry_deletion WHERE entry_identity IN (:identities)")
	abstract suspend fun entryDeletions(
		identities: List<String>,
	): List<ImportedActivityEntryDeletionEntity>

	@Query("SELECT * FROM imported_activity_deletion_generation WHERE run_identity IN (:identities)")
	abstract suspend fun deletionGenerations(
		identities: List<String>,
	): List<ImportedActivityDeletionGenerationEntity>

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

	@Query("DELETE FROM imported_activity_entry_revision")
	abstract fun deleteAllEntries()

	@Query("DELETE FROM imported_activity_receipt")
	abstract fun deleteAllReceipts()

	@Query("DELETE FROM imported_activity_entry_deletion")
	abstract fun deleteAllEntryDeletions()

	@Query("DELETE FROM imported_activity_deletion_generation")
	abstract fun deleteAllDeletionGenerations()

	companion object {
		const val MAX_REVISIONS_PER_ENTRY = 16
		const val MAX_RECEIPTS_PER_ENTRY = 256
		const val MAX_TOTAL_WINDOWS_PER_ENTRY = 16_384
		const val MAX_TOTAL_FRAGMENTS_PER_ENTRY = 262_144
		const val MAX_TOTAL_RUNS_PER_LINEAGE = MAX_REVISIONS_PER_ENTRY * 64
		const val MAX_TOTAL_ZONE_EPOCHS_PER_LINEAGE = 16_384
		const val MAX_TOTAL_WINDOWS_PER_LINEAGE = 65_536
		const val MAX_TOTAL_FRAGMENTS_PER_LINEAGE = 524_288
	}
}

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
