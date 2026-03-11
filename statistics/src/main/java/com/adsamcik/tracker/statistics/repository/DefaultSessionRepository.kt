package com.adsamcik.tracker.statistics.repository

import android.content.Context
import androidx.paging.PagingSource
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.statistics.data.Stat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default implementation of SessionRepository using Room DAO.
 * Provides session data from the local database.
 * 
 * @param context Android context for database access
 * @param dispatchers Coroutine dispatchers for background operations
 */
@Singleton
class DefaultSessionRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val dispatchers: DispatchersProvider
) : SessionRepository {
    private val tripDao = AppDatabase.database(context).tripDao()
    private val context = context
    
    override fun getAllSessionsPaged(): PagingSource<Int, Trip> {
        return tripDao.getAllPaged()
    }
    
    override suspend fun getSummaryStats(): List<Stat> = withContext(dispatchers.io) {
        SessionStatsAdapter.buildSummary(context)
    }
    
    override suspend fun getWeeklyStats(): List<Stat> = withContext(dispatchers.io) {
        SessionStatsAdapter.buildSevenDaySummary(context)
    }
}
