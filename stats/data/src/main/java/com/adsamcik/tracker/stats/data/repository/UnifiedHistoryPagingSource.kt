package com.adsamcik.tracker.stats.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.stats.api.repository.HistoryContinuation
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRead
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.HistoryPagingItem
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import kotlinx.coroutines.CancellationException

/** Forward keyset browsing. Refresh restarts the chosen range; it never broadens its dates. */
internal class UnifiedHistoryPagingSource(
    private val database: AppDatabase,
    private val range: HistoryRange,
    private val read: suspend (HistoryRangeRequest) -> HistoryRangeRead,
) : PagingSource<HistoryContinuation, HistoryPagingItem>() {
    private val snapshotLock = Any()
    private var snapshot: TrackingHistoryReadSnapshot? = null
    private val observer = object : InvalidationTracker.Observer(
        UnifiedHistoryRangeReader.invalidationTables.toTypedArray()) {
        override fun onInvalidated(tables: Set<String>) { invalidate() }
    }
    init {
        database.invalidationTracker.addWeakObserver(observer)
        registerInvalidatedCallback { database.invalidationTracker.removeObserver(observer) }
    }

    override suspend fun load(params: LoadParams<HistoryContinuation>): LoadResult<HistoryContinuation, HistoryPagingItem> {
        if (invalid) return LoadResult.Invalid()
        if (params is LoadParams.Prepend) return LoadResult.Page(emptyList(), null, null)
        return try {
            when (val result = read(HistoryRangeRequest(range, params.loadSize.coerceIn(1, 100), params.key))) {
                is HistoryRangeRead.Page -> synchronized(snapshotLock) {
                    if (invalid || snapshot?.let { it != result.snapshot } == true) {
                        invalidate()
                        LoadResult.Invalid()
                    } else {
                        snapshot = result.snapshot
                        val items = result.rows.map { HistoryPagingItem.Row(it, result.snapshot) }.toMutableList<HistoryPagingItem>()
                        val problems = result.problems + result.rows.flatMap { it.problems }
                        if (problems.isNotEmpty()) items += HistoryPagingItem.PageProblems(problems, result.snapshot, params.key)
                        LoadResult.Page(items, prevKey = null, nextKey = result.next)
                    }
                }
                is HistoryRangeRead.Unavailable -> LoadResult.Error(
                    IllegalStateException("History is unavailable: ${result.reason.name}"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            LoadResult.Error(failure)
        }
    }

    override fun getRefreshKey(state: PagingState<HistoryContinuation, HistoryPagingItem>): HistoryContinuation? = null
}
