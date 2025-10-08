package com.adsamcik.tracker.statistics.data

import androidx.paging.PagingData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import kotlinx.coroutines.flow.Flow

interface StatsRepository {
    fun getSessionsPaged(): Flow<PagingData<TrackerSession>>
    suspend fun getSessionCount(): Long
    suspend fun getSessionById(sessionId: Long): TrackerSession?
}