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
	/** Returns the saved observation with this exact source identity, if present. */
	@Query("SELECT * FROM activity_snapshot WHERE source_signal_id = :sourceSignalId LIMIT 1")
	suspend fun getBySourceSignalId(sourceSignalId: String): ActivitySnapshot?

	/** Returns saved observations selected by their original source identities. */
	@Query("SELECT * FROM activity_snapshot WHERE source_signal_id IN (:identities)")
	suspend fun bySourceSignalIds(identities: List<String>): List<ActivitySnapshot>

	/** Reads all-day observations and imported copies within the measured window. */
	@Query(
	    "SELECT * FROM activity_snapshot WHERE source_signal_id LIKE 'all-day:activity:%' " +
	        "AND time_ms >= :fromMs AND time_ms < :toMs " +
	        "ORDER BY time_ms, source_signal_id LIMIT :limit",
	)
	suspend fun allDayBetween(fromMs: Long, toMs: Long, limit: Int): List<ActivitySnapshot>

	/** Reads on-device all-day observations, excluding imported copies before applying the row limit. */
	@Query("""
	    SELECT * FROM activity_snapshot
	    WHERE source_signal_id LIKE 'all-day:activity:%' AND time_ms >= :fromMs AND time_ms < :toMs
	      AND instr(',' || COALESCE(capability_flags, '') || ',', ',PORTABLE_IMPORT,') = 0
	    ORDER BY time_ms, source_signal_id LIMIT :limit
	""")
	suspend fun nativeAllDayBetween(fromMs: Long, toMs: Long, limit: Int): List<ActivitySnapshot>

	/** Pages all-day observations and imported copies by stable row ID within the measured window. */
	@Query(
	    "SELECT * FROM activity_snapshot WHERE source_signal_id LIKE 'all-day:activity:%' " +
	        "AND time_ms >= :fromMs AND time_ms < :toMs " +
	        "AND id > :afterId ORDER BY id LIMIT :limit",
	)
	suspend fun allDayPage(fromMs: Long, toMs: Long, afterId: Long, limit: Int): List<ActivitySnapshot>

	/** Deletes all rows from the native Activity observation table. */
	@Query("DELETE FROM activity_snapshot")
	suspend fun deleteAllNative(): Int

	/** Counts rows in the native Activity observation table. */
	@Query("SELECT COUNT(*) FROM activity_snapshot")
	suspend fun nativeCount(): Int

	/** Counts saved all-day Activity observations, including imported copies. */
	@Query("SELECT COUNT(*) FROM activity_snapshot WHERE source_signal_id LIKE 'all-day:activity:%'")
	suspend fun allDayCount(): Int
	
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

    /** Exact saved leaves selected by the bounded measured-history key page. */
    @Query("SELECT * FROM activity_snapshot WHERE id IN (:ids) ORDER BY id")
    suspend fun historyRows(ids: List<Long>): List<ActivitySnapshot>
}
