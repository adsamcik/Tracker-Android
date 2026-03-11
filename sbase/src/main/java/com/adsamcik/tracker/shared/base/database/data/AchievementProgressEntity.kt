package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
	tableName = "achievement_progress",
	indices = [
		Index(value = ["achievement_id"], unique = true),
		Index(value = ["updated_at"]),
		Index(value = ["unlocked_at"])
	]
)
data class AchievementProgressEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "achievement_id") val achievementId: String,
	@ColumnInfo(name = "current_value") val currentValue: Long = 0,
	@ColumnInfo(name = "target_value") val targetValue: Long,
	val tier: Int = 0,
	@ColumnInfo(name = "unlocked_at") val unlockedAt: Long? = null,
	@ColumnInfo(name = "updated_at") val updatedAt: Long = 0
)
