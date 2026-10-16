package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.paging.PagingSource
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.stats.api.repository.HistoryBrowseMode
import com.adsamcik.tracker.stats.api.repository.HistoryContinuation
import com.adsamcik.tracker.stats.api.repository.HistoryLocationCoverage
import com.adsamcik.tracker.stats.api.repository.HistoryLocationPoint
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryPagingItem
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRead
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual PagingSource and Room invalidation registration; selected page responses are fixtures. NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnifiedHistoryPagingMetadataRoomTest {
    private lateinit var database: AppDatabase
    private val range = HistoryRange(0, 10_000, "UTC", mode = HistoryBrowseMode.OBSERVATIONS)
    private val snapshot = TrackingHistoryReadSnapshot(0, 1)
    private val position = HistoryPosition(100, "legacy:location", "point:1")
    private fun row() = UnifiedHistoryRow(position, null, setOf(HistoryOrigin.LEGACY), HistoryPurpose.UNKNOWN,
        sections = emptyList(), measurement = HistoryMeasurement.Location(
            HistoryLocationPoint(100, 1, 2, null, null, 1), HistoryLocationCoverage.LEGACY_UNVERIFIABLE))
    @Before fun setup() { database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>()) }
    @After fun close() { database.close() }

    @Test fun `source failure metadata stays untimed alongside the valid saved sibling`() = runTest {
        val problem = HistoryProblem(HistorySource.WIFI, HistoryProblemReason.STORAGE_UNAVAILABLE)
        val source = UnifiedHistoryPagingSource(database, range) {
            it.range shouldBe range
            HistoryRangeRead.Page(listOf(row()), snapshot, null, setOf(problem))
        }
        val page = source.load(PagingSource.LoadParams.Refresh<HistoryContinuation>(null, 50, false))
            .shouldBeInstanceOf<PagingSource.LoadResult.Page<HistoryContinuation, HistoryPagingItem>>()
        page.data.filterIsInstance<HistoryPagingItem.Row>().single().row shouldBe row()
        page.data.filterIsInstance<HistoryPagingItem.PageProblems>().single().let {
            it.problems shouldBe setOf(problem)
            it.snapshot shouldBe snapshot
            it.after shouldBe null
        }
    }

    @Test fun `a failed source without any timed candidate still reaches the caller`() = runTest {
        val problem = HistoryProblem(HistorySource.CELL, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        val source = UnifiedHistoryPagingSource(database, range) {
            HistoryRangeRead.Page(emptyList(), snapshot, null, setOf(problem))
        }
        val page = source.load(PagingSource.LoadParams.Refresh<HistoryContinuation>(null, 50, false))
            .shouldBeInstanceOf<PagingSource.LoadResult.Page<HistoryContinuation, HistoryPagingItem>>()
        page.data.single().shouldBeInstanceOf<HistoryPagingItem.PageProblems>().problems shouldBe setOf(problem)
        page.nextKey shouldBe null
    }

    @Test fun `append with changed saved data snapshot invalidates rather than combining epochs`() = runTest {
        val next = HistoryContinuation(range, position)
        val source = UnifiedHistoryPagingSource(database, range) { request ->
            HistoryRangeRead.Page(listOf(row()), if (request.after == null) {
                snapshot
            } else {
                snapshot.copy(collectedDataEpoch = 1)
            },
                if (request.after == null) {
                    next
                } else {
                    null
                })
        }
        source.load(PagingSource.LoadParams.Refresh<HistoryContinuation>(null, 50, false))
            .shouldBeInstanceOf<PagingSource.LoadResult.Page<HistoryContinuation, HistoryPagingItem>>()
        source.load(PagingSource.LoadParams.Append(next, 50, false))
            .shouldBeInstanceOf<PagingSource.LoadResult.Invalid<HistoryContinuation, HistoryPagingItem>>()
        source.invalid shouldBe true
    }

    @Test fun `cancelled page read remains cancellation instead of a displayed storage error`() = runTest {
        val cancellation = CancellationException("cancelled fixture")
        val source = UnifiedHistoryPagingSource(database, range) { throw cancellation }
        var observed: Throwable? = null
        try { source.load(PagingSource.LoadParams.Refresh<HistoryContinuation>(null, 50, false)) }
        catch (failure: CancellationException) { observed = failure }
        observed shouldBe cancellation
    }
}
