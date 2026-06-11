package com.adsamcik.tracker.stats.api.repository

import androidx.paging.PagingSource
import com.adsamcik.tracker.shared.model.Trip

/**
 * Android presentation contract for paged trip access and trip mutations.
 *
 * Keeps Paging 3 and shared trip projections behind a stats-layer boundary so
 * feature modules do not need direct DAO access.
 */
interface TripPresentationRepository {
	fun getPagedTrips(): PagingSource<Int, Trip>
	fun getPagedTripsOverlapping(fromMs: Long, toMs: Long): PagingSource<Int, Trip>
	suspend fun getTripsBetween(fromMs: Long, toMs: Long): List<Trip>
	suspend fun getTripProjection(id: Long): Trip?
	suspend fun deleteTrip(id: Long)
}
