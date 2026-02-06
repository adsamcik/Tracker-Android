package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.LiveStatsEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing live_stats single-row table.
 */
@Dao
interface LiveStatsDao : BaseDao<LiveStatsEntity> {

	/**
	 * Get current live stats (single row, id=0).
	 */
	@Query("SELECT * FROM live_stats WHERE id = 0")
	suspend fun get(): LiveStatsEntity?

	/**
	 * Get current live stats as Flow for reactive dashboard updates.
	 */
	@Query("SELECT * FROM live_stats WHERE id = 0")
	fun getFlow(): Flow<LiveStatsEntity?>

	/**
	 * Upsert the live stats row.
	 */
	@Query("""
		INSERT OR REPLACE INTO live_stats
		(id, date_epoch_day, session_distance_m, session_steps, session_duration_ms,
		 day_total_distance_m, day_total_steps, day_total_duration_ms, last_updated_ms)
		VALUES (0, :dateEpochDay, :sessionDistanceM, :sessionSteps, :sessionDurationMs,
				:dayTotalDistanceM, :dayTotalSteps, :dayTotalDurationMs, :lastUpdatedMs)
	""")
	suspend fun upsert(
		dateEpochDay: Long,
		sessionDistanceM: Float,
		sessionSteps: Int,
		sessionDurationMs: Long,
		dayTotalDistanceM: Float,
		dayTotalSteps: Int,
		dayTotalDurationMs: Long,
		lastUpdatedMs: Long
	)

	/**
	 * Clear live stats (called on tracking stop).
	 */
	@Query("DELETE FROM live_stats")
	suspend fun clear()

	/**
	 * Delete all live stats (for use in deleteAllCollectedData transaction).
	 */
	@Query("DELETE FROM live_stats")
	fun deleteAll()
}
