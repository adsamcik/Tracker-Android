package com.adsamcik.signalcollector.game.challenge.data.instance

import android.content.Context
import androidx.room.PrimaryKey
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.database.data.DatabaseLocation
import com.adsamcik.signalcollector.game.challenge.data.ChallengeInstance
import com.adsamcik.signalcollector.game.challenge.data.entity.ExplorerChallengeEntity
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntry

class ExplorerChallengeInstance(context: Context,
                                entry: ChallengeEntry,
                                title: String,
                                descriptionTemplate: String,
                                data: ExplorerChallengeEntity)
	: ChallengeInstance<ExplorerChallengeEntity>(entry, title, descriptionTemplate, data) {

	override val progress: Double
		get() = extra.locationCount / extra.requiredLocationCount.toDouble()

	private val dao = AppDatabase.getDatabase(context).locationDao()

	@PrimaryKey
	var id: Int = 0

	override val description: String
		get() = descriptionTemplate.format(extra.requiredLocationCount)

	override fun checkCompletionConditions() = extra.locationCount >= extra.requiredLocationCount

	private fun countUnique(locations: List<DatabaseLocation>, time: Long): Int {
		val accuracyInM = 20.0
		val newList = locations.map {
			val rounded = it.location.roundTo(accuracyInM)
			rounded.latitude to rounded.longitude
		}.distinctBy { it }
		return dao.newLocations(newList, time, accuracyInM).size
	}

	override fun processSession(session: TrackerSession) {
		val locationList = dao.getAllBetween(session.start, session.end)
		extra.locationCount += countUnique(locationList, session.start)
	}
}