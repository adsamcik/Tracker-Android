package com.adsamcik.tracker.stats.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.repository.TripPresentationRepository
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.repository.TripRepository
import com.adsamcik.tracker.stats.api.repository.TripSummary
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import javax.inject.Inject

class DefaultTripRepository @Inject constructor(
	private val tripDao: TripDao,
	private val savedLocationMetrics: SavedLocationMetricsReader,
) : TripRepository, TripPresentationRepository {

	override fun observeTrips(): Flow<List<TripSummary>> = flow {
        val measurements = savedLocationMetrics.tripMeasurements()
        val changes = savedLocationMetrics.observeTableChanges().onEach { measurements.changed(it) }.map { }
        combine(tripDao.getRecentTripsFlow(100), changes) { trips, _ ->
            measurements.keepOnly(trips.map { it.id }.toSet())
            trips.map { measuredTrip(it, measurements::read).toSummary() }
        }.collect { emit(it) }
    }

	override fun observeTripsBetween(from: EpochMs, to: EpochMs): Flow<List<TripSummary>> = flow {
        val measurements = savedLocationMetrics.tripMeasurements()
        savedLocationMetrics.observeTableChanges().collect { tables ->
            measurements.changed(tables)
            val trips = tripDao.getBetween(from.raw, to.raw)
            measurements.keepOnly(trips.map { it.id }.toSet())
            emit(trips.map { measuredTrip(it, measurements::read).toSummary() })
        }
    }

	override suspend fun getTripDetail(id: Long): Either<StatsError, TripSummary> {
		return try {
			val trip = tripDao.getById(id)
			if (trip != null) {
				measuredTrip(trip).toSummary().right()
			} else {
				StatsError.NotFound("Trip not found", "Trip", id.toString()).left()
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (e: Exception) {
			StatsError.DatabaseError("Failed to load trip: ${e.message}", e).left()
		}
	}

	override fun getPagedTrips(): PagingSource<Int, com.adsamcik.tracker.shared.model.Trip> =
        pagedTrips(tripDao.getAllPaged())

	override fun getPagedTripsOverlapping(
		fromMs: Long,
		toMs: Long,
	): PagingSource<Int, com.adsamcik.tracker.shared.model.Trip> =
        pagedTrips(tripDao.getPagedOverlapping(fromMs, toMs))

    private fun pagedTrips(delegate: PagingSource<Int, com.adsamcik.tracker.shared.base.database.data.Trip>):
        PagingSource<Int, com.adsamcik.tracker.shared.model.Trip> {
        val measurements = savedLocationMetrics.tripMeasurements()
        return TripModelPagingSource(delegate, { measuredTrip(it, measurements::read) }, measurements,
            savedLocationMetrics::onSavedTablesChange, savedLocationMetrics.readDispatcher)
    }

	override suspend fun getTripsBetween(fromMs: Long, toMs: Long) =
		tripDao.getBetween(fromMs, toMs).map { measuredTrip(it) }

	override suspend fun getTripProjection(id: Long) = tripDao.getById(id)?.let { measuredTrip(it) }

	private suspend fun measuredTrip(trip: com.adsamcik.tracker.shared.base.database.data.Trip,
        readLocation: suspend (Long) -> com.adsamcik.tracker.stats.api.repository.SavedLocationSummary =
            savedLocationMetrics::readTrip):
		com.adsamcik.tracker.shared.model.Trip {
		if (trip.source == com.adsamcik.tracker.shared.model.SegmentSource.PORTABLE_STEPS_IMPORT) {
			return trip.toModel().copy(distanceM = 0f, sampleCount = 0, hasMeasuredDistance = false)
		}
		val location = try { readLocation(trip.id) }
		catch (cancelled: CancellationException) { throw cancelled }
		catch (_: Exception) { null }
		return trip.toModel().copy(distanceM = (location?.distanceMeters ?: 0.0).toFloat(),
			sampleCount = location?.pointCount?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0,
			hasMeasuredDistance = location?.distanceMeters != null,
			distanceIsPartial = location == null || location.partial)
	}

	override suspend fun deleteTrip(id: Long) {
		tripDao.deleteById(id)
	}

	private fun com.adsamcik.tracker.shared.model.Trip.toSummary(): TripSummary {
		return TripSummary(
			id = id,
			startTimeMs = EpochMs(startTimeMs),
			endTimeMs = EpochMs(endTimeMs),
			distance = DistanceM.coerced(distanceM),
			duration = DurationMs((endTimeMs - startTimeMs).coerceAtLeast(0L)),
			primaryMode = resolveTransportMode(),
			sampleCount = sampleCount,
			source = source,
			hasMeasuredDistance = hasMeasuredDistance,
			distanceIsPartial = distanceIsPartial,
		)
	}

	private fun com.adsamcik.tracker.shared.model.Trip.resolveTransportMode(): TransportMode =
		when (primaryActivity) {
			7 -> TransportMode.WALK
			8 -> TransportMode.RUN
			1 -> TransportMode.CYCLE
			0 -> TransportMode.DRIVE
			else -> when (source) {
				com.adsamcik.tracker.shared.model.SegmentSource.INFERRED_HIGH_CONFIDENCE,
				com.adsamcik.tracker.shared.model.SegmentSource.INFERRED_MEDIUM_CONFIDENCE ->
					TransportMode.TRANSIT
				else -> TransportMode.UNKNOWN
			}
		}
}

private class TripModelPagingSource(
	private val delegate: PagingSource<Int, com.adsamcik.tracker.shared.base.database.data.Trip>,
	private val measure: suspend (com.adsamcik.tracker.shared.base.database.data.Trip) ->
        com.adsamcik.tracker.shared.model.Trip,
    measurements: SavedTripMeasurementCache,
    registerChanges: ((Set<String>) -> Unit) -> (() -> Unit),
    io: kotlinx.coroutines.CoroutineDispatcher,
) : PagingSource<Int, com.adsamcik.tracker.shared.model.Trip>() {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val changes = Channel<Set<String>>(Channel.BUFFERED)
    private val stopObserving = registerChanges {
        if (changes.trySend(it).isFailure) {
            invalidate()
        }
    }
	init {
        scope.launch {
            for (tables in changes) {
                try {
                    if (measurements.changed(tables)) {
                        invalidate()
                    }
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { invalidate() }
            }
        }
		delegate.registerInvalidatedCallback { invalidate() }
		registerInvalidatedCallback { stopObserving(); changes.close(); scope.cancel() }
	}

	override fun getRefreshKey(state: PagingState<Int, com.adsamcik.tracker.shared.model.Trip>): Int? =
		state.anchorPosition

	override suspend fun load(params: LoadParams<Int>): LoadResult<Int, com.adsamcik.tracker.shared.model.Trip> =
		try { when (val result = delegate.load(params)) {
			is LoadResult.Error -> LoadResult.Error(result.throwable)
			is LoadResult.Invalid -> LoadResult.Invalid()
			is LoadResult.Page -> LoadResult.Page(
				data = result.data.map { measure(it) },
				prevKey = result.prevKey,
				nextKey = result.nextKey,
				itemsBefore = result.itemsBefore,
				itemsAfter = result.itemsAfter,
			)
		} } catch (cancelled: CancellationException) { throw cancelled }
		catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
			// Delegate loading and saved-distance conversion both need Paging's recoverable error.
			LoadResult.Error(failure)
		}
}
