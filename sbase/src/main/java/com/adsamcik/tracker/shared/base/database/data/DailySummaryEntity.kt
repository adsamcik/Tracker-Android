package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Materialized daily aggregate. One row per calendar day.
 * Updated by [com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryAggregator]
 * at session end (via TrackingOrchestrator) and periodically by DailySummaryMaterializationWorker.
 */
@Entity(
	tableName = "daily_summary",
	indices = [
		Index(value = ["date_epoch_day"], unique = true)
	]
)
data class DailySummaryEntity(
	@PrimaryKey
	@ColumnInfo(name = "date_epoch_day")
	val dateEpochDay: Long,

	@ColumnInfo(name = "total_distance_m")
	val totalDistanceM: Float,

	@ColumnInfo(name = "total_steps")
	val totalSteps: Int,

	@ColumnInfo(name = "total_duration_ms")
	val totalDurationMs: Long,

	@ColumnInfo(name = "trip_count")
	val tripCount: Int,

	@ColumnInfo(name = "active_tracking_ms")
	val activeTrackingMs: Long,

	@ColumnInfo(name = "last_updated_ms")
	val lastUpdatedMs: Long,

	@ColumnInfo(name = "created_at")
	val createdAt: Long
)
