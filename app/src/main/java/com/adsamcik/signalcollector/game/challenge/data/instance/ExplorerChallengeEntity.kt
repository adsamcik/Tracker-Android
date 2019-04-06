package com.adsamcik.signalcollector.game.challenge.data.instance

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.adsamcik.signalcollector.game.challenge.ChallengeDifficulty
import com.adsamcik.signalcollector.game.challenge.data.progress.ExplorerChallengeProgressData
import com.adsamcik.signalcollector.game.challenge.database.ChallengeDatabase
import com.adsamcik.signalcollector.tracker.data.TrackerSession

@Entity(tableName = "explorer_challenge")
class ExplorerChallengeEntity(context: Context,
                              name: String,
                              description: String,
                              difficulty: ChallengeDifficulty,
                              startTime: Long,
                              endTime: Long,
                              @ColumnInfo(name = "req_loc_count")
                              private val requiredLocationCount: Int,
                              progressData: ExplorerChallengeProgressData) : Challenge<ExplorerChallengeProgressData>(difficulty, name, description, startTime, endTime, progressData) {

	private val dao = ChallengeDatabase.getAppDatabase(context).explorerDao()

	@PrimaryKey
	var id: Int = 0

	override val description: String
		get() = description.format(progressData.locationCount)

	override fun batchProcess(session: TrackerSession) {
		val newLocationCount = dao.newLocationsBetween(session.start, session.end)
		progressData.locationCount += newLocationCount
		if (progressData.locationCount >= requiredLocationCount)
			progressData.isCompleted = true
	}

}