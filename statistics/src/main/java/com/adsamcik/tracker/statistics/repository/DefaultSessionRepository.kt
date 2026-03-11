package com.adsamcik.tracker.statistics.repository

import android.content.Context
import androidx.paging.PagingSource
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.Trip
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
    
    override suspend fun getSummaryStats(): SessionStatsResult = withContext(dispatchers.io) {
        try {
            SessionStatsResult.Success(SessionStatsAdapter.buildSummary(context))
        } catch (e: Exception) {
            SessionStatsResult.Failure(
                message = e.message ?: "Failed to load summary statistics",
                cause = e,
            )
        }
    }
    
    override suspend fun getWeeklyStats(): SessionStatsResult = withContext(dispatchers.io) {
        try {
            SessionStatsResult.Success(SessionStatsAdapter.buildSevenDaySummary(context))
        } catch (e: Exception) {
            SessionStatsResult.Failure(
                message = e.message ?: "Failed to load weekly statistics",
                cause = e,
            )
        }
    }
}
