package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing activity_snapshot table.
 */
@Dao
interface ActivitySnapshotDao : BaseDao<ActivitySnapshot> {
	
	/**
	 * Get activity snapshots within time range as Flow.
	 */
	@Query("SELECT * FROM activity_snapshot WHERE time_ms >= :fromMs AND time_ms <= :toMs ORDER BY time_ms")
	fun getAllBetweenFlow(fromMs: Long, toMs: Long): Flow<List<ActivitySnapshot>>

	@Query("SELECT * FROM activity_snapshot WHERE time_ms >= :fromMs AND time_ms <= :toMs ORDER BY time_ms")
	suspend fun getAllBetween(fromMs: Long, toMs: Long): List<ActivitySnapshot>

	@Query(
		"""
		SELECT * FROM activity_snapshot
		WHERE clock_domain_id = :clockDomainId
		  AND COALESCE(source_elapsed_realtime_nanos, received_elapsed_realtime_nanos) >=
			  :fromElapsedRealtimeNanos
		  AND COALESCE(source_elapsed_realtime_nanos, received_elapsed_realtime_nanos) <=
			  :toElapsedRealtimeNanos
		ORDER BY COALESCE(source_elapsed_realtime_nanos, received_elapsed_realtime_nanos) ASC, id ASC
		""",
	)
	suspend fun getAllInClockDomain(
		clockDomainId: String,
		fromElapsedRealtimeNanos: Long,
		toElapsedRealtimeNanos: Long,
	): List<ActivitySnapshot>

	@Query("SELECT * FROM activity_snapshot WHERE time_ms < :timeMs ORDER BY time_ms DESC LIMIT 1")
	suspend fun getLatestBefore(timeMs: Long): ActivitySnapshot?

	/**
	 * Get only activity transitions (not periodic updates).
	 */
	@Query("SELECT * FROM activity_snapshot WHERE time_ms >= :fromMs AND time_ms <= :toMs AND is_transition = 1 ORDER BY time_ms")
	suspend fun getTransitionsBetween(fromMs: Long, toMs: Long): List<ActivitySnapshot>

	/**
	 * Get most recent activity snapshot.
	 */
	@Query("SELECT * FROM activity_snapshot ORDER BY time_ms DESC LIMIT 1")
	suspend fun getLatest(): ActivitySnapshot?

	/**
	 * Delete all activity snapshots.
	 */
	@Query("DELETE FROM activity_snapshot")
	fun deleteAll()

	/**
	 * Delete snapshots older than given timestamp.
	 */
	@Query("DELETE FROM activity_snapshot WHERE time_ms < :beforeMs")
	suspend fun deleteOlderThan(beforeMs: Long): Int
}
