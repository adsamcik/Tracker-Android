package com.adsamcik.tracker.game.challenge.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Personal record per challenge type and metric.
 * E.g., "fastest Step challenge completion" or "most steps in a Step challenge".
 */
@Entity(tableName = "challenge_personal_record")
data class ChallengePersonalRecordEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "challenge_type") val challengeType: String,
	val metric: String,
	val value: Double,
	@ColumnInfo(name = "history_id") val historyId: Long? = null,
	@ColumnInfo(name = "achieved_at") val achievedAt: Long,
)
