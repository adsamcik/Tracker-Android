package com.adsamcik.tracker.stats.data.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.SessionActivityIds
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.SessionSegmentStats
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.repository.SessionStatsRepository
import com.adsamcik.tracker.stats.api.repository.SessionStatsSnapshot
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import javax.inject.Inject

class DefaultSessionStatsRepository @Inject constructor(
	private val sessionSegmentDao: SessionSegmentDao,
	private val tripDao: TripDao,
	private val locationSampleDao: LocationSampleDao,
	private val dispatchers: DispatchersProvider,
	private val savedLocationMetrics: SavedLocationMetricsReader,
	private val savedRadioCounts: SavedRadioCountsReader,
) : SessionStatsRepository {

	override suspend fun getAllTime(): Either<StatsError, SessionStatsSnapshot> = withContext(dispatchers.io) {
		try {
			sessionSegmentDao.getSummary(
				onFootActivities = ON_FOOT_ACTIVITY_TYPES,
				inVehicleActivities = IN_VEHICLE_ACTIVITY_TYPES,
			).toSnapshot(
				location = availableLocation(),
				tripCount = tripDao.countAllTrips(),
				locationCount = locationSampleDao.countAll(),
				wifiCount = savedRadioCounts.read(HistorySource.WIFI),
				cellCount = savedRadioCounts.read(HistorySource.CELL),
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
				location = availableLocation(fromMs.raw, toMs.raw),
				tripCount = tripDao.countTripsBetween(fromMs.raw, toMs.raw),
				locationCount = locationSampleDao.countBetween(fromMs.raw, toMs.raw).toLong(),
				wifiCount = savedRadioCounts.read(HistorySource.WIFI, fromMs.raw, toMs.raw),
				cellCount = savedRadioCounts.read(HistorySource.CELL, fromMs.raw, toMs.raw),
			).right()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			StatsError.DatabaseError("Failed to load session stats: ${e.message}", e).left()
		}
	}

	private fun SessionSegmentStats.toSnapshot(
		location: SavedLocationMetrics?,
		tripCount: Long,
		locationCount: Long,
		wifiCount: SavedRadioCount,
		cellCount: SavedRadioCount,
	): SessionStatsSnapshot {
		return SessionStatsSnapshot(
			duration = DurationMs(durationMs.coerceAtLeast(0L)),
			collections = collectionCount.coerceAtLeast(0L),
			totalDistance = DistanceM.coerced((location?.distanceMeters ?: 0.0).toFloat()),
			onFootDistance = DistanceM.coerced((location?.onFootDistanceMeters ?: 0.0).toFloat()),
			inVehicleDistance = DistanceM.coerced((location?.vehicleDistanceMeters ?: 0.0).toFloat()),
			tripCount = tripCount.coerceAtLeast(0L),
			locationCount = locationCount.coerceAtLeast(0L),
			wifiCount = wifiCount.count,
			cellCount = cellCount.count,
			hasMeasuredDistance = location?.distanceMeters != null,
			hasActivityDistances = location?.hasActivityDistances == true,
			distanceIsPartial = location?.partial == true,
			wifiCountIsPartial = wifiCount.partial,
			cellCountIsPartial = cellCount.partial,
		)
	}

	private suspend fun availableLocation(fromMs: Long = 0L, toMs: Long = Long.MAX_VALUE): SavedLocationMetrics? =
		try { savedLocationMetrics.read(fromMs, toMs) }
		catch (cancelled: CancellationException) { throw cancelled }
		catch (_: Exception) { null }

	private companion object {
		val ON_FOOT_ACTIVITY_TYPES = SessionActivityIds.ON_FOOT.toList()
		val IN_VEHICLE_ACTIVITY_TYPES = SessionActivityIds.IN_VEHICLE.toList()
	}
}
