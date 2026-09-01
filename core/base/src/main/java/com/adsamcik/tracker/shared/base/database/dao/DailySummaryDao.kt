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
	@Suppress("LongParameterList")
	@Query("""
		INSERT OR REPLACE INTO daily_summary
		(date_epoch_day, total_distance_m, total_steps, total_duration_ms, trip_count, active_tracking_ms, last_updated_ms, created_at,
			calendar_zone_id)
		VALUES (:dateEpochDay, :totalDistanceM, :totalSteps, :totalDurationMs, :tripCount, :activeTrackingMs, :lastUpdatedMs,
				COALESCE((SELECT created_at FROM daily_summary WHERE date_epoch_day = :dateEpochDay), :lastUpdatedMs),
				:calendarZoneId)
	""")
	suspend fun upsert(
		dateEpochDay: Long,
		totalDistanceM: Float,
		totalSteps: Int,
		totalDurationMs: Long,
		tripCount: Int,
		activeTrackingMs: Long,
		lastUpdatedMs: Long,
		calendarZoneId: String,
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

	/** Deletes one derived day after its final segment-backed contribution disappears. */
	@Query("DELETE FROM daily_summary WHERE date_epoch_day = :dateEpochDay")
	suspend fun deleteByDay(dateEpochDay: Long): Int

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
	 * Sum total steps across all days whose epoch-day falls within [[fromDay], [toDay]].
	 * Inclusive on both ends. Callers convert wall-clock ms → epoch-day via
	 * [fromMsToFromDay]/[toMsToToDay] so the SQLite index on `date_epoch_day` is used.
	 */
	@Query("SELECT COALESCE(SUM(total_steps), 0) FROM daily_summary WHERE date_epoch_day BETWEEN :fromDay AND :toDay")
	suspend fun sumStepsBetween(fromDay: Long, toDay: Long): Long

	/**
	 * Sum total trip count across all days.
	 */
	@Query("SELECT COALESCE(SUM(trip_count), 0) FROM daily_summary")
	suspend fun sumTotalTrips(): Long

	/**
	 * Sum total trip count across days within [[fromDay], [toDay]].
	 */
	@Query("SELECT COALESCE(SUM(trip_count), 0) FROM daily_summary WHERE date_epoch_day BETWEEN :fromDay AND :toDay")
	suspend fun sumTripsBetween(fromDay: Long, toDay: Long): Long

	/**
	 * Sum total distance (meters) across days within [[fromDay], [toDay]].
	 */
	@Query("SELECT CAST(COALESCE(SUM(total_distance_m), 0) AS INTEGER) FROM daily_summary WHERE date_epoch_day BETWEEN :fromDay AND :toDay")
	suspend fun sumTotalDistanceBetween(fromDay: Long, toDay: Long): Long

	/**
	 * Sum active tracking time across all days and return minutes.
	 */
	@Query("SELECT COALESCE(SUM(active_tracking_ms), 0) / 60000 FROM daily_summary")
	suspend fun sumActiveMinutes(): Long

	/**
	 * Sum active tracking minutes across days within [[fromDay], [toDay]].
	 */
	@Query("SELECT COALESCE(SUM(active_tracking_ms), 0) / 60000 FROM daily_summary WHERE date_epoch_day BETWEEN :fromDay AND :toDay")
	suspend fun sumActiveMinutesBetween(fromDay: Long, toDay: Long): Long

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
	 * Used as the cumulative [MetricKeys.ACTIVE_DAYS_TOTAL][com.adsamcik.tracker.stats.api.metric.MetricKeys.ACTIVE_DAYS_TOTAL] value.
	 */
	@Query("SELECT COUNT(*) FROM daily_summary WHERE trip_count >= :minTripsPerDay")
	suspend fun countActiveDays(minTripsPerDay: Int): Long

	/**
	 * Count days in the range whose epoch-day falls within [[fromDay], [toDay]] and that
	 * recorded at least [minTripsPerDay] trips.
	 * Used as the windowed [MetricKeys.ACTIVE_DAYS_TOTAL][com.adsamcik.tracker.stats.api.metric.MetricKeys.ACTIVE_DAYS_TOTAL] value.
	 */
	@Query(
		"""
		SELECT COUNT(*) FROM daily_summary
		WHERE date_epoch_day BETWEEN :fromDay AND :toDay
		  AND trip_count >= :minTripsPerDay
		"""
	)
	suspend fun countActiveDaysBetween(fromDay: Long, toDay: Long, minTripsPerDay: Int): Long

	@Query("SELECT COALESCE(SUM(total_duration_ms), 0) FROM daily_summary")
	suspend fun sumTotalDurationMs(): Long

	@Query("SELECT COALESCE(MAX(total_distance_m), 0) FROM daily_summary")
	suspend fun maxDailyDistance(): Long

	@Query("SELECT MIN(created_at) FROM daily_summary")
	suspend fun minCreatedAt(): Long?

	@Query("SELECT date_epoch_day FROM daily_summary WHERE trip_count > 0 ORDER BY date_epoch_day ASC")
	suspend fun getActiveEpochDays(): List<Long>

	@Query("SELECT COUNT(DISTINCT strftime('%Y-%m', date_epoch_day * 86400, 'unixepoch')) FROM daily_summary WHERE trip_count > 0")
	suspend fun countDistinctMonths(): Long

	@Query("SELECT COUNT(DISTINCT strftime('%w', date_epoch_day * 86400, 'unixepoch')) FROM daily_summary WHERE trip_count > 0")
	suspend fun countDistinctWeekdays(): Long

	@Query("SELECT COALESCE(SUM(total_distance_m), 0) FROM daily_summary WHERE date_epoch_day >= :fromEpochDay")
	suspend fun sumDistanceSinceEpochDay(fromEpochDay: Long): Long

	@Query("SELECT COUNT(*) FROM daily_summary WHERE date_epoch_day >= :fromEpochDay AND trip_count > 0")
	suspend fun countActiveDaysSinceEpochDay(fromEpochDay: Long): Long
}

/** Milliseconds per (UTC) day. */
private const val MS_PER_DAY: Long = 86_400_000L

/**
 * Convert a window-start epoch-millis to the inclusive epoch-day lower bound:
 * the first day whose midnight is at or after [fromMs]. Used by [DailySummaryDao]
 * range queries so the `date_epoch_day` index is usable.
 */
fun fromMsToFromDay(fromMs: Long): Long = (fromMs + MS_PER_DAY - 1) / MS_PER_DAY

/**
 * Convert a window-end epoch-millis to the inclusive epoch-day upper bound:
 * the last day whose midnight is at or before [toMs].
 */
fun toMsToToDay(toMs: Long): Long = toMs / MS_PER_DAY
