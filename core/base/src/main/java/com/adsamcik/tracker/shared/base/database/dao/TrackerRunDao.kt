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
	@Query("SELECT COALESCE(MAX(id), 0) FROM tracker_run")
	suspend fun maxId(): Long

	@Query("SELECT MIN(start_time_ms) FROM tracker_run")
	suspend fun minStartTimeMs(): Long?
	
	/**
	 * Get all tracker runs within time range, ordered by start time.
	 */
	@Query(
		"SELECT * FROM tracker_run WHERE start_time_ms >= :fromMs AND start_time_ms < :toMs " +
			"AND (end_time_ms IS NULL OR end_time_ms <= :toMs) ORDER BY start_time_ms",
	)
	suspend fun getAllBetween(fromMs: Long, toMs: Long): List<TrackerRun>

	/**
	 * Runs that overlap the half-open window, including a currently active run.
	 *
	 * A fenced legacy row with no factual end is unknown-boundary evidence, not inferred continuous
	 * activity. It therefore matches only the window containing its known start; an unfenced null end
	 * still represents the live runtime and remains open through the query window.
	 */
	@Query(
		"""
		SELECT * FROM tracker_run
		WHERE start_time_ms < :toMs
		  AND (
		    end_time_ms > :fromMs
		    OR (
		      end_time_ms IS NULL
		      AND (legacy_runtime_fenced = 0 OR start_time_ms >= :fromMs)
		    )
		  )
		ORDER BY start_time_ms ASC, id ASC
		""",
	)
	suspend fun getOverlapping(fromMs: Long, toMs: Long): List<TrackerRun>

	/** Stable, bounded snapshot page for diagnostic/research exports. */
	@Query(
		"""
		SELECT * FROM tracker_run
		WHERE id > :afterId AND id <= :throughId
		  AND start_time_ms < :toMsExclusive
		  AND (
		    end_time_ms > :fromMs
		    OR (
		      end_time_ms IS NULL
		      AND (legacy_runtime_fenced = 0 OR start_time_ms >= :fromMs)
		    )
		  )
		ORDER BY id ASC
		LIMIT :limit
		""",
	)
	suspend fun getOverlappingChunk(
		fromMs: Long,
		toMsExclusive: Long,
		afterId: Long,
		throughId: Long,
		limit: Int,
	): List<TrackerRun>

	/**
	 * Get tracker runs within time range as Flow.
	 */
	@Query(
		"SELECT * FROM tracker_run WHERE start_time_ms >= :fromMs AND start_time_ms < :toMs " +
			"AND (end_time_ms IS NULL OR end_time_ms <= :toMs) ORDER BY start_time_ms",
	)
	fun getAllBetweenFlow(fromMs: Long, toMs: Long): Flow<List<TrackerRun>>

	/**
	 * Get the current-process run whose end is still open.
	 *
	 * A migrated legacy row may retain a null factual end while being durably fenced from runtime
	 * ownership. Such a row is historical/diagnostic evidence, not an active run.
	 */
	@Query(
		"SELECT * FROM tracker_run WHERE end_time_ms IS NULL AND legacy_runtime_fenced = 0 " +
			"ORDER BY start_time_ms DESC LIMIT 1",
	)
	suspend fun getActiveRun(): TrackerRun?

	/** Number of runs that still claim to be active. Healthy state permits at most one. */
	@Query("SELECT COUNT(*) FROM tracker_run WHERE end_time_ms IS NULL")
	suspend fun countOpenRuns(): Int

	@Query(
		"""
		SELECT * FROM tracker_run
		WHERE end_time_ms IS NOT NULL
		ORDER BY end_time_ms DESC, id DESC
		LIMIT 1
		""",
	)
	suspend fun getLatestCompletedRun(): TrackerRun?

	/**
	 * Close every run left open by an earlier process before a replacement run starts.
	 *
	 * Multiple rows can be open after repeated process death, so this deliberately updates all of
	 * them. The end is clamped to the row start to preserve a non-negative interval if the wall
	 * clock moved backwards between processes.
	 */
	@Query(
		"""
		UPDATE tracker_run
		SET end_time_ms = MAX(start_time_ms, :endTimeMs)
		WHERE end_time_ms IS NULL AND legacy_runtime_fenced = 0
		""",
	)
	suspend fun closeOpenRuns(endTimeMs: Long): Int

	/**
	 * End an active run.
	 */
	@Query(
		"UPDATE tracker_run SET end_time_ms = :endTimeMs " +
			"WHERE id = :id AND legacy_runtime_fenced = 0",
	)
	suspend fun endRun(id: Long, endTimeMs: Long)

	/**
	 * Delete all tracker runs.
	 */
	@Query("DELETE FROM tracker_run")
	fun deleteAll()

	/**
	 * Delete runs older than given timestamp.
	 */
	@Query("DELETE FROM tracker_run WHERE end_time_ms IS NOT NULL AND end_time_ms < :beforeMs")
	suspend fun deleteOlderThan(beforeMs: Long): Int
}
