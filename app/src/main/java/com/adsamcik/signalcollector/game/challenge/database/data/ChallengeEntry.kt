package com.adsamcik.signalcollector.game.challenge.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.adsamcik.signalcollector.game.challenge.ChallengeDifficulty

@Entity(tableName = "entry")
data class ChallengeEntry(val name: String,
                          @ColumnInfo(name = "start_time")
                          val startTime: Long,
                          @ColumnInfo(name = "end_time")
                          val endTime: Long,
                          val difficulty: ChallengeDifficulty) {

	@PrimaryKey(autoGenerate = true)
	var id: Long = 0L
}