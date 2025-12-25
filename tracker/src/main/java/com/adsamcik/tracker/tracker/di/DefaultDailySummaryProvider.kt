package com.adsamcik.tracker.tracker.di

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.dao.SessionDataDao
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.DailySummaryProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Default implementation of DailySummaryProvider.
 * 
 * Fetches today's aggregated session summary from Room DAO on IO dispatcher.
 * Called on-demand (not reactive) to stay lightweight per design decision.
 * 
 * Lifecycle: Application-scoped singleton (wired in AppGraph)
 */
class DefaultDailySummaryProvider(
    private val sessionDao: SessionDataDao,
    private val ioDispatcher: CoroutineDispatcher
) : DailySummaryProvider {
    
    override suspend fun fetchTodaySummary(): DailySummary? = withContext(ioDispatcher) {
        val now = Time.nowMillis
        val startOfDay = (now / Time.DAY_IN_MILLISECONDS) * Time.DAY_IN_MILLISECONDS
        
        val summary = sessionDao.getTodaySummary(startOfDay, now)
        
        // Return null if no sessions (sessionCount == 0 means no data)
        if (summary == null || summary.sessionCount == 0) {
            null
        } else {
            DailySummary(
                totalDistanceM = summary.distanceInM,
                totalSteps = summary.steps,
                totalDurationMs = summary.duration,
                sessionCount = summary.sessionCount
            )
        }
    }
}
