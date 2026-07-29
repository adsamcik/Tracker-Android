package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.CompletedTrackerSession
import com.adsamcik.tracker.shared.base.database.data.TrackerStateEvent

@Dao
interface TrackerStateEventDao {
	@Insert
	suspend fun insert(event: TrackerStateEvent): Long

	@Query(
		"""
		SELECT * FROM tracker_state_event
		WHERE wall_time_ms < :toMs AND wall_time_ms >= :fromMs
		ORDER BY wall_time_ms ASC, id ASC
		""",
	)
	suspend fun getBetween(fromMs: Long, toMs: Long): List<TrackerStateEvent>

	/**
	 * The latest complete lifecycle interval, independent of policy-segment tracker runs.
	 *
	 * The clock domain is session-unique, so the START/STOP pair also provides a safe monotonic
	 * range when wall time changed while tracking.
	 */
	@Query(
		"""
		SELECT
			stop_event.clock_domain_id AS clock_domain_id,
			start_event.elapsed_realtime_nanos AS start_elapsed_realtime_nanos,
			stop_event.elapsed_realtime_nanos AS end_elapsed_realtime_nanos,
			start_event.wall_time_ms AS start_wall_time_ms,
			stop_event.wall_time_ms AS end_wall_time_ms
		FROM tracker_state_event AS stop_event
		INNER JOIN tracker_state_event AS start_event
			ON start_event.clock_domain_id = stop_event.clock_domain_id
			AND start_event.state = 'START'
			AND start_event.elapsed_realtime_nanos <= stop_event.elapsed_realtime_nanos
			AND start_event.id = (
				SELECT MAX(candidate_start.id)
				FROM tracker_state_event AS candidate_start
				WHERE candidate_start.clock_domain_id = stop_event.clock_domain_id
				  AND candidate_start.state = 'START'
				  AND candidate_start.elapsed_realtime_nanos <= stop_event.elapsed_realtime_nanos
			)
		WHERE stop_event.state = 'STOP'
		ORDER BY stop_event.id DESC, start_event.id DESC
		LIMIT 1
		""",
	)
	suspend fun getLatestCompletedSession(): CompletedTrackerSession?

	/**
	 * Completed sessions that have not been published by the current algorithm/configuration.
	 *
	 * Oldest-first ordering prevents a burst of short sessions from starving earlier evidence.
	 */
	@Query(
		"""
		SELECT
			stop_event.clock_domain_id AS clock_domain_id,
			start_event.elapsed_realtime_nanos AS start_elapsed_realtime_nanos,
			stop_event.elapsed_realtime_nanos AS end_elapsed_realtime_nanos,
			start_event.wall_time_ms AS start_wall_time_ms,
			stop_event.wall_time_ms AS end_wall_time_ms
		FROM tracker_state_event AS stop_event
		INNER JOIN tracker_state_event AS start_event
			ON start_event.clock_domain_id = stop_event.clock_domain_id
			AND start_event.state = 'START'
			AND start_event.elapsed_realtime_nanos <= stop_event.elapsed_realtime_nanos
			AND start_event.id = (
				SELECT MAX(candidate_start.id)
				FROM tracker_state_event AS candidate_start
				WHERE candidate_start.clock_domain_id = stop_event.clock_domain_id
				  AND candidate_start.state = 'START'
				  AND candidate_start.elapsed_realtime_nanos <= stop_event.elapsed_realtime_nanos
			)
		WHERE stop_event.state = 'STOP'
		  AND NOT EXISTS (
			  SELECT 1
			  FROM trajectory_reconstruction_run AS reconstruction
			  WHERE reconstruction.source_clock_domain_id = stop_event.clock_domain_id
				AND reconstruction.algorithm_version = :algorithmVersion
				AND reconstruction.configuration_version = :configurationVersion
				AND reconstruction.status = 'COMPLETED'
		  )
		ORDER BY stop_event.id ASC
		""",
	)
	suspend fun getCompletedSessionsAwaitingReconstruction(
		algorithmVersion: String,
		configurationVersion: String,
	): List<CompletedTrackerSession>

	@Query("DELETE FROM tracker_state_event WHERE wall_time_ms < :beforeMs")
	suspend fun deleteOlderThan(beforeMs: Long): Int

	@Query("DELETE FROM tracker_state_event")
	fun deleteAll()
}
