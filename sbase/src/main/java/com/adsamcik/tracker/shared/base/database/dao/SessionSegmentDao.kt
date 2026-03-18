package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SessionSegmentStats
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing session_segment table.
 */
@Dao
interface SessionSegmentDao : BaseDao<SessionSegment> {

	@Query(
		"""
		SELECT
			COALESCE(SUM(end_time_ms - start_time_ms), 0) AS duration_ms,
			COALESCE(SUM(sample_count), 0) AS collection_count,
			COALESCE(SUM(distance_m), 0) AS distance_m,
			COALESCE(SUM(CASE WHEN primary_activity IN (:onFootActivities) THEN distance_m ELSE 0 END), 0) AS on_foot_distance_m,
			COALESCE(SUM(CASE WHEN primary_activity IN (:inVehicleActivities) THEN distance_m ELSE 0 END), 0) AS in_vehicle_distance_m,
			COALESCE(SUM(steps), 0) AS step_count
		FROM session_segment
		WHERE sample_count > 0
		"""
	)
	suspend fun getSummary(
		onFootActivities: List<Int>,
		inVehicleActivities: List<Int>,
	): SessionSegmentStats

	@Query(
		"""
		SELECT
			COALESCE(SUM(end_time_ms - start_time_ms), 0) AS duration_ms,
			COALESCE(SUM(sample_count), 0) AS collection_count,
			COALESCE(SUM(distance_m), 0) AS distance_m,
			COALESCE(SUM(CASE WHEN primary_activity IN (:onFootActivities) THEN distance_m ELSE 0 END), 0) AS on_foot_distance_m,
			COALESCE(SUM(CASE WHEN primary_activity IN (:inVehicleActivities) THEN distance_m ELSE 0 END), 0) AS in_vehicle_distance_m,
			COALESCE(SUM(steps), 0) AS step_count
		FROM session_segment
		WHERE sample_count > 0
			AND start_time_ms >= :fromMs
			AND end_time_ms <= :toMs
		"""
	)
	suspend fun getSummaryBetween(
		fromMs: Long,
		toMs: Long,
		onFootActivities: List<Int>,
		inVehicleActivities: List<Int>,
	): SessionSegmentStats
	
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

	/**
	 * Delete empty session segments created without any samples.
	 */
	@Query("DELETE FROM session_segment WHERE sample_count = 0")
	suspend fun deleteEmpty(): Int

	/**
	 * Count total session segments.
	 */
	@Query("SELECT COUNT(*) FROM session_segment")
	suspend fun countTotal(): Long

	/**
	 * Count distinct primary_activity values (transport modes used).
	 */
	@Query("SELECT COUNT(DISTINCT primary_activity) FROM session_segment WHERE primary_activity IS NOT NULL")
	suspend fun countDistinctActivities(): Long

	/**
	 * Count segments by primary activity type.
	 */
	@Query("SELECT COUNT(*) FROM session_segment WHERE primary_activity = :activityType")
	suspend fun countByActivity(activityType: Int): Long

	/**
	 * Find the maximum single-segment distance in meters.
	 */
	@Query("SELECT COALESCE(MAX(distance_m), 0) FROM session_segment")
	suspend fun maxSegmentDistance(): Long
}
