package com.adsamcik.tracker.stats.data.repository

import androidx.paging.PagingSource
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.stats.api.repository.HistoryBrowseMode
import com.adsamcik.tracker.stats.api.repository.HistoryContinuation
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryPagingItem
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRead
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.HistorySource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** Actual RangeReader -> raw Room keys -> exact source readers -> paging. No sensor is activated. NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnifiedMeasuredHistoryAssemblyRoomTest {
    private lateinit var database: AppDatabase
    private lateinit var reader: UnifiedHistoryRangeReader

    @Before fun setup() = runTest {
        database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext())
        database.sourceEvidenceStateDao().ensure()
        reader = rangeReaderForRoomTest(database, ApplicationProvider.getApplicationContext())
    }
    @After fun close() = database.close()

    @Test fun `all source choices show original saved readings even with no sessions`() = runTest {
        point(1_000L)
        activity(1_002L)
        pressure(1_001L)
        val page = read(range())
        page.problems shouldBe emptySet()
        page.next shouldBe null
        page.rows.map { it.position.startTimeMs } shouldBe listOf(1_002L, 1_001L, 1_000L)
        page.rows.map { requireNotNull(it.measurement).source } shouldBe
            listOf(HistorySource.ACTIVITY, HistorySource.PRESSURE, HistorySource.LOCATION)
        page.rows.all { it.name == null && it.sections.isEmpty() && it.toExclusiveMs == null } shouldBe true
        page.rows.all { it.purpose == HistoryPurpose.UNKNOWN && it.origins == setOf(HistoryOrigin.LEGACY) } shouldBe true
    }

    @Test fun `global source merge reaches older readings beyond 4096 with equal timestamp keys`() = runTest {
        database.withTransaction {
            repeat(4_201) { index ->
                val time = 1_000L + index / 7
                when (index % 3) { 0 -> point(time); 1 -> activity(time); else -> pressure(time) }
            }
        }
        val range = range()
        val found = mutableListOf<com.adsamcik.tracker.stats.api.repository.HistoryPosition>()
        var after: HistoryContinuation? = null
        var pages = 0
        do {
            val page = reader.read(HistoryRangeRequest(range, limit = 73, after = after))
                .shouldBeInstanceOf<HistoryRangeRead.Page>()
            page.problems shouldBe emptySet()
            (page.rows.size <= 73) shouldBe true
            found += page.rows.map { it.position }
            after = page.next
            pages++
            (pages <= 60) shouldBe true
        } while (after != null)
        found.size shouldBe 4_201
        found.distinct().size shouldBe found.size
        found.zipWithNext().all { (a, b) -> a < b } shouldBe true
    }

    @Test fun `a page of rejected raw keys advances without creating timed error readings`() = runTest {
        repeat(101) { pressure(10_000L + it, mean = -1f) }
        point(1_000L)
        activity(1_001L)
        val range = range(0L, 20_000L)
        val first = read(range, limit = 100)
        first.rows shouldBe emptyList()
        first.problems shouldBe setOf(HistoryProblem(HistorySource.PRESSURE, HistoryProblemReason.INTEGRITY_UNAVAILABLE))
        val continuation = requireNotNull(first.next)
        val second = reader.read(HistoryRangeRequest(range, 100, continuation)).shouldBeInstanceOf<HistoryRangeRead.Page>()
        second.next shouldBe null
        second.rows.map { requireNotNull(it.measurement).source } shouldBe listOf(HistorySource.ACTIVITY, HistorySource.LOCATION)
        second.rows.all { it.position > continuation.consumed } shouldBe true
        second.problems shouldBe first.problems
    }

    @Test fun `one selected source never borrows a coincident source or a recording purpose`() = runTest {
        point(1_000L)
        activity(1_000L)
        pressure(1_000L)
        val chosen = range().copy(sources = setOf(HistorySource.PRESSURE))
        read(chosen).rows.single().measurement.shouldBeInstanceOf<HistoryMeasurement.LegacyPressure>()
        read(chosen.copy(purposes = setOf(HistoryPurpose.CAPTURE))).rows shouldBe emptyList()
        read(chosen.copy(origins = setOf(HistoryOrigin.IMPORTED))).rows shouldBe emptyList()
    }

    @Test fun `Calendar day boundaries follow original measured time through a 23 hour day`() = runTest {
        val zone = ZoneId.of("Europe/Prague")
        val day = LocalDate.of(2026, 3, 29)
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        activity(from - 1L, createdAt = to + 100_000L)
        activity(from, createdAt = to + 100_000L)
        point(to - 1L, createdAt = to + 100_000L)
        pressure(to)
        val page = read(range(from, to).copy(viewZoneId = zone.id))
        page.rows.map { it.position.startTimeMs } shouldBe listOf(to - 1L, from)
        page.problems shouldBe emptySet()
        to - from shouldBe 23L * 60L * 60L * 1_000L
    }

    @Test fun `default recording mode stays separate from measured sessionless leaves`() = runTest {
        activity(1_000L)
        pressure(1_001L)
        val range = HistoryRange(0L, 2_000L, "UTC", sources = setOf(HistorySource.ACTIVITY, HistorySource.PRESSURE))
        range.mode shouldBe HistoryBrowseMode.RECORDINGS
        read(range).rows shouldBe emptyList()
        read(range.copy(mode = HistoryBrowseMode.OBSERVATIONS)).rows.size shouldBe 2
    }

    @Test fun `qualification failure keeps sibling data and reaches untimed paging metadata`() = runTest {
        point(1_000L)
        activity(1_001L)
        val proof = mockk<RetainedLocationContextReader>()
        coEvery { proof.qualifySavedInTransaction(any()) } throws android.database.sqlite.SQLiteException("Source failure fixture")
        reader = rangeReaderForRoomTest(database, ApplicationProvider.getApplicationContext(), qualifiedLocation = proof)
        val source = UnifiedHistoryPagingSource(database, range(), reader::read)
        val loaded = source.load(PagingSource.LoadParams.Refresh(null, 100, false))
            .shouldBeInstanceOf<PagingSource.LoadResult.Page<HistoryContinuation, HistoryPagingItem>>()
        val rows = loaded.data.filterIsInstance<HistoryPagingItem.Row>()
        rows.map { requireNotNull(it.row.measurement).source } shouldBe listOf(HistorySource.ACTIVITY, HistorySource.LOCATION)
        rows.all { it.row.problems.isEmpty() } shouldBe true
        val notice = loaded.data.filterIsInstance<HistoryPagingItem.PageProblems>().single()
        notice.problems shouldBe setOf(HistoryProblem(HistorySource.LOCATION, HistoryProblemReason.STORAGE_UNAVAILABLE))
        notice.snapshot shouldBe rows.first().snapshot
    }

    @Test fun `snapshot change cannot append old and new data epochs together`() = runTest {
        activity(1_000L)
        activity(1_001L)
        val source = UnifiedHistoryPagingSource(database, range(), reader::read)
        val first = source.load(PagingSource.LoadParams.Refresh(null, 1, false))
            .shouldBeInstanceOf<PagingSource.LoadResult.Page<HistoryContinuation, HistoryPagingItem>>()
        database.sourceEvidenceStateDao().updateLifecycle(1L, null, 2_000L)
        source.load(PagingSource.LoadParams.Append(requireNotNull(first.nextKey), 1, false))
            .shouldBeInstanceOf<PagingSource.LoadResult.Invalid<HistoryContinuation, HistoryPagingItem>>()
    }

    @Test fun `cancelling a real source qualifier cancels the containing history request`() = runTest {
        point(1_000L)
        activity(1_001L)
        val cancelled = CancellationException("Source cancellation fixture")
        val proof = mockk<RetainedLocationContextReader>()
        coEvery { proof.qualifySavedInTransaction(any()) } throws cancelled
        reader = rangeReaderForRoomTest(database, ApplicationProvider.getApplicationContext(), qualifiedLocation = proof)
        var observed: CancellationException? = null
        try { reader.read(HistoryRangeRequest(range())) } catch (failure: CancellationException) { observed = failure }
        (observed === cancelled) shouldBe true
    }

    private suspend fun read(range: HistoryRange, limit: Int = 100) =
        reader.read(HistoryRangeRequest(range, limit)).shouldBeInstanceOf<HistoryRangeRead.Page>()
    private fun range(from: Long = 0L, to: Long = 2_000L) =
        HistoryRange(from, to, "UTC", mode = HistoryBrowseMode.OBSERVATIONS)
    private suspend fun point(time: Long, createdAt: Long = time) = database.locationSampleDao().insert(
        LocationSample(timeMs = time, elapsedRealtimeNanos = 0L, latE7 = 500_000_000, lonE7 = 140_000_000,
            altitudeM = null, rawGpsAltitudeM = null, hAccM = null, vAccM = null, speedMps = null,
            speedAccuracyMps = null, provider = "legacy-fixture", quality = SampleQuality.HIGH, motionState = null,
            policy = null, bucketId = null, createdAt = createdAt))
    private suspend fun activity(time: Long, createdAt: Long = time) = database.activitySnapshotDao().insert(
        ActivitySnapshot(timeMs = time, activityType = 7, confidence = 65, isTransition = true, createdAt = createdAt))
    private suspend fun pressure(time: Long, mean: Float = 1_010f) = database.pressureSampleDao().insert(
        PressureSample(timeMs = time, elapsedRealtimeNanos = 20L, pressureHpa = mean, altitudeM = 12f,
            bucketId = null, createdAt = time, sampleCount = 4, minPressureHpa = 1_000f, maxPressureHpa = 1_020f,
            standardDeviationHpa = 2f, windowStartElapsedRealtimeNanos = 10L, windowEndElapsedRealtimeNanos = 20L))
}
