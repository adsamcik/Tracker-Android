package com.adsamcik.tracker.game.challenge.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Permanent record of a completed or expired challenge.
 * Used for trophy case, lifetime stats, and personal records.
 */
@Entity(
	tableName = "challenge_history",
	indices = [
		Index(value = ["outcome"]),
		Index(value = ["medal"])
	]
)
data class ChallengeHistoryEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "challenge_type") val challengeType: String,
	val difficulty: String,
	@ColumnInfo(name = "start_time") val startTime: Long,
	@ColumnInfo(name = "end_time") val endTime: Long,
	val outcome: String,
	@ColumnInfo(name = "completed_at") val completedAt: Long? = null,
	@ColumnInfo(name = "progress_value") val progressValue: Double,
	@ColumnInfo(name = "target_value") val targetValue: Double,
	val medal: String? = null,
	@ColumnInfo(name = "xp_awarded") val xpAwarded: Int = 0,
	@ColumnInfo(name = "original_challenge_id") val originalChallengeId: Long? = null,
)
