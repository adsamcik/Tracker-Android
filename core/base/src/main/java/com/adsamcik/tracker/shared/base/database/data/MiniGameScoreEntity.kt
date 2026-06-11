package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Game-agnostic high score record. Keyed by game_id string.
 *
 * The `played_at DESC` index backs `MiniGameScoreDao.getRecent(limit)` which
 * drives the dashboard "recent runs" panel; without it Room would scan the
 * entire score history every time the dashboard recomposes. Added in
 * AppDatabase v31 (see [com.adsamcik.tracker.shared.base.database.MIGRATION_30_31]).
 */
@Entity(
	tableName = "minigame_score",
	indices = [
		Index(value = ["played_at"], name = "idx_minigame_score_played_at"),
	],
)
data class MiniGameScoreEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "game_id") val gameId: String,
	val score: Double,
	@ColumnInfo(name = "xp_awarded") val xpAwarded: Int,
	@ColumnInfo(name = "played_at") val playedAt: Long,
)
