package com.adsamcik.tracker.tracker.ui.compose

import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.DailySummaryProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeDailySummaryProvider : DailySummaryProvider {
    var summary: DailySummary? = null

    override suspend fun fetchTodaySummary(): DailySummary? {
        return summary
    }

    override fun observeTodayLive(): Flow<DailySummary?> {
        return flowOf(summary)
    }
}
