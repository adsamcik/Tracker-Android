package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ImportedRadioFileMembership

/**
 * Tracks which original Wi-Fi or Cell readings were present in each imported file, independently
 * of aggregate ownership.
 */
@Dao
interface ImportedRadioFileMembershipDao {
	/**
	 * Reads `imported_radio_file_membership` rows. Query conditions include `source_kind =
	 * :sourceKind`, `import_job_id = :jobId`, `import_entry_key = :entryKey`. Orders by `member_kind,
	 * member_identity`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM imported_radio_file_membership WHERE source_kind = :sourceKind " +
		("AND import_job_id = :jobId AND import_entry_key = :entryKey ORDER BY member_kind, member_identity " +
            "LIMIT :limit"))
	suspend fun members(sourceKind: Int, jobId: String, entryKey: String, limit: Int): List<ImportedRadioFileMembership>

	/**
	 * Inserts [ImportedRadioFileMembership] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insert(rows: List<ImportedRadioFileMembership>)

	/**
	 * Deletes `imported_radio_file_membership` rows matching the supplied scope. Query conditions
	 * include `source_kind = :sourceKind`, `entry_identity = :entryIdentity`.
	 */
	@Query(("DELETE FROM imported_radio_file_membership WHERE source_kind = :sourceKind AND entry_identity " +
        "= :entryIdentity"))
	suspend fun deleteEntry(sourceKind: Int, entryIdentity: String)

	/**
	 * Deletes `imported_radio_file_membership` rows.
	 */
	@Query("DELETE FROM imported_radio_file_membership")
	suspend fun clear()
}
