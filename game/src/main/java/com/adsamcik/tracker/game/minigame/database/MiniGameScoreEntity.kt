package com.adsamcik.tracker.game.minigame.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Game-agnostic high score record. Keyed by game_id string.
 */
@Entity(tableName = "minigame_score")
data class MiniGameScoreEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "game_id") val gameId: String,
	val score: Double,
	@ColumnInfo(name = "xp_awarded") val xpAwarded: Int,
	@ColumnInfo(name = "played_at") val playedAt: Long,
)
