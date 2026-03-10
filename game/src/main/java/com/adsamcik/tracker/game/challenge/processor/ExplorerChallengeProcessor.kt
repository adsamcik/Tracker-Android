package com.adsamcik.tracker.game.challenge.processor

import android.content.Context
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import javax.inject.Inject

class ExplorerChallengeProcessor @Inject constructor() : ChallengeProcessor {
	override val type: ChallengeType = ChallengeType.Explorer

	override val titleRes: Int = R.string.challenge_explorer_title

	override fun formatDescription(context: Context, entity: ChallengeEntity): String {
		return context.getString(
			R.string.challenge_explorer_description,
			entity.requiredValue.toInt().toString()
		)
	}

	override fun extractProgress(context: Context, session: TrackerSession): Double {
		val dao = AppDatabase.database(context).locationSampleDao()
		val locations = kotlinx.coroutines.runBlocking {
			dao.getAllBetween(session.start, session.end)
		}
		// Limit lookback to 6 months (fix for #103)
		val lookbackStart = session.start - (180L * 24 * 60 * 60 * 1000)
		return countUniqueLocations(dao, locations, lookbackStart, session.start).toDouble()
	}

	private fun countUniqueLocations(
		dao: LocationSampleDao,
		locations: List<LocationSample>,
		from: Long,
		to: Long
	): Int {
		if (locations.isEmpty()) {
			return 0
		}

		val newLocations = locations.mapNotNull { sample ->
			val lat = sample.latE7?.div(1e7) ?: return@mapNotNull null
			val lon = sample.lonE7?.div(1e7) ?: return@mapNotNull null
			Location.roundTo(lat, ACCURACY_IN_METERS, lon, ACCURACY_IN_METERS)
		}.distinct()

		if (newLocations.isEmpty()) return 0

		val minLat = newLocations.minOf { it.latitude } - Location.latitudeAccuracy(ACCURACY_IN_METERS)
		val maxLat = newLocations.maxOf { it.latitude } + Location.latitudeAccuracy(ACCURACY_IN_METERS)
		val minLon = newLocations.minOf { it.longitude } - Location.longitudeAccuracy(ACCURACY_IN_METERS, minLat)
		val maxLon = newLocations.maxOf { it.longitude } + Location.longitudeAccuracy(ACCURACY_IN_METERS, maxLat)

		// Filter in-memory since LocationSampleDao lacks a spatial query
		val existingLocations = kotlinx.coroutines.runBlocking { dao.getAllBetween(from, to) }
			.mapNotNull { sample ->
				val lat = sample.latE7?.div(1e7) ?: return@mapNotNull null
				val lon = sample.lonE7?.div(1e7) ?: return@mapNotNull null
				if (lat in minLat..maxLat && lon in minLon..maxLon) {
					Location.roundTo(lat, ACCURACY_IN_METERS, lon, ACCURACY_IN_METERS)
				} else {
					null
				}
			}.toSet()

		val uniqueNewLocations = newLocations.filter { it !in existingLocations }

		return uniqueNewLocations.size
	}

	override val defaultRequiredValue: Double = 100.0

	override val defaultDurationMs: Long = 7L * 24 * 60 * 60 * 1000

	companion object {
		private const val ACCURACY_IN_METERS = 20.0
	}
}
