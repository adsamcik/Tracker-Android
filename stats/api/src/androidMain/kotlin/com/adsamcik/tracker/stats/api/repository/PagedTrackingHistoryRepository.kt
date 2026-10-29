package com.adsamcik.tracker.stats.api.repository

import androidx.paging.PagingSource

/**
 * Pages the shared saved-data history for a requested range using source-aware continuation keys.
 */
interface PagedTrackingHistoryRepository : TrackingHistoryRepository {
    /**
     * Creates a fresh page source for the saved-data range; continuation keys and invalidation
     * keep successive source pages consistent.
     */
    fun pagingSource(range: HistoryRange): PagingSource<HistoryContinuation, HistoryPagingItem>

    /** Qualified occupied dates for one visible month, in the requested display zone. */
    fun observeCalendarPresence(range: HistoryRange): kotlinx.coroutines.flow.Flow<CalendarHistoryPresenceRead> =
        kotlinx.coroutines.flow.flow {
            emit(CalendarHistoryPresenceRead.Unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE))
        }
}
