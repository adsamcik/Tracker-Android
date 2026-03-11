package com.adsamcik.tracker.statistics.repository

import androidx.paging.PagingSource
import com.adsamcik.tracker.shared.base.database.data.Trip
/**
 * Repository interface for accessing tracker session data.
 * Provides abstraction over direct DAO access for testing and modularity.
 */
interface SessionRepository {
    /**
     * Get all trips as a paging source, ordered by start time descending.
     * @return PagingSource for lazy loading of trips
     */
    fun getAllSessionsPaged(): PagingSource<Int, Trip>
    
    /**
     * Get overall summary statistics for all sessions.
     * @return Sealed result containing formatted summary statistics or a failure
     */
    suspend fun getSummaryStats(): SessionStatsResult
    
    /**
     * Get summary statistics for the last 7 days.
     * @return Sealed result containing formatted weekly statistics or a failure
     */
    suspend fun getWeeklyStats(): SessionStatsResult
}
