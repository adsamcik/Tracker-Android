package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Personal record per challenge type and metric.
 * E.g., "fastest Step challenge completion" or "most steps in a Step challenge".
 */
@Entity(
	tableName = "challenge_personal_record",
	foreignKeys = [
		ForeignKey(
			entity = ChallengeHistoryEntity::class,
			parentColumns = ["id"],
			childColumns = ["history_id"],
			onDelete = ForeignKey.SET_NULL,
		),
	],
	indices = [
		Index(value = ["challenge_type", "metric"], unique = true),
		Index(value = ["history_id"]),
	],
)
data class ChallengePersonalRecordEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "challenge_type") val challengeType: String,
	val metric: String,
	val value: Double,
	@ColumnInfo(name = "history_id") val historyId: Long? = null,
	@ColumnInfo(name = "achieved_at") val achievedAt: Long,
)
