package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "achievement_progress", indices = [Index(value = ["metric_key"], unique = true)])
data class AchievementProgressEntity(
	@PrimaryKey @ColumnInfo(name = "metric_key") val metricKey: String,
	@ColumnInfo(name = "last_tier_index") val lastTierIndex: Int = -1,
	@ColumnInfo(name = "last_value") val lastValue: Double = 0.0,
	@ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
)
