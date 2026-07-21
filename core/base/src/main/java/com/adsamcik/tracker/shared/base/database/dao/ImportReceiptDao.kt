package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ImportEntryReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportJobReceiptEntity

@Dao
interface ImportReceiptDao {
	@Query("SELECT * FROM import_job_receipt WHERE job_id = :jobId")
	suspend fun getJob(jobId: String): ImportJobReceiptEntity?

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertJob(job: ImportJobReceiptEntity): Long

	@Query(
		"""
		UPDATE import_job_receipt
		SET source_name = :sourceName,
			source_size_bytes = :sourceSizeBytes,
			status = 'IN_PROGRESS',
			completed_at = NULL,
			updated_at = :updatedAt
		WHERE job_id = :jobId
		"""
	)
	suspend fun markJobInProgress(
		jobId: String,
		sourceName: String,
		sourceSizeBytes: Long,
		updatedAt: Long,
	)

	@Query(
		"""
		UPDATE import_job_receipt
		SET status = 'COMPLETE',
			completed_at = :completedAt,
			updated_at = :completedAt
		WHERE job_id = :jobId
		"""
	)
	suspend fun markJobComplete(jobId: String, completedAt: Long)

	@Query(
		"""
		SELECT * FROM import_entry_receipt
		WHERE job_id = :jobId AND entry_key = :entryKey
		"""
	)
	suspend fun getEntry(jobId: String, entryKey: String): ImportEntryReceiptEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun putEntry(entry: ImportEntryReceiptEntity)

	@Query("DELETE FROM import_entry_receipt")
	fun deleteAllEntries()

	@Query("DELETE FROM import_job_receipt")
	fun deleteAllJobs()
}
