package com.adsamcik.signalcollector.game.challenge.data.instance

import android.content.Context
import androidx.room.PrimaryKey
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.database.dao.LocationDataDao
import com.adsamcik.signalcollector.common.database.data.DatabaseLocation
import com.adsamcik.signalcollector.game.challenge.data.ChallengeDefinition
import com.adsamcik.signalcollector.game.challenge.data.ChallengeInstance
import com.adsamcik.signalcollector.game.challenge.data.entity.ExplorerChallengeEntity
import com.adsamcik.signalcollector.game.challenge.data.persistence.ExplorerChallengePersistence
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntry

class ExplorerChallengeInstance(entry: ChallengeEntry,
                                definition: ChallengeDefinition<ExplorerChallengeInstance>,
                                data: ExplorerChallengeEntity)
	: ChallengeInstance<ExplorerChallengeEntity, ExplorerChallengeInstance>(entry, definition, data) {

	override val persistence = ExplorerChallengePersistence()

	override val progress: Double
		get() = extra.locationCount / extra.requiredLocationCount.toDouble()

	@PrimaryKey
	var id: Int = 0

	override fun getDescription(context: Context): String {
		return context.getString(definition.descriptionRes, extra.requiredLocationCount)
	}

	override fun checkCompletionConditions() = extra.locationCount >= extra.requiredLocationCount

	private fun countUnique(dao: LocationDataDao, locations: List<DatabaseLocation>, time: Long): Int {
		val newList = locations.map {
			val rounded = it.location.roundTo(ACCURACY_IN_METERS)
			rounded.latitude to rounded.longitude
		}.distinctBy { it }
		return dao.newLocations(newList, time, ACCURACY_IN_METERS).size
	}

	override fun processSession(context: Context, session: TrackerSession) {
		val dao = AppDatabase.getDatabase(context).locationDao()
		val locationList = dao.getAllBetween(session.start, session.end)
		extra.locationCount += countUnique(dao, locationList, session.start)
	}

	companion object {
		private const val ACCURACY_IN_METERS = 20.0
	}
}