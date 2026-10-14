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
import javax.inject.Inject

class DefaultTripRepository @Inject constructor(
	private val tripDao: TripDao,
	private val savedLocationMetrics: SavedLocationMetricsReader,
) : TripRepository, TripPresentationRepository {

	override fun observeTrips(): Flow<List<TripSummary>> =
		combine(tripDao.getRecentTripsFlow(100), savedLocationMetrics.observeChanges()) { trips, _ ->
			trips.map { measuredTrip(it).toSummary() }
		}

	override fun observeTripsBetween(from: EpochMs, to: EpochMs): Flow<List<TripSummary>> =
		savedLocationMetrics.observeChanges().map {
			tripDao.getBetween(from.raw, to.raw).map { trip -> measuredTrip(trip).toSummary() }
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
		TripModelPagingSource(tripDao.getAllPaged(), ::measuredTrip, savedLocationMetrics::onSavedDataChange)

	override fun getPagedTripsOverlapping(
		fromMs: Long,
		toMs: Long,
	): PagingSource<Int, com.adsamcik.tracker.shared.model.Trip> =
		TripModelPagingSource(tripDao.getPagedOverlapping(fromMs, toMs), ::measuredTrip, savedLocationMetrics::onSavedDataChange)

	override suspend fun getTripsBetween(fromMs: Long, toMs: Long) =
		tripDao.getBetween(fromMs, toMs).map { measuredTrip(it) }

	override suspend fun getTripProjection(id: Long) = tripDao.getById(id)?.let { measuredTrip(it) }

	private suspend fun measuredTrip(trip: com.adsamcik.tracker.shared.base.database.data.Trip):
		com.adsamcik.tracker.shared.model.Trip {
		if (trip.source == com.adsamcik.tracker.shared.model.SegmentSource.PORTABLE_STEPS_IMPORT) {
			return trip.toModel().copy(distanceM = 0f, sampleCount = 0, hasMeasuredDistance = false)
		}
		val location = try { savedLocationMetrics.readTrip(trip.id) }
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
	private val measure: suspend (com.adsamcik.tracker.shared.base.database.data.Trip) -> com.adsamcik.tracker.shared.model.Trip,
	registerChanges: (() -> Unit) -> (() -> Unit),
) : PagingSource<Int, com.adsamcik.tracker.shared.model.Trip>() {
	private val stopObserving = registerChanges { invalidate() }
	init {
		delegate.registerInvalidatedCallback { invalidate() }
		registerInvalidatedCallback { stopObserving() }
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
		catch (failure: Exception) { LoadResult.Error(failure) }
}
