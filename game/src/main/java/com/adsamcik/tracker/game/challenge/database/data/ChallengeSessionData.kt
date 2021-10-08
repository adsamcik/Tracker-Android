package com.adsamcik.tracker.game.challenge.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Processing state of session data in challenges.
 */
@Entity(tableName = "challenge_session_data")
data class ChallengeSessionData(
		@PrimaryKey
		val id: Long = 0,
		@ColumnInfo(name = "challenge_processed")
		var isChallengeProcessed: Boolean
)

