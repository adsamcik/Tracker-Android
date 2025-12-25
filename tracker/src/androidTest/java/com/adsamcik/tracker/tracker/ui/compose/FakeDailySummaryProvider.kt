package com.adsamcik.tracker.tracker.ui.compose

import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.DailySummaryProvider

class FakeDailySummaryProvider : DailySummaryProvider {
    var summary: DailySummary? = null

    override suspend fun fetchTodaySummary(): DailySummary? {
        return summary
    }
}
