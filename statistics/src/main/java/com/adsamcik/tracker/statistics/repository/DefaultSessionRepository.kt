package com.adsamcik.tracker.statistics.repository

import android.content.Context
import androidx.paging.PagingSource
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.summary.SummaryGenerator

/**
 * Default implementation of SessionRepository using Room DAO.
 * Provides session data from the local database.
 */
class DefaultSessionRepository(context: Context) : SessionRepository {
    private val sessionDao = AppDatabase.database(context).sessionDao()
    private val context = context
    
    override fun getAllSessionsPaged(): PagingSource<Int, TrackerSession> {
        return sessionDao.getAllPaged()
    }
    
    override suspend fun getSummaryStats(): List<Stat> {
        return SummaryGenerator.buildSummary(context)
    }
    
    override suspend fun getWeeklyStats(): List<Stat> {
        return SummaryGenerator.buildSevenDaySummary(context)
    }
}