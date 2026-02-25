package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing daily_summary table.
 */
@Dao
interface DailySummaryDao : BaseDao<DailySummaryEntity> {

	/**
	 * Get summary for a specific day.
	 */
	@Query("SELECT * FROM daily_summary WHERE date_epoch_day = :dateEpochDay")
	suspend fun getByDay(dateEpochDay: Long): DailySummaryEntity?

	/**
	 * Get summary for a specific day as Flow.
	 */
	@Query("SELECT * FROM daily_summary WHERE date_epoch_day = :dateEpochDay")
	fun getByDayFlow(dateEpochDay: Long): Flow<DailySummaryEntity?>

	/**
	 * Get all summaries in a date range, ordered by date.
	 */
	@Query("SELECT * FROM daily_summary WHERE date_epoch_day >= :fromDay AND date_epoch_day <= :toDay ORDER BY date_epoch_day")
	suspend fun getBetween(fromDay: Long, toDay: Long): List<DailySummaryEntity>

	/**
	 * Get all summaries in a date range as a reactive Flow.
	 */
	@Query("SELECT * FROM daily_summary WHERE date_epoch_day >= :fromDay AND date_epoch_day <= :toDay ORDER BY date_epoch_day")
	fun getBetweenFlow(fromDay: Long, toDay: Long): Flow<List<DailySummaryEntity>>

	/**
	 * Upsert a daily summary row.
	 * Uses REPLACE to handle insert-or-update since dateEpochDay is the primary key.
	 */
	@Query("""
		INSERT OR REPLACE INTO daily_summary
		(date_epoch_day, total_distance_m, total_steps, total_duration_ms, trip_count, active_tracking_ms, last_updated_ms, created_at)
		VALUES (:dateEpochDay, :totalDistanceM, :totalSteps, :totalDurationMs, :tripCount, :activeTrackingMs, :lastUpdatedMs,
				COALESCE((SELECT created_at FROM daily_summary WHERE date_epoch_day = :dateEpochDay), :lastUpdatedMs))
	""")
	suspend fun upsert(
		dateEpochDay: Long,
		totalDistanceM: Float,
		totalSteps: Int,
		totalDurationMs: Long,
		tripCount: Int,
		activeTrackingMs: Long,
		lastUpdatedMs: Long
	)

	/**
	 * Delete all daily summaries.
	 */
	@Query("DELETE FROM daily_summary")
	fun deleteAll()

	/**
	 * Delete summaries older than given epoch day.
	 */
	@Query("DELETE FROM daily_summary WHERE date_epoch_day < :beforeDay")
	suspend fun deleteOlderThan(beforeDay: Long): Int
}
