package com.adsamcik.tracker.tracker.data

import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.shared.model.Trip
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Read-only feature port for the tracker dashboard's recent-session card.
 */
interface RecentTripsRepository {
	fun observeRecentTrips(limit: Int): Flow<List<Trip>>
}

@Singleton
internal class RoomRecentTripsRepository @Inject constructor(
	private val tripDao: TripDao,
	private val dispatchers: DispatchersProvider,
) : RecentTripsRepository {
	override fun observeRecentTrips(limit: Int): Flow<List<Trip>> =
		tripDao.getRecentTripsFlow(limit)
			.map { trips -> trips.map { it.toModel() } }
			.flowOn(dispatchers.io)
}
