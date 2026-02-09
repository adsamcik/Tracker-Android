package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.StorageSizeSnapshotEntity

/**
 * DAO for accessing storage_size_snapshot table.
 * Stores daily snapshots of database storage usage metrics.
 */
@Dao
interface StorageSizeSnapshotDao {

	/**
	 * Insert or replace a storage snapshot for a given day.
	 * Uses REPLACE to handle the unique epoch_day constraint.
	 */
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsert(snapshot: StorageSizeSnapshotEntity)

	/**
	 * Get the most recent storage snapshots, ordered newest first.
	 */
	@Query("SELECT * FROM storage_size_snapshot ORDER BY epoch_day DESC LIMIT :limit")
	suspend fun getRecent(limit: Int): List<StorageSizeSnapshotEntity>

	/**
	 * Get the storage snapshot for a specific day.
	 */
	@Query("SELECT * FROM storage_size_snapshot WHERE epoch_day = :epochDay LIMIT 1")
	suspend fun getByDay(epochDay: Long): StorageSizeSnapshotEntity?

	/**
	 * Delete snapshots older than the specified cutoff day.
	 */
	@Query("DELETE FROM storage_size_snapshot WHERE epoch_day < :cutoffDay")
	suspend fun deleteOlderThan(cutoffDay: Long)

	/**
	 * Delete all storage snapshots. Non-suspend for use in runInTransaction.
	 */
	@Query("DELETE FROM storage_size_snapshot")
	fun deleteAll()
}
