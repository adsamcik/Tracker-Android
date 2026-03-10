package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing session_segment table.
 */
@Dao
interface SessionSegmentDao : BaseDao<SessionSegment> {
	
	/**
	 * Get all session segments within time range, ordered by start time.
	 */
	@Query("SELECT * FROM session_segment WHERE start_time_ms >= :fromMs AND end_time_ms <= :toMs ORDER BY start_time_ms")
	suspend fun getAllBetween(fromMs: Long, toMs: Long): List<SessionSegment>

	/**
	 * Get session segments within time range as Flow.
	 */
	@Query("SELECT * FROM session_segment WHERE start_time_ms >= :fromMs AND end_time_ms <= :toMs ORDER BY start_time_ms")
	fun getAllBetweenFlow(fromMs: Long, toMs: Long): Flow<List<SessionSegment>>

	/**
	 * Get segments by source type.
	 */
	@Query("SELECT * FROM session_segment WHERE source = :source AND start_time_ms >= :fromMs AND end_time_ms <= :toMs ORDER BY start_time_ms")
	suspend fun getBySource(source: SegmentSource, fromMs: Long, toMs: Long): List<SessionSegment>

	/**
	 * Get total distance for all segments in time range.
	 */
	@Query("SELECT IFNULL(SUM(distance_m), 0) FROM session_segment WHERE start_time_ms >= :fromMs AND end_time_ms <= :toMs")
	suspend fun getTotalDistance(fromMs: Long, toMs: Long): Float

	/**
	 * Get total steps for all segments in time range.
	 */
	@Query("SELECT IFNULL(SUM(steps), 0) FROM session_segment WHERE start_time_ms >= :fromMs AND end_time_ms <= :toMs AND steps IS NOT NULL")
	suspend fun getTotalSteps(fromMs: Long, toMs: Long): Int

	/**
	 * Count segments by source.
	 */
	@Query("SELECT COUNT(*) FROM session_segment WHERE source = :source")
	suspend fun countBySource(source: SegmentSource): Int

	/**
	 * Delete all session segments.
	 */
	@Query("DELETE FROM session_segment")
	fun deleteAll()

	/**
	 * Delete a single session segment by ID.
	 */
	@Query("DELETE FROM session_segment WHERE id = :id")
	fun deleteById(id: Long)

	/**
	 * Delete segments older than given timestamp.
	 */
	@Query("DELETE FROM session_segment WHERE end_time_ms < :beforeMs")
	suspend fun deleteOlderThan(beforeMs: Long): Int
}
