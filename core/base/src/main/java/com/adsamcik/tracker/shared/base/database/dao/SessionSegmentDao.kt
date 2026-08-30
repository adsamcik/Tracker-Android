package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SessionSegmentStats
import kotlinx.coroutines.flow.Flow
import com.adsamcik.tracker.shared.model.SegmentSource

data class SessionSegmentBounds(
	@ColumnInfo(name = "min_start")
	val minStart: Long?,
	@ColumnInfo(name = "max_end")
	val maxEnd: Long?,
)

/**
 * DAO for accessing session_segment table.
 */
@Dao
interface SessionSegmentDao : BaseDao<SessionSegment> {
	@Query("SELECT * FROM session_segment WHERE id = :id")
	suspend fun getById(id: Long): SessionSegment?

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
	 *
	 * Strict containment: `start_time_ms >= :fromMs AND end_time_ms <= :toMs`. Use
	 * [getOverlapping] when you also need segments that cross the boundary (e.g.
	 * a run from 23:50 to 00:10 for daily-summary aggregation).
	 */
	@Query("SELECT * FROM session_segment WHERE start_time_ms >= :fromMs AND end_time_ms <= :toMs ORDER BY start_time_ms")
	suspend fun getAllBetween(fromMs: Long, toMs: Long): List<SessionSegment>

	/**
	 * Get session segments that overlap the range `[fromMs, toMs)` (start exclusive
	 * end exclusive). Includes segments fully inside the range AND segments that
	 * straddle either boundary. Used by daily-summary aggregation to prorate
	 * cross-midnight runs that would otherwise be dropped from every day.
	 *
	 * Empty preinserted placeholders are excluded because they are not recorded trips and may survive
	 * a process death until lifecycle-owned reclamation proves the presentation writer is quiescent.
	 *
	 * `INDEXED BY idx_session_segment_end_time_ms` forces the planner to seek on
	 * `end_time_ms > :fromMs` first. When materializing today against years of history
	 * the `start_time_ms < :toMs` predicate covers a huge range (anything before now),
	 * while `end_time_ms > :fromMs` (start-of-day) is far more selective — the planner
	 * would otherwise pick the composite `idx_session_segment_time_range (start_time_ms,
	 * end_time_ms)` and scan everything before today.
	 *
	 * EXPLAIN QUERY PLAN: `SEARCH session_segment USING INDEX idx_session_segment_end_time_ms (end_time_ms>?)`
	 */
	@Query(
		"""
		SELECT * FROM session_segment INDEXED BY idx_session_segment_end_time_ms
		WHERE sample_count > 0 AND end_time_ms > :fromMs AND start_time_ms < :toMs
		ORDER BY start_time_ms
		"""
	)
	suspend fun getOverlapping(fromMs: Long, toMs: Long): List<SessionSegment>

	@Query(
		"""
		SELECT MIN(start_time_ms) AS min_start, MAX(end_time_ms) AS max_end
		FROM session_segment
		WHERE primary_activity IS NULL
		"""
	)
	suspend fun getUnrecognizedBounds(): SessionSegmentBounds

	@Query(
		"""
		SELECT *
		FROM session_segment
		WHERE primary_activity IS NULL
			AND start_time_ms >= :fromMs
			AND end_time_ms <= :toMs
		ORDER BY start_time_ms
		"""
	)
	suspend fun getUnrecognizedWithin(fromMs: Long, toMs: Long): List<SessionSegment>

	@Query(
		"""
		SELECT *
		FROM session_segment
		WHERE primary_activity IS NULL
			AND start_time_ms >= :fromMs
			AND start_time_ms <= :toMs
		ORDER BY start_time_ms
		"""
	)
	suspend fun getUnrecognizedStartingBetween(fromMs: Long, toMs: Long): List<SessionSegment>

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
	@Query("SELECT COUNT(*) FROM session_segment WHERE source = :source AND sample_count > 0")
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
	 * Count total session segments.
	 */
	@Query("SELECT COUNT(*) FROM session_segment")
	suspend fun countTotal(): Long

	/**
	 * Count distinct primary_activity values (transport modes used).
	 */
	@Query(
		"""
		SELECT COUNT(DISTINCT primary_activity)
		FROM session_segment
		WHERE primary_activity IS NOT NULL
			AND sample_count > 0
		"""
	)
	suspend fun countDistinctActivities(): Long

	/**
	 * Count segments by primary activity type.
	 */
	@Query("SELECT COUNT(*) FROM session_segment WHERE primary_activity = :activityType AND sample_count > 0")
	suspend fun countByActivity(activityType: Int): Long

	/**
	 * Count segments by a set of primary activities.
	 */
	@Query("SELECT COUNT(*) FROM session_segment WHERE primary_activity IN (:activityTypes) AND sample_count > 0")
	suspend fun countByActivities(activityTypes: List<Int>): Long

	/** Count distinct local calendar days containing a classified activity. */
	@Query(
		"""
		SELECT COUNT(DISTINCT strftime('%Y-%m-%d', start_time_ms / 1000, 'unixepoch', 'localtime'))
		FROM session_segment
		WHERE primary_activity IN (:activityTypes)
			AND sample_count > 0
		"""
	)
	suspend fun countDistinctDaysByActivities(activityTypes: List<Int>): Long

