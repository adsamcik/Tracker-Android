package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
	tableName = "personal_record",
	indices = [Index(value = ["metric"], unique = true)]
)
data class PersonalRecordEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	val metric: String,
	val value: Double,
	@ColumnInfo(name = "achieved_at") val achievedAt: Long,
	@ColumnInfo(name = "updated_at") val updatedAt: Long
)
