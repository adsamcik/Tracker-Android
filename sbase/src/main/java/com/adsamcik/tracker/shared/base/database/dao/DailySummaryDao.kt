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
	 * Get all summaries before a specific epoch day, ordered by date.
	 */
	@Query("SELECT * FROM daily_summary WHERE date_epoch_day < :beforeDay ORDER BY date_epoch_day LIMIT 5000")
	suspend fun getAllBefore(beforeDay: Long): List<DailySummaryEntity>

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

	/**
	 * Sum total tracked distance across all days (meters).
	 */
	@Query("SELECT COALESCE(SUM(total_distance_m), 0) FROM daily_summary")
	suspend fun sumTotalDistance(): Long

	/**
	 * Sum total steps across all days.
	 */
	@Query("SELECT COALESCE(SUM(total_steps), 0) FROM daily_summary")
	suspend fun sumTotalSteps(): Long

	/**
	 * Sum total steps between timestamps using epoch-day buckets.
	 */
	@Query("SELECT COALESCE(SUM(total_steps), 0) FROM daily_summary WHERE date_epoch_day * 86400000 BETWEEN :fromMs AND :toMs")
	suspend fun sumStepsBetween(fromMs: Long, toMs: Long): Long

	/**
	 * Sum total trip count across all days.
	 */
	@Query("SELECT COALESCE(SUM(trip_count), 0) FROM daily_summary")
	suspend fun sumTotalTrips(): Long

	/**
	 * Sum total trip count between timestamps using epoch-day buckets.
	 */
	@Query("SELECT COALESCE(SUM(trip_count), 0) FROM daily_summary WHERE date_epoch_day * 86400000 BETWEEN :fromMs AND :toMs")
	suspend fun sumTripsBetween(fromMs: Long, toMs: Long): Long

	/**
	 * Sum total distance between timestamps using epoch-day buckets (meters).
	 */
	@Query("SELECT CAST(COALESCE(SUM(total_distance_m), 0) AS INTEGER) FROM daily_summary WHERE date_epoch_day * 86400000 BETWEEN :fromMs AND :toMs")
	suspend fun sumTotalDistanceBetween(fromMs: Long, toMs: Long): Long

	/**
	 * Sum active tracking time across all days and return minutes.
	 */
	@Query("SELECT COALESCE(SUM(active_tracking_ms), 0) / 60000 FROM daily_summary")
	suspend fun sumActiveMinutes(): Long

	/**
	 * Sum active tracking time between timestamps and return minutes.
	 */
	@Query("SELECT COALESCE(SUM(active_tracking_ms), 0) / 60000 FROM daily_summary WHERE date_epoch_day * 86400000 BETWEEN :fromMs AND :toMs")
	suspend fun sumActiveMinutesBetween(fromMs: Long, toMs: Long): Long

	/**
	 * Best single-day step count.
	 */
	@Query("SELECT COALESCE(MAX(total_steps), 0) FROM daily_summary")
	suspend fun maxDailySteps(): Long

	/**
	 * Count days that have any tracking data.
	 */
	@Query("SELECT COUNT(*) FROM daily_summary")
	suspend fun countDays(): Long

	/**
	 * Count days with at least [minTripsPerDay] trips across all time.
	 * Used as the cumulative [MetricKeys.ACTIVE_DAYS][com.adsamcik.tracker.stats.api.metric.MetricKeys.ACTIVE_DAYS] value.
	 */
	@Query("SELECT COUNT(*) FROM daily_summary WHERE trip_count >= :minTripsPerDay")
	suspend fun countActiveDays(minTripsPerDay: Int): Long

	/**
	 * Count days in the range whose epoch-day falls within [fromMs, toMs] and that recorded
	 * at least [minTripsPerDay] trips.
	 * Used as the windowed [MetricKeys.ACTIVE_DAYS][com.adsamcik.tracker.stats.api.metric.MetricKeys.ACTIVE_DAYS] value.
	 */
	@Query(
		"""
		SELECT COUNT(*) FROM daily_summary
		WHERE date_epoch_day * 86400000 BETWEEN :fromMs AND :toMs
		  AND trip_count >= :minTripsPerDay
		"""
	)
	suspend fun countActiveDaysBetween(fromMs: Long, toMs: Long, minTripsPerDay: Int): Long
}