	/**
	 * Count segments by a set of primary activities that OVERLAP the closed time
	 * interval `[fromMs, toMs]`. Includes segments fully inside the interval AND
	 * segments that straddle either boundary — matches the daily_summary cross-
	 * midnight overlap semantics so windowed walking/cycling counts no longer
	 * silently drop boundary-crossing trips.
	 */
	@Query(
		"""
		SELECT COUNT(*) FROM session_segment
		WHERE primary_activity IN (:activityTypes)
			AND start_time_ms < :toMs AND end_time_ms > :fromMs
		"""
	)
	suspend fun countByActivitiesBetween(fromMs: Long, toMs: Long, activityTypes: List<Int>): Long

	/**
	 * Sum distance for a set of primary activities (meters).
	 */
	@Query("SELECT CAST(COALESCE(SUM(distance_m), 0) AS INTEGER) FROM session_segment WHERE primary_activity IN (:activityTypes)")
	suspend fun sumDistanceByActivities(activityTypes: List<Int>): Long

	/**
	 * Sum distance for a set of primary activities that OVERLAP `[fromMs, toMs]`,
	 * prorated by the fraction of each segment's duration that falls inside the
	 * interval. Constant-speed approximation — same compromise as
	 * `DailySummaryAggregator.materializeDayFromSegments`. Without prorating,
	 * cross-midnight (or boundary-crossing) segments would either be dropped
	 * (strict containment) or double-counted (overlap with full distance).
	 */
	@Query(
		"""
		SELECT CAST(
			COALESCE(SUM(
				distance_m * (
					CAST(MIN(end_time_ms, :toMs) - MAX(start_time_ms, :fromMs) AS REAL)
					/ CAST(MAX(end_time_ms - start_time_ms, 1) AS REAL)
				)
			), 0) AS INTEGER
		)
		FROM session_segment
		WHERE primary_activity IN (:activityTypes)
			AND start_time_ms < :toMs AND end_time_ms > :fromMs
		"""
	)
	suspend fun sumDistanceByActivitiesBetween(fromMs: Long, toMs: Long, activityTypes: List<Int>): Long

	/**
	 * Find the maximum single-segment distance in meters.
	 */
	@Query("SELECT COALESCE(MAX(distance_m), 0) FROM session_segment")
	suspend fun maxSegmentDistance(): Long

	@Query("SELECT COALESCE(MAX(end_time_ms - start_time_ms), 0) FROM session_segment")
	suspend fun maxSegmentDurationMs(): Long

	@Query("SELECT COALESCE(MAX(CASE WHEN end_time_ms > start_time_ms THEN distance_m / ((end_time_ms - start_time_ms) / 1000.0) ELSE 0 END), 0) FROM session_segment")
	suspend fun maxAverageSpeedMps(): Double

	@Query("SELECT COUNT(DISTINCT strftime('%H', start_time_ms / 1000, 'unixepoch', 'localtime')) FROM session_segment")
	suspend fun countDistinctStartHours(): Long

	@Query(
		"""
		SELECT COUNT(DISTINCT primary_activity)
		FROM session_segment
		WHERE primary_activity IS NOT NULL
			AND start_time_ms < :toMs AND end_time_ms > :fromMs
		"""
	)
	suspend fun countDistinctActivitiesBetween(fromMs: Long, toMs: Long): Long

	@Query("SELECT MIN(start_time_ms) FROM session_segment")
	suspend fun minStartTime(): Long?

	/**
	 * Count segments whose LOCAL start hour is in the half-open range
	 * `[fromHour, toHour)` (e.g. 0..5 for late-night, 5..8 for dawn).
	 */
	@Query(
		"""
		SELECT COUNT(*) FROM session_segment
		WHERE CAST(strftime('%H', start_time_ms / 1000, 'unixepoch', 'localtime') AS INTEGER) >= :fromHour
			AND CAST(strftime('%H', start_time_ms / 1000, 'unixepoch', 'localtime') AS INTEGER) < :toHour
		"""
	)
	suspend fun countSessionsStartingBetweenHours(fromHour: Int, toHour: Int): Long

	/** Maximum single-segment distance (meters) for a set of primary activities. */
	@Query("SELECT CAST(COALESCE(MAX(distance_m), 0) AS INTEGER) FROM session_segment WHERE primary_activity IN (:activityTypes)")
	suspend fun maxDistanceByActivities(activityTypes: List<Int>): Long

	/**
	 * Count distinct LOCAL calendar days that contain at least one walking, one
	 * cycling and one driving segment (a "triathlete" day).
	 */
	@Query(
		"""
		SELECT COUNT(*) FROM (
			SELECT strftime('%Y-%m-%d', start_time_ms / 1000, 'unixepoch', 'localtime') AS day
			FROM session_segment
			GROUP BY day
			HAVING SUM(CASE WHEN primary_activity IN (:walk) THEN 1 ELSE 0 END) > 0
				AND SUM(CASE WHEN primary_activity IN (:cycle) THEN 1 ELSE 0 END) > 0
				AND SUM(CASE WHEN primary_activity IN (:drive) THEN 1 ELSE 0 END) > 0
		)
		"""
	)
	suspend fun countTriathlonDays(walk: List<Int>, cycle: List<Int>, drive: List<Int>): Long
}
