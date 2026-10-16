package com.adsamcik.tracker.stats.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import java.lang.ref.WeakReference
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
    init {
        registerPagingInvalidation(database.invalidationTracker, this,
            UnifiedHistoryRangeReader.invalidationTables.toTypedArray())
    }

    override suspend fun load(params: LoadParams<HistoryContinuation>): LoadResult<HistoryContinuation,
        HistoryPagingItem> {
        if (invalid) {
            return LoadResult.Invalid()
        }
        if (params is LoadParams.Prepend) {
            return LoadResult.Page(emptyList(), null, null)
        }
        return try {
            when (val result = read(HistoryRangeRequest(range, params.loadSize.coerceIn(1, 100), params.key))) {
                is HistoryRangeRead.Page -> acceptPage(result, params.key)
                is HistoryRangeRead.Unavailable -> LoadResult.Error(
                    IllegalStateException("History is unavailable: ${result.reason.name}"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
            // Room, row construction and snapshot failures all use Paging's existing Retry result.
            LoadResult.Error(failure)
        }
    }

    private fun acceptPage(result: HistoryRangeRead.Page, after: HistoryContinuation?):
        LoadResult<HistoryContinuation, HistoryPagingItem> = synchronized(snapshotLock) {
        if (invalid || snapshot?.let { it != result.snapshot } == true) {
            invalidate()
            LoadResult.Invalid()
        } else {
            snapshot = result.snapshot
            val items = result.rows.map { HistoryPagingItem.Row(it,
                result.snapshot) }.toMutableList<HistoryPagingItem>()
            val problems = result.problems + result.rows.flatMap { it.problems }
            if (problems.isNotEmpty()) {
                items += HistoryPagingItem.PageProblems(problems, result.snapshot, after)
            }
            LoadResult.Page(items, prevKey = null, nextKey = result.next)
        }
    }

    override fun getRefreshKey(state: PagingState<HistoryContinuation, HistoryPagingItem>): HistoryContinuation? = null
}

/** Public Room observation with the same weak paging-source lifetime and explicit invalidation cleanup. */
internal fun registerPagingInvalidation(tracker: InvalidationTracker, source: PagingSource<*, *>,
    tables: Array<String>) {
    val owner = WeakReference(source)
    val observer = object : InvalidationTracker.Observer(tables) {
        override fun onInvalidated(tables: Set<String>) {
            owner.get()?.invalidate() ?: tracker.removeObserver(this)
        }
    }
    source.registerInvalidatedCallback { tracker.removeObserver(observer) }
    tracker.addObserver(observer)
    if (source.invalid) {
        tracker.removeObserver(observer)
    }
}
