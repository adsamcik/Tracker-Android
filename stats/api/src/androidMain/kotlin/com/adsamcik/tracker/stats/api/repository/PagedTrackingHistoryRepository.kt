package com.adsamcik.tracker.stats.api.repository

import androidx.paging.PagingSource

interface PagedTrackingHistoryRepository : TrackingHistoryRepository {
    fun pagingSource(range: HistoryRange): PagingSource<HistoryContinuation, HistoryPagingItem>
}
