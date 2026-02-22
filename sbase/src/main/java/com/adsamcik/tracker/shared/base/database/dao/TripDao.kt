package com.adsamcik.tracker.shared.base.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.shared.base.database.data.TripDaySummary

/**
 * Read-only DAO projecting [com.adsamcik.tracker.shared.base.database.data.SessionSegment]
 * rows as [Trip] POJOs for UI consumption.
 */
@Dao
interface TripDao {

	/**
	 * Paged list of all trips, newest first. Used by the Stats list.
	 */
	@Query(
		"""
		SELECT id, start_time_ms AS startTimeMs, end_time_ms AS endTimeMs,
		       distance_m AS distanceM, steps, primary_activity AS primaryActivity,
		       activity_confidence AS activityConfidence, sample_count AS sampleCount,
		       source, created_at AS createdAt
		FROM session_segment
		ORDER BY start_time_ms DESC
		"""
	)
	fun getAllPaged(): PagingSource<Int, Trip>

	/**
	 * Trips within a time range, ordered by start time descending.
	 */
	@Query(
		"""
		SELECT id, start_time_ms AS startTimeMs, end_time_ms AS endTimeMs,
		       distance_m AS distanceM, steps, primary_activity AS primaryActivity,
		       activity_confidence AS activityConfidence, sample_count AS sampleCount,
		       source, created_at AS createdAt
		FROM session_segment
		WHERE start_time_ms >= :fromMs AND end_time_ms <= :toMs
		ORDER BY start_time_ms DESC
		LIMIT 500
		"""
	)
	suspend fun getBetween(fromMs: Long, toMs: Long): List<Trip>

	/**
	 * Single trip lookup by ID.
	 */
	@Query(
		"""
		SELECT id, start_time_ms AS startTimeMs, end_time_ms AS endTimeMs,
		       distance_m AS distanceM, steps, primary_activity AS primaryActivity,
		       activity_confidence AS activityConfidence, sample_count AS sampleCount,
		       source, created_at AS createdAt
		FROM session_segment
		WHERE id = :id
		"""
	)
	suspend fun getById(id: Long): Trip?

	/**
	 * Aggregated day summary for the dashboard.
	 */
	@Query(
		"""
		SELECT COUNT(*) AS tripCount,
		       IFNULL(SUM(distance_m), 0) AS totalDistanceM,
		       IFNULL(SUM(steps), 0) AS totalSteps,
		       IFNULL(SUM(end_time_ms - start_time_ms), 0) AS totalDurationMs
		FROM session_segment
		WHERE start_time_ms >= :startOfDayMs AND end_time_ms <= :nowMs
		"""
	)
	suspend fun getTodaySummary(startOfDayMs: Long, nowMs: Long): TripDaySummary?

	/**
	 * Delete a single trip (session segment) by ID.
	 */
	@Query("DELETE FROM session_segment WHERE id = :id")
	suspend fun deleteById(id: Long)

	/**
	 * Most recent trips, limited to [limit] results. For dashboard quick view.
	 */
	@Query(
		"""
		SELECT id, start_time_ms AS startTimeMs, end_time_ms AS endTimeMs,
		       distance_m AS distanceM, steps, primary_activity AS primaryActivity,
		       activity_confidence AS activityConfidence, sample_count AS sampleCount,
		       source, created_at AS createdAt
		FROM session_segment
		ORDER BY start_time_ms DESC
		LIMIT :limit
		"""
	)
	suspend fun getRecentTrips(limit: Int): List<Trip>
}
