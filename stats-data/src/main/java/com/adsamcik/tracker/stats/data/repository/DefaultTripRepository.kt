package com.adsamcik.tracker.stats.data.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.repository.TripRepository
import com.adsamcik.tracker.stats.api.repository.TripSummary
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class DefaultTripRepository @Inject constructor(
	private val tripDao: TripDao,
) : TripRepository {

	override fun observeTrips(): Flow<List<TripSummary>> =
		tripDao.getRecentTripsFlow(100).map { trips -> trips.map { it.toSummary() } }

	override fun observeTripsBetween(from: EpochMs, to: EpochMs): Flow<List<TripSummary>> = flow {
		val trips = tripDao.getBetween(from.raw, to.raw)
		emit(trips.map { it.toSummary() })
	}

	override suspend fun getTripDetail(id: Long): Either<StatsError, TripSummary> {
		return try {
			val trip = tripDao.getById(id)
			if (trip != null) {
				trip.toSummary().right()
			} else {
				StatsError.NotFound("Trip not found", "Trip", id.toString()).left()
			}
		} catch (e: Exception) {
			StatsError.DatabaseError("Failed to load trip: ${e.message}", e).left()
		}
	}

	private fun com.adsamcik.tracker.shared.base.database.data.Trip.toSummary(): TripSummary {
		return TripSummary(
			id = id,
			startTimeMs = EpochMs(startTimeMs),
			endTimeMs = EpochMs(endTimeMs),
			distance = DistanceM.coerced(distanceM),
			steps = StepCount.coerced(steps ?: 0),
			duration = DurationMs((endTimeMs - startTimeMs).coerceAtLeast(0L)),
			primaryMode = resolveTransportMode(),
			sampleCount = sampleCount,
		)
	}

	private fun com.adsamcik.tracker.shared.base.database.data.Trip.resolveTransportMode(): TransportMode =
		when (primaryActivity) {
			7 -> TransportMode.WALK
			8 -> TransportMode.RUN
			1 -> TransportMode.CYCLE
			0 -> TransportMode.DRIVE
			else -> when (source) {
				com.adsamcik.tracker.shared.base.database.data.SegmentSource.INFERRED_HIGH_CONFIDENCE,
				com.adsamcik.tracker.shared.base.database.data.SegmentSource.INFERRED_MEDIUM_CONFIDENCE ->
					TransportMode.TRANSIT
				else -> TransportMode.UNKNOWN
			}
		}
}
