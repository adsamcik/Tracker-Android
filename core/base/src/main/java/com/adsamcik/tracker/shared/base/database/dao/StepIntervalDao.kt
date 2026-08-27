package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing step_interval table.
 */
@Dao
interface StepIntervalDao : BaseDao<StepInterval> {
	@Query("SELECT * FROM step_interval WHERE source_signal_id = :sourceSignalId LIMIT 1")
	suspend fun getBySourceSignalId(sourceSignalId: String): StepInterval?

	@Query(
		"""
		SELECT * FROM step_interval
		WHERE end_time_ms >= :fromMs AND start_time_ms <= :toMs
		ORDER BY start_time_ms ASC, id ASC
		""",
	)
	suspend fun getAllBetween(fromMs: Long, toMs: Long): List<StepInterval>

	@Query(
		"""
		SELECT * FROM step_interval
		WHERE clock_domain_id = :clockDomainId
		  AND COALESCE(source_elapsed_realtime_nanos, received_elapsed_realtime_nanos) >=
			  :fromElapsedRealtimeNanos
		  AND COALESCE(
			  source_first_elapsed_realtime_nanos,
			  source_elapsed_realtime_nanos,
			  received_elapsed_realtime_nanos
		  ) <= :toElapsedRealtimeNanos
		ORDER BY COALESCE(
			source_first_elapsed_realtime_nanos,
			source_elapsed_realtime_nanos,
			received_elapsed_realtime_nanos
		) ASC, id ASC
		""",
	)
	suspend fun getAllInClockDomain(
		clockDomainId: String,
		fromElapsedRealtimeNanos: Long,
		toElapsedRealtimeNanos: Long,
	): List<StepInterval>
	
	/**
	 * Get step intervals within time range as Flow.
	 */
	@Query("SELECT * FROM step_interval WHERE start_time_ms >= :fromMs AND end_time_ms <= :toMs ORDER BY start_time_ms")
	fun getAllBetweenFlow(fromMs: Long, toMs: Long): Flow<List<StepInterval>>

	/**
	 * Get total step count within time range.
	 */
	@Query("SELECT IFNULL(SUM(step_count), 0) FROM step_interval WHERE start_time_ms >= :fromMs AND end_time_ms <= :toMs")
	suspend fun getTotalSteps(fromMs: Long, toMs: Long): Int

	/**
	 * Get most recent step interval.
	 */
	@Query("SELECT * FROM step_interval ORDER BY end_time_ms DESC LIMIT 1")
	suspend fun getLatest(): StepInterval?

	/** True when the legacy Steps destination still contains any collected row. */
	@Query("SELECT EXISTS(SELECT 1 FROM step_interval LIMIT 1)")
	suspend fun hasAny(): Boolean

	/**
	 * Delete all step intervals.
	 */
	@Query("DELETE FROM step_interval")
	fun deleteAll()

	/**
	 * Delete intervals older than given timestamp.
	 */
	@Query("DELETE FROM step_interval WHERE end_time_ms < :beforeMs")
	suspend fun deleteOlderThan(beforeMs: Long): Int
}
