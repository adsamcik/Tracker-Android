package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton player profile caching XP and level.
 * Derived from xp_ledger sum but cached for fast reads.
 */
@Entity(tableName = "player_profile")
data class PlayerProfileEntity(
	@PrimaryKey val id: Int = 1,
	@ColumnInfo(name = "total_xp") val totalXp: Long = 0,
	val level: Int = 1,
	@ColumnInfo(name = "xp_into_current_level") val xpIntoCurrentLevel: Long = 0,
	@ColumnInfo(name = "xp_for_next_level") val xpForNextLevel: Long = 30,
)
