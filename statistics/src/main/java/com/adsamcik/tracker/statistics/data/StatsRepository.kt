package com.adsamcik.tracker.statistics.data

import androidx.paging.PagingData
import com.adsamcik.tracker.shared.base.database.data.Trip
import kotlinx.coroutines.flow.Flow

interface StatsRepository {
    fun getSessionsPaged(): Flow<PagingData<Trip>>
    suspend fun getSessionCount(): Long
    suspend fun getSessionById(sessionId: Long): Trip?
}