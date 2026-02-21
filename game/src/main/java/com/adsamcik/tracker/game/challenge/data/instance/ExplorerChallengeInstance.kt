package com.adsamcik.tracker.game.challenge.data.instance

import android.content.Context
import androidx.room.PrimaryKey
import com.adsamcik.tracker.game.challenge.data.ChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.ChallengeInstance
import com.adsamcik.tracker.game.challenge.data.entity.ExplorerChallengeEntity
import com.adsamcik.tracker.game.challenge.data.persistence.ExplorerChallengePersistence
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.shared.base.data.Location
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
		get() = if (extra.requiredLocationCount > 0) {
			(extra.locationCount / extra.requiredLocationCount.toDouble()).coerceIn(0.0, 1.0)
		} else {
			0.0
		}

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
		if (locations.isEmpty()) {
			return 0
		}

		// Round and distinct the new locations
		val newLocations = locations.map {
			Location.roundTo(it.latitude, ACCURACY_IN_METERS, it.longitude, ACCURACY_IN_METERS)
		}.distinct()

		// Calculate bounding box for the new locations
		val minLat = newLocations.minOf { it.latitude } - Location.latitudeAccuracy(ACCURACY_IN_METERS)
		val maxLat = newLocations.maxOf { it.latitude } + Location.latitudeAccuracy(ACCURACY_IN_METERS)
		val minLon = newLocations.minOf { it.longitude } - Location.longitudeAccuracy(ACCURACY_IN_METERS, minLat)
		val maxLon = newLocations.maxOf { it.longitude } + Location.longitudeAccuracy(ACCURACY_IN_METERS, maxLat)

		// Fetch existing locations within the bounding box and time range
		val existingLocations = dao.getAllInsideAndBetween(
			from = 0,
			to = time,
			topLatitude = maxLat,
			rightLongitude = maxLon,
			bottomLatitude = minLat,
			leftLongitude = minLon
		).map {
			Location.roundTo(it.latitude, ACCURACY_IN_METERS, it.longitude, ACCURACY_IN_METERS)
		}.toSet()

		// Filter out locations that already exist
		val uniqueNewLocations = newLocations.filter { it !in existingLocations }

		return uniqueNewLocations.size
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

