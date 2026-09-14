package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRunEntity
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

	/** Full collected-data clear only; retained deletion generations are cleared separately. */
	@Query("DELETE FROM imported_pressure_entry_revision")
	abstract fun deleteAllEntries()

	@Query("DELETE FROM imported_pressure_receipt")
	abstract fun deleteAllReceipts()

	/** Full collected-data clear only. Selected deletion must retain and advance these rows. */
	@Query("DELETE FROM imported_pressure_deletion_generation")
	abstract fun deleteAllDeletionGenerations()

	companion object {
		const val MAX_RUNS_PER_ENTRY = 64
		const val MAX_WINDOWS_PER_RUN = 2_048
		const val MAX_TOTAL_WINDOWS_PER_ENTRY = 16_384
		const val MAX_REVISIONS_PER_ENTRY = 16
		const val MAX_RECEIPTS_PER_ENTRY = 256
		const val MAX_TOTAL_RUNS_PER_ENTRY_LINEAGE = MAX_REVISIONS_PER_ENTRY * MAX_RUNS_PER_ENTRY
		const val MAX_TOTAL_WINDOWS_PER_ENTRY_LINEAGE =
			MAX_REVISIONS_PER_ENTRY * MAX_TOTAL_WINDOWS_PER_ENTRY
	}
}

data class ImportedPressureRunIdentityOwner(
	val identity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
)

data class ImportedPressureWindowIdentityOwner(
	val identity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "run_identity") val runIdentity: String,
)
