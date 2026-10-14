package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ImportedRadioFileMembership

@Dao
interface ImportedRadioFileMembershipDao {
	@Query("SELECT * FROM imported_radio_file_membership WHERE source_kind = :sourceKind " +
		"AND import_job_id = :jobId AND import_entry_key = :entryKey ORDER BY member_kind, member_identity LIMIT :limit")
	suspend fun members(sourceKind: Int, jobId: String, entryKey: String, limit: Int): List<ImportedRadioFileMembership>

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insert(rows: List<ImportedRadioFileMembership>)

	@Query("DELETE FROM imported_radio_file_membership WHERE source_kind = :sourceKind AND entry_identity = :entryIdentity")
	suspend fun deleteEntry(sourceKind: Int, entryIdentity: String)

	@Query("DELETE FROM imported_radio_file_membership")
	suspend fun clear()
}
