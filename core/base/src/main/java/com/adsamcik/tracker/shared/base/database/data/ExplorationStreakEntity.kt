package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks exploration streaks (consecutive days/weeks of discovering new cells).
 * Uses streak type as primary key (only one row per streak type).
 */
@Entity(tableName = "exploration_streak")
data class ExplorationStreakEntity(
	/** Streak type: "DAILY_DISCOVERY", "WEEKLY_EXPLORER", "CHAIN" */
	@PrimaryKey
	@ColumnInfo(name = "type")
	val type: String,

	/** Current streak count */
	@ColumnInfo(name = "current_count", defaultValue = "0")
	val currentCount: Int = 0,

	/** Best streak count ever achieved */
	@ColumnInfo(name = "best_count", defaultValue = "0")
	val bestCount: Int = 0,

	/** Last epoch day (days since epoch) when streak was incremented */
	@ColumnInfo(name = "last_increment_day", defaultValue = "0")
	val lastIncrementDay: Long = 0,

	/** Last update timestamp in millis */
	@ColumnInfo(name = "updated_at", defaultValue = "0")
	val updatedAt: Long = 0
)
