package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table for real-time dashboard stats during active tracking.
 * Updated every ~30s by StreamingAggregator. Cleared on tracking stop.
 */
@Entity(tableName = "live_stats")
data class LiveStatsEntity(
	@PrimaryKey
	val id: Int = 0,

	@ColumnInfo(name = "date_epoch_day")
	val dateEpochDay: Long,

	@ColumnInfo(name = "session_distance_m")
	val sessionDistanceM: Float,

	@ColumnInfo(name = "session_steps")
	val sessionSteps: Int,

	@ColumnInfo(name = "session_duration_ms")
	val sessionDurationMs: Long,

	@ColumnInfo(name = "day_total_distance_m")
	val dayTotalDistanceM: Float,

	@ColumnInfo(name = "day_total_steps")
	val dayTotalSteps: Int,

	@ColumnInfo(name = "day_total_duration_ms")
	val dayTotalDurationMs: Long,

	@ColumnInfo(name = "last_updated_ms")
	val lastUpdatedMs: Long
)
