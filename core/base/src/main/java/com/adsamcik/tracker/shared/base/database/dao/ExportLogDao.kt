package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ExportLogEntity

/**
 * DAO for accessing export_log table.
 * Records metadata about data export operations.
 */
@Dao
interface ExportLogDao {

	/**
	 * Insert an export log entry.
	 */
	@Insert
	suspend fun insert(log: ExportLogEntity): Long

	/**
	 * Get the most recent export log entries.
	 */
	@Query("SELECT * FROM export_log ORDER BY completed_at DESC LIMIT :limit")
	suspend fun getRecent(limit: Int): List<ExportLogEntity>

	/**
	 * Delete export log entries older than the cutoff timestamp.
	 */
	@Query("DELETE FROM export_log WHERE completed_at < :cutoffMs")
	suspend fun deleteOlderThan(cutoffMs: Long)

	/**
	 * Delete all export log entries. Non-suspend for use in runInTransaction.
	 */
	@Query("DELETE FROM export_log")
	fun deleteAll()

	/**
	 * Count total exports.
	 */
	@Query("SELECT COUNT(*) FROM export_log")
	suspend fun countTotal(): Long

	/**
	 * Count distinct export formats used (gpx / kml / json / sqlite / …).
	 */
	@Query("SELECT COUNT(DISTINCT format) FROM export_log")
	suspend fun countDistinctFormats(): Long
}
