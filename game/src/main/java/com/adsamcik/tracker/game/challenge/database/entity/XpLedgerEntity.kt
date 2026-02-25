package com.adsamcik.tracker.game.challenge.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Immutable log of every XP award. Source of truth for total XP.
 */
@Entity(tableName = "xp_ledger")
data class XpLedgerEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	val amount: Int,
	val source: String,
	@ColumnInfo(name = "source_id") val sourceId: Long? = null,
	@ColumnInfo(name = "earned_at") val earnedAt: Long,
)
