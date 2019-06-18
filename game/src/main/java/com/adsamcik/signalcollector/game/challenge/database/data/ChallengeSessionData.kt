package com.adsamcik.signalcollector.game.challenge.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "challenge_session_data")
class ChallengeSessionData(
		@PrimaryKey
		val id: Long = 0,
		@ColumnInfo(name = "challenge_processed")
		var isChallengeProcessed: Boolean)