package com.adsamcik.tracker.game.challenge.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.database.typeconverter.ChallengeDifficultyStringTypeConverter
import com.adsamcik.tracker.game.challenge.database.typeconverter.ChallengeTypeConverter

/**
 * Unified entity replacing ChallengeEntry + 4 type-specific entities.
 * Uses entity-level string converters to override the database-level ordinal converters.
 */
@Entity(
	tableName = "challenge",
	indices = [Index(value = ["is_completed", "end_time"])]
)
@TypeConverters(ChallengeTypeConverter::class, ChallengeDifficultyStringTypeConverter::class)
data class ChallengeEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "type") val type: ChallengeType,
	@ColumnInfo(name = "start_time") val startTime: Long,
	@ColumnInfo(name = "end_time") val endTime: Long,
	val difficulty: ChallengeDifficulty,
	@ColumnInfo(name = "required_value") val requiredValue: Double,
	@ColumnInfo(name = "current_value") var currentValue: Double = 0.0,
	@ColumnInfo(name = "is_completed") var isCompleted: Boolean = false,
	@ColumnInfo(name = "extra_json") val extraJson: String? = null,
)
