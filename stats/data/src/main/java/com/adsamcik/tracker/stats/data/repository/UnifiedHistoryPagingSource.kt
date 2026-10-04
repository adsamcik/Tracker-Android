package com.adsamcik.tracker.stats.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.stats.api.repository.HistoryContinuation
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRead
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import kotlinx.coroutines.CancellationException

/** Forward keyset browsing. Refresh restarts the chosen range; it never broadens its dates. */
internal class UnifiedHistoryPagingSource(
    private val database: AppDatabase,
    private val range: HistoryRange,
    private val read: suspend (HistoryRangeRequest) -> HistoryRangeRead,
) : PagingSource<HistoryContinuation, UnifiedHistoryRow>() {
    private val observer = object : InvalidationTracker.Observer(
        UnifiedHistoryRangeReader.invalidationTables.toTypedArray()) {
        override fun onInvalidated(tables: Set<String>) { invalidate() }
    }
    init {
        database.invalidationTracker.addWeakObserver(observer)
        registerInvalidatedCallback { database.invalidationTracker.removeObserver(observer) }
    }

    override suspend fun load(params: LoadParams<HistoryContinuation>): LoadResult<HistoryContinuation, UnifiedHistoryRow> {
        if (invalid) return LoadResult.Invalid()
        if (params is LoadParams.Prepend) return LoadResult.Page(emptyList(), null, null)
        return try {
            when (val result = read(HistoryRangeRequest(range, params.loadSize.coerceIn(1, 100), params.key))) {
                is HistoryRangeRead.Page -> if (invalid) LoadResult.Invalid() else LoadResult.Page(
                    result.rows, prevKey = null, nextKey = result.next)
                is HistoryRangeRead.Unavailable -> LoadResult.Error(
                    IllegalStateException("History is unavailable: ${result.reason.name}"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            LoadResult.Error(failure)
        }
    }

    override fun getRefreshKey(state: PagingState<HistoryContinuation, UnifiedHistoryRow>): HistoryContinuation? = null
}
