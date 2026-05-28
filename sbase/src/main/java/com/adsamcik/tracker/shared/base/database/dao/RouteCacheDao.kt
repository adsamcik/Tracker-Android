package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.RouteCacheEntity

/**
 * DAO for accessing route_cache table.
 * Stores compressed polyline representations of tracked routes.
 */
@Dao
interface RouteCacheDao {

	/**
	 * Insert a compressed route cache entry.
	 */
	@Insert
	suspend fun insert(route: RouteCacheEntity): Long

	/**
	 * Get all cached routes for a legacy session.
	 */
	@Query("SELECT * FROM route_cache WHERE session_id = :sessionId ORDER BY start_time")
	suspend fun getBySessionId(sessionId: Long): List<RouteCacheEntity>

	/**
	 * Get the cached route for a session segment. Prefers the most recently created row
	 * if duplicates exist (which they shouldn't, but `segment_id` lacks a UNIQUE index,
	 * so an algorithm-version churn could leave two rows for the same segment). The
	 * `ORDER BY created_at DESC` makes the result deterministic.
	 */
	@Query("SELECT * FROM route_cache WHERE segment_id = :segmentId ORDER BY created_at DESC LIMIT 1")
	suspend fun getBySegmentId(segmentId: Long): RouteCacheEntity?

	/**
	 * Get all cached routes within a time range.
	 */
	@Query("SELECT * FROM route_cache WHERE start_time >= :startMs AND end_time <= :endMs ORDER BY start_time")
	suspend fun getBetween(startMs: Long, endMs: Long): List<RouteCacheEntity>

	/**
	 * Delete cached routes older than the cutoff timestamp.
	 */
	@Query("DELETE FROM route_cache WHERE end_time < :cutoffMs")
	suspend fun deleteOlderThan(cutoffMs: Long)

	/**
	 * Delete all cached routes. Non-suspend for use in runInTransaction.
	 */
	@Query("DELETE FROM route_cache")
	fun deleteAll()

	/**
	 * Count total cached routes.
	 */
	@Query("SELECT COUNT(*) FROM route_cache")
	suspend fun count(): Int
}
