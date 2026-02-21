package com.adsamcik.tracker.game.challenge.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton challenge completion streak tracker.
 * Freeze earned every 7 completions, max 3.
 */
@Entity(tableName = "challenge_streak")
data class ChallengeStreakEntity(
	@PrimaryKey val id: Int = 1,
	@ColumnInfo(name = "current_count") val currentCount: Int = 0,
	@ColumnInfo(name = "best_count") val bestCount: Int = 0,
	@ColumnInfo(name = "last_completion_time") val lastCompletionTime: Long = 0,
	@ColumnInfo(name = "freeze_count") val freezeCount: Int = 0,
)
