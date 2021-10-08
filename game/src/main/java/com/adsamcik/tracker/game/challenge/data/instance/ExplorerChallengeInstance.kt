package com.adsamcik.tracker.game.challenge.data.instance

import android.content.Context
import androidx.room.PrimaryKey
import com.adsamcik.tracker.game.challenge.data.ChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.ChallengeInstance
import com.adsamcik.tracker.game.challenge.data.entity.ExplorerChallengeEntity
import com.adsamcik.tracker.game.challenge.data.persistence.ExplorerChallengePersistence
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationDataDao
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation

class ExplorerChallengeInstance(
		entry: ChallengeEntry,
		definition: ChallengeDefinition<ExplorerChallengeInstance>,
		data: ExplorerChallengeEntity
) : ChallengeInstance<ExplorerChallengeEntity, ExplorerChallengeInstance>(entry, definition, data) {

	override val persistence: ExplorerChallengePersistence = ExplorerChallengePersistence()

	override val progress: Double
		get() = extra.locationCount / extra.requiredLocationCount.toDouble()

	@PrimaryKey
	var id: Int = 0

	override fun getDescription(context: Context): String {
		return context.getString(definition.descriptionRes, extra.requiredLocationCount)
	}

	override fun checkCompletionConditions(): Boolean = extra.locationCount >= extra.requiredLocationCount

	private fun countUnique(
			dao: LocationDataDao,
			locations: List<DatabaseLocation>,
			time: Long
	): Int {
		val newList = locations.map {
			val rounded = it.location.roundTo(ACCURACY_IN_METERS)
			rounded.latitude to rounded.longitude
		}.distinctBy { it }
		return dao.newLocations(newList, time, ACCURACY_IN_METERS).size
	}

	override fun processSession(context: Context, session: TrackerSession) {
		val dao = AppDatabase.database(context).locationDao()
		val locationList = dao.getAllBetween(session.start, session.end)
		extra.locationCount += countUnique(dao, locationList, session.start)
	}

	companion object {
		private const val ACCURACY_IN_METERS = 20.0
	}
}

