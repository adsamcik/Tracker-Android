package com.adsamcik.tracker.statistics.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.dao.SessionDataDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class DefaultStatsRepository(
    private val sessionDao: SessionDataDao,
    private val ioDispatcher: CoroutineDispatcher
) : StatsRepository {
    companion object {
        private const val PAGE_SIZE = 20
        private const val PREFETCH_DISTANCE = 5
        private const val INITIAL_LOAD_SIZE = 40
    }

    override fun getSessionsPaged(): Flow<PagingData<TrackerSession>> {
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                prefetchDistance = PREFETCH_DISTANCE,
                enablePlaceholders = false,
                initialLoadSize = INITIAL_LOAD_SIZE
            ),
            pagingSourceFactory = { sessionDao.getAllPaged() }
        ).flow
    }

    override suspend fun getSessionCount(): Long {
        return withContext(ioDispatcher) { sessionDao.count() }
    }

    override suspend fun getSessionById(sessionId: Long): TrackerSession? {
        return withContext(ioDispatcher) { sessionDao.get(sessionId) }
    }
}