package com.adsamcik.tracker.stats.data.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.data.SessionSegmentStats
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.repository.SessionStatsRepository
import com.adsamcik.tracker.stats.api.repository.SessionStatsSnapshot
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import javax.inject.Inject

class DefaultSessionStatsRepository @Inject constructor(
	private val sessionSegmentDao: SessionSegmentDao,
	private val tripDao: TripDao,
	private val locationSampleDao: LocationSampleDao,
	private val wifiObservationDao: WifiObservationDao,
	private val cellSampleDao: CellSampleDao,
	private val dispatchers: DispatchersProvider,
) : SessionStatsRepository {

	override suspend fun getAllTime(): Either<StatsError, SessionStatsSnapshot> = withContext(dispatchers.io) {
		try {
			sessionSegmentDao.getSummary(
				onFootActivities = ON_FOOT_ACTIVITY_TYPES,
				inVehicleActivities = IN_VEHICLE_ACTIVITY_TYPES,
			).toSnapshot(
				tripCount = tripDao.countAllTrips(),
				locationCount = locationSampleDao.countAll(),
				wifiCount = wifiObservationDao.countDistinctBssid(),
				cellCount = cellSampleDao.uniqueCount(),
			).right()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			StatsError.DatabaseError("Failed to load summary stats: ${e.message}", e).left()
		}
	}

	override suspend fun getBetween(
		fromMs: EpochMs,
		toMs: EpochMs,
	): Either<StatsError, SessionStatsSnapshot> = withContext(dispatchers.io) {
		try {
			sessionSegmentDao.getSummaryBetween(
				fromMs = fromMs.raw,
				toMs = toMs.raw,
				onFootActivities = ON_FOOT_ACTIVITY_TYPES,
				inVehicleActivities = IN_VEHICLE_ACTIVITY_TYPES,
			).toSnapshot(
				tripCount = tripDao.countTripsBetween(fromMs.raw, toMs.raw),
				locationCount = locationSampleDao.countBetween(fromMs.raw, toMs.raw).toLong(),
				wifiCount = wifiObservationDao.countDistinctBssid(fromMs.raw, toMs.raw),
				cellCount = cellSampleDao.uniqueCount(fromMs.raw, toMs.raw),
			).right()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			StatsError.DatabaseError("Failed to load session stats: ${e.message}", e).left()
		}
	}

	private fun SessionSegmentStats.toSnapshot(
		tripCount: Long,
		locationCount: Long,
		wifiCount: Long,
		cellCount: Long,
	): SessionStatsSnapshot {
		return SessionStatsSnapshot(
			duration = DurationMs(durationMs.coerceAtLeast(0L)),
			collections = collectionCount.coerceAtLeast(0L),
			totalDistance = DistanceM.coerced(distanceM),
			onFootDistance = DistanceM.coerced(onFootDistanceM),
			inVehicleDistance = DistanceM.coerced(inVehicleDistanceM),
			steps = StepCount.coerced(stepCount.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()),
			tripCount = tripCount.coerceAtLeast(0L),
			locationCount = locationCount.coerceAtLeast(0L),
			wifiCount = wifiCount.coerceAtLeast(0L),
			cellCount = cellCount.coerceAtLeast(0L),
		)
	}

	private companion object {
		val ON_FOOT_ACTIVITY_TYPES = listOf(
			DetectedActivity.WALKING.value,
			DetectedActivity.RUNNING.value,
			DetectedActivity.ON_FOOT.value,
		)
		val IN_VEHICLE_ACTIVITY_TYPES = listOf(
			DetectedActivity.IN_VEHICLE.value,
			DetectedActivity.ON_BICYCLE.value,
		)
	}
}
