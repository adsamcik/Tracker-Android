package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.TrackerRun
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing tracker_run table.
 */
@Dao
interface TrackerRunDao : BaseDao<TrackerRun> {
	
	/**
	 * Get all tracker runs within time range, ordered by start time.
	 */
	@Query("SELECT * FROM tracker_run WHERE start_time_ms >= :fromMs AND IFNULL(end_time_ms, :toMs) <= :toMs ORDER BY start_time_ms")
	suspend fun getAllBetween(fromMs: Long, toMs: Long): List<TrackerRun>

	/**
	 * Get tracker runs within time range as Flow.
	 */
	@Query("SELECT * FROM tracker_run WHERE start_time_ms >= :fromMs AND IFNULL(end_time_ms, :toMs) <= :toMs ORDER BY start_time_ms")
	fun getAllBetweenFlow(fromMs: Long, toMs: Long): Flow<List<TrackerRun>>

	/**
	 * Get currently active run (end_time_ms is null).
	 */
	@Query("SELECT * FROM tracker_run WHERE end_time_ms IS NULL ORDER BY start_time_ms DESC LIMIT 1")
	suspend fun getActiveRun(): TrackerRun?

	/**
	 * End an active run.
	 */
	@Query("UPDATE tracker_run SET end_time_ms = :endTimeMs WHERE id = :id")
	suspend fun endRun(id: Long, endTimeMs: Long)

	/**
	 * Delete all tracker runs.
	 */
	@Query("DELETE FROM tracker_run")
	fun deleteAll()

	/**
	 * Delete runs older than given timestamp.
	 */
	@Query("DELETE FROM tracker_run WHERE IFNULL(end_time_ms, start_time_ms) < :beforeMs")
	suspend fun deleteOlderThan(beforeMs: Long): Int
}
