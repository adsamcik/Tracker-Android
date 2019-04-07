package com.adsamcik.signalcollector.game.challenge.data.instance

import android.content.Context
import androidx.room.PrimaryKey
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntry
import com.adsamcik.signalcollector.game.challenge.database.data.extra.ExplorerChallengeEntry
import com.adsamcik.signalcollector.tracker.data.TrackerSession
import kotlin.math.roundToInt

class ExplorerChallengeInstance(context: Context,
                                entry: ChallengeEntry,
                                title: String,
                                descriptionTemplate: String,
                                data: ExplorerChallengeEntry)
	: ChallengeInstance<ExplorerChallengeEntry>(entry, title, descriptionTemplate, data) {

	override val progress: Int
		get() = (extra.locationCount / extra.requiredLocationCount.toDouble()).roundToInt().coerceAtMost(100)

	private val dao = AppDatabase.getAppDatabase(context).challengeDao()

	@PrimaryKey
	var id: Int = 0

	override val description: String
		get() = descriptionTemplate.format(extra.requiredLocationCount)

	override fun batchProcess(session: TrackerSession) {
		val newLocationCount = dao.newLocationsBetween(session.start, session.end)
		extra.locationCount += newLocationCount
		if (extra.locationCount >= extra.requiredLocationCount)
			extra.isCompleted = true
	}

}