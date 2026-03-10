package com.adsamcik.tracker.statistics.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.Trip
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class DefaultStatsRepository(
    private val tripDao: TripDao,
    private val ioDispatcher: CoroutineDispatcher
) : StatsRepository {
    companion object {
        private const val PAGE_SIZE = 20
        private const val PREFETCH_DISTANCE = 5
        private const val INITIAL_LOAD_SIZE = 40
    }

    override fun getSessionsPaged(): Flow<PagingData<Trip>> {
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                prefetchDistance = PREFETCH_DISTANCE,
                enablePlaceholders = false,
                initialLoadSize = INITIAL_LOAD_SIZE
            ),
            pagingSourceFactory = { tripDao.getAllPaged() }
        ).flow
    }

    override suspend fun getSessionCount(): Long {
        return withContext(ioDispatcher) { tripDao.countAllTrips() }
    }

    override suspend fun getSessionById(sessionId: Long): Trip? {
        return withContext(ioDispatcher) { tripDao.getById(sessionId) }
    }
}