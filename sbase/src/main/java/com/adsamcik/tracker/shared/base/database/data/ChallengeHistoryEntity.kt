package com.adsamcik.tracker.shared.base.database.data

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
		Index(value = ["medal"]),
		// p5-2: completed_at is the dominant ORDER BY in observeAll / getCompleted / getByMedal.
		// Index materially speeds up trophy case + history list scrolling once history grows.
		Index(value = ["completed_at"])
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
