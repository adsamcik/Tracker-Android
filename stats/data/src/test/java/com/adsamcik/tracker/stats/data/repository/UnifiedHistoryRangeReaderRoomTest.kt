package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.paging.PagingSource
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileArchivePointEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.OrganizationAnnotationEntity
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.HistoryContinuation
import com.adsamcik.tracker.stats.api.repository.HistoryLocationCoverage
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRead
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.HistorySection
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelectionCodec
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelector
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Source-authored only: real Room key selection and saved Location/annotation readers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnifiedHistoryRangeReaderRoomTest {
    private lateinit var database: AppDatabase
    private lateinit var reader: UnifiedHistoryRangeReader
    private lateinit var wifi: DefaultWifiHistoryRepository
    private lateinit var context: Application

    @Before fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.testDatabase(context)
        database.sourceEvidenceStateDao().ensure()
        wifi = mockk()
        reader = rangeReaderForRoomTest(database, context, wifi)
    }
    @After fun close() = database.close()

    @Test fun `old Location history pages beyond one hundred rows without changing dates`() = runTest {
        for (id in 1L..251L) legacyLocation(id, id * 10, id * 10 + 5)
        val range = range(0, 3_000)
        var continuation: HistoryContinuation? = null
        val identities = mutableListOf<String>()
        do {
            val page = reader.read(HistoryRangeRequest(range, limit = 73, after = continuation))
                .shouldBeInstanceOf<HistoryRangeRead.Page>()
            page.rows.forEach { row ->
                row.sources shouldBe setOf(HistorySource.LOCATION)
                row.sections.single().shouldBeInstanceOf<HistorySection.Location>().coverage shouldBe
                    HistoryLocationCoverage.LEGACY_UNVERIFIABLE
            }
            identities += page.rows.map { it.position.stableId }
            continuation = page.next
            continuation?.range?.shouldBe(range)
        } while (continuation != null)
        identities.size shouldBe 251
        identities.distinct().size shouldBe 251
        identities.first() shouldBe "segment:251"
        identities.last() shouldBe "segment:1"
        reader.read(HistoryRangeRequest(range(4_000, 5_000))).shouldBeInstanceOf<HistoryRangeRead.Page>()
            .rows shouldBe emptyList()
    }

    @Test fun `unavailable Wi-Fi sibling keeps valid Location and an explicit source problem`() = runTest {
        legacyLocation(1, 100, 200)
        val id = "a".repeat(64)
        database.importedWifiDao().insertEntryRevision(ImportedWifiEntryRevisionEntity(id, 1, null,
            "b".repeat(64), ImportedWifiEntryRevisionEntity.SOURCE_FORMAT,
            ImportedWifiEntryRevisionEntity.SOURCE_SCHEMA_VERSION, "MANUAL", 150, 180,
            0, "job", "entry", "wifi.trackerwifi", 200))
        coEvery { wifi.selectedImportedInTransaction(listOf(id), any()) } throws IllegalStateException("Unavailable source fixture")
        val page = reader.read(HistoryRangeRequest(range(0, 300).copy(sources = setOf(HistorySource.LOCATION, HistorySource.WIFI),
            origins = setOf(HistoryOrigin.LEGACY, HistoryOrigin.IMPORTED)))).shouldBeInstanceOf<HistoryRangeRead.Page>()
        page.rows.size shouldBe 2
        page.rows.first().problems shouldBe setOf(HistoryProblem(HistorySource.WIFI, HistoryProblemReason.INTEGRITY_UNAVAILABLE))
        page.rows.last().sections.single().shouldBeInstanceOf<HistorySection.Location>().points.single().timeMs shouldBe 101L
    }

    @Test fun `missing optional Location qualification never hides ordinary saved points`() = runTest {
        legacyLocation(1, 100, 200)
        val proof = mockk<RetainedLocationContextReader>()
        coEvery { proof.qualifyInTransaction(any()) } throws android.database.sqlite.SQLiteException("Unavailable proof fixture")
        reader = rangeReaderForRoomTest(database, context, wifi, proof)
        val row = reader.read(HistoryRangeRequest(range(0, 300))).shouldBeInstanceOf<HistoryRangeRead.Page>().rows.single()
        row.sections.single().shouldBeInstanceOf<HistorySection.Location>().points.single().timeMs shouldBe 101L
        row.sections.single().shouldBeInstanceOf<HistorySection.Location>().coverage shouldBe HistoryLocationCoverage.LEGACY_UNVERIFIABLE
        row.problems shouldBe setOf(HistoryProblem(HistorySource.LOCATION, HistoryProblemReason.STORAGE_UNAVAILABLE))
    }

    @Test fun `native compatibility keeps saved Location and propagates caught proof read failures`() = runTest {
        legacyLocation(1, 100, 200)
        val proof = mockk<RetainedLocationContextReader>()
        reader = rangeReaderForRoomTest(database, context, wifi, proof)
        for (reason in listOf(HistoryProblemReason.STORAGE_UNAVAILABLE, HistoryProblemReason.INTEGRITY_UNAVAILABLE)) {
            coEvery { proof.qualifyInTransaction(any()) } returns RetainedLocationContextSnapshot(
                emptyList(), setOf(com.adsamcik.tracker.stats.api.repository.RadioLocationContextGap.LOCATION_AUTHORITY_UNAVAILABLE),
                historyProblem = reason)
            val row = reader.read(HistoryRangeRequest(range(0, 300))).shouldBeInstanceOf<HistoryRangeRead.Page>().rows.single()
            row.sections.single().shouldBeInstanceOf<HistorySection.Location>().points.single().timeMs shouldBe 101L
            row.sections.single().shouldBeInstanceOf<HistorySection.Location>().coverage shouldBe HistoryLocationCoverage.LEGACY_UNVERIFIABLE
            row.problems shouldBe setOf(HistoryProblem(HistorySource.LOCATION, reason))
        }
    }

    @Test fun `imported annotation uses exact Location IDs and never coincident-time substitutions`() = runTest {
        val first = point("first", 1_000)
        val second = point("second", 1_000)
        point("unrelated", 1_000)
        val archive = "c".repeat(64)
        archive(archive, listOf(first, second))
        annotation("group", 500, 1_500, listOf(OrganizationSourceSelector.LocationFile(archive)))
        val page = reader.read(HistoryRangeRequest(organizationRange())).shouldBeInstanceOf<HistoryRangeRead.Page>()
        val row = page.rows.single()
        row.sections.filterIsInstance<HistorySection.Location>().single().points.size shouldBe 2
        row.problems shouldBe emptySet()
        row.sections.filterIsInstance<HistorySection.Organization>().single().session.name shouldBe "group"
        database.locationSampleDao().deleteExactIds(listOf(first.second))
        point("replacement", 1_000)
        val after = reader.read(HistoryRangeRequest(organizationRange())).shouldBeInstanceOf<HistoryRangeRead.Page>().rows.single()
        after.sections.filterIsInstance<HistorySection.Location>().single().points.size shouldBe 1
        after.problems shouldBe setOf(HistoryProblem(HistorySource.LOCATION, HistoryProblemReason.INTEGRITY_UNAVAILABLE))
    }

    @Test fun `retention and original archive epoch stay source-owned while metadata survives`() = runTest {
        val archive = "d".repeat(64)
        archive(archive, listOf(point("old", 1_000), point("kept", 2_000)))
        annotation("group", 500, 2_500, listOf(OrganizationSourceSelector.LocationFile(archive)))
        database.sourceEvidenceStateDao().updateLifecycle(0, 1_500, 3_000)
        val retained = reader.read(HistoryRangeRequest(organizationRange())).shouldBeInstanceOf<HistoryRangeRead.Page>().rows.single()
        retained.sections.filterIsInstance<HistorySection.Location>().single().points.map { it.timeMs } shouldBe listOf(2_000L)
        retained.problems shouldBe setOf(HistoryProblem(HistorySource.LOCATION, HistoryProblemReason.INTEGRITY_UNAVAILABLE))
        database.sourceEvidenceStateDao().updateLifecycle(1, 1_500, 4_000)
        val changed = reader.read(HistoryRangeRequest(organizationRange())).shouldBeInstanceOf<HistoryRangeRead.Page>().rows.single()
        changed.sections.filterIsInstance<HistorySection.Location>() shouldBe emptyList()
        changed.sections.filterIsInstance<HistorySection.Organization>().size shouldBe 1
        changed.problems shouldBe setOf(HistoryProblem(HistorySource.LOCATION, HistoryProblemReason.INTEGRITY_UNAVAILABLE))
    }

    @Test fun `same Location file preserves all data and filters only known saved purpose labels`() = runTest {
        val archive = "e".repeat(64)
        val points = listOf(point("session", 1_001, "session"), point("background", 1_002, "background"),
            point("both", 1_003, "both"), point("unknown", 1_004, "unknown"))
        point("unrelated", 1_003, "both")
        archive(archive, points)
        annotation("mixed", 500, 1_500, listOf(OrganizationSourceSelector.LocationFile(archive)))
        suspend fun read(purposes: Set<HistoryPurpose>) = reader.read(HistoryRangeRequest(
            organizationRange().copy(purposes = purposes + HistoryPurpose.ORGANIZATION)))
            .shouldBeInstanceOf<HistoryRangeRead.Page>().rows.single()
        val all = read(setOf(HistoryPurpose.CAPTURE, HistoryPurpose.BACKGROUND))
        all.sections.filterIsInstance<HistorySection.Location>().single().points.map { it.timeMs } shouldBe
            listOf(1_001L, 1_002L, 1_003L, 1_004L)
        all.problems shouldBe emptySet()
        val capture = read(setOf(HistoryPurpose.CAPTURE))
        capture.sections.filterIsInstance<HistorySection.Location>().single().points.map { it.timeMs } shouldBe
            listOf(1_001L, 1_003L)
        capture.problems shouldBe setOf(HistoryProblem(HistorySource.LOCATION, HistoryProblemReason.TIME_UNCERTAIN))
        val background = read(setOf(HistoryPurpose.BACKGROUND))
        background.sections.filterIsInstance<HistorySection.Location>().single().points.map { it.timeMs } shouldBe
            listOf(1_002L, 1_003L)
        background.problems shouldBe setOf(HistoryProblem(HistorySource.LOCATION, HistoryProblemReason.TIME_UNCERTAIN))
        background.sections.filterIsInstance<HistorySection.Location>().single().coverage shouldBe
            HistoryLocationCoverage.LEGACY_UNVERIFIABLE
        database.locationFileIdentityDao().byPortableIds(points.map { it.first }).map { it.portableId }.toSet() shouldBe
            points.map { it.first }.toSet()
    }

    @Test fun `metadata-only imported group never borrows same-time legacy capture`() = runTest {
        legacyLocation(1, 1_000, 2_000)
        annotation("metadata", 500, 2_500, emptyList())
        val page = reader.read(HistoryRangeRequest(organizationRange())).shouldBeInstanceOf<HistoryRangeRead.Page>()
        page.rows.single().sections.map { it.source } shouldBe listOf(null)
        page.rows.single().problems shouldBe emptySet()
    }

    @Test fun `local retrospective group preserves origin filters and original measurements`() = runTest {
        legacyLocation(1, 1_000, 2_000)
        annotation("walk", 500, 2_500, null)
        val filtered = reader.read(HistoryRangeRequest(organizationRange().copy(origins = setOf(HistoryOrigin.ORGANIZATION))))
            .shouldBeInstanceOf<HistoryRangeRead.Page>().rows.single()
        filtered.sections.filterIsInstance<HistorySection.Location>() shouldBe emptyList()
        val selected = reader.read(HistoryRangeRequest(organizationRange().copy(origins = setOf(HistoryOrigin.ORGANIZATION, HistoryOrigin.LEGACY))))
            .shouldBeInstanceOf<HistoryRangeRead.Page>()
        selected.rows.single { it.purpose == HistoryPurpose.ORGANIZATION }.sections.filterIsInstance<HistorySection.Location>()
            .single().points.single().timeMs shouldBe 1_001L
        database.sessionSegmentDao().countTotal() shouldBe 1L
    }

    @Test fun `narrow annotation selects exact Location timestamps and withholds crossing Steps total`() = runTest {
        database.sessionSegmentDao().insert(SessionSegment(1, 1_000, 2_000, 0f, 100,
            null, null, 4, SegmentSource.USER_CREATED, null, 2_000))
        point("before", 1_099, imported = false)
        point("first", 1_100, imported = false)
        point("last", 1_499, imported = false)
        point("after", 1_500, imported = false)
        annotation("short", 1_100, 1_500, null)
        annotation("whole", 900, 2_100, null)
        val page = reader.read(HistoryRangeRequest(organizationRange().copy(
            sources = setOf(HistorySource.LOCATION, HistorySource.STEPS),
            origins = setOf(HistoryOrigin.LEGACY, HistoryOrigin.ORGANIZATION))))
            .shouldBeInstanceOf<HistoryRangeRead.Page>()
        val short = page.rows.single { it.name == "short" }
        short.sections.filterIsInstance<HistorySection.Location>().single().points.map { it.timeMs } shouldBe
            listOf(1_100L, 1_499L)
        short.sections.filterIsInstance<HistorySection.Steps>() shouldBe emptyList()
        short.problems shouldBe setOf(HistoryProblem(HistorySource.STEPS, HistoryProblemReason.TIME_UNCERTAIN))
        val whole = page.rows.single { it.name == "whole" }
        whole.sections.filterIsInstance<HistorySection.Steps>().single().history.count shouldBe 100L
        // Organization neither cuts the original stored total nor creates a new acquisition row.
        database.sessionSegmentDao().countTotal() shouldBe 1L
        database.trackingHistoryReadDao().segments(listOf(1L)).single().steps shouldBe 100
    }

    @Test fun `malformed organization metadata does not discard an independent saved source`() = runTest {
        legacyLocation(1, 100, 200)
        database.organizationAnnotationDao().insert(OrganizationAnnotationEntity("broken", 1, 0, 0,
            null, 150, 250, "UTC", "broken", null, false, "invalid selection"))
        val page = reader.read(HistoryRangeRequest(range(0, 300).copy(
            origins = setOf(HistoryOrigin.LEGACY, HistoryOrigin.ORGANIZATION),
            purposes = setOf(HistoryPurpose.CAPTURE, HistoryPurpose.ORGANIZATION))))
            .shouldBeInstanceOf<HistoryRangeRead.Page>()
        page.rows.size shouldBe 2
        page.rows.first().problems shouldBe setOf(HistoryProblem(null, HistoryProblemReason.INTEGRITY_UNAVAILABLE))
        page.rows.last().sections.filterIsInstance<HistorySection.Location>().single().points.single().timeMs shouldBe 101L
    }

    @Test fun `linked recording interval selects exact Location and withholds its crossing Steps total`() = runTest {
        database.sessionSegmentDao().insert(SessionSegment(1, 1_000, 2_000, 0f, 100,
            null, null, 4, SegmentSource.USER_CREATED, null, 2_000))
        val saved = listOf(point("before-linked", 1_099, imported = false), point("first-linked", 1_100, imported = false),
            point("last-linked", 1_499, imported = false), point("after-linked", 1_500, imported = false))
        database.organizationAnnotationDao().insert(OrganizationAnnotationEntity("linked-short", 1, 0, 0,
            "segment:1", 1_100, 1_500, "UTC", "Short walk", null, false))
        val row = reader.read(HistoryRangeRequest(range(0, 3_000).copy(
            sources = setOf(HistorySource.LOCATION, HistorySource.STEPS),
            origins = setOf(HistoryOrigin.LEGACY, HistoryOrigin.ORGANIZATION))))
            .shouldBeInstanceOf<HistoryRangeRead.Page>().rows.single()
        row.name shouldBe "Short walk"
        row.position.startTimeMs shouldBe 1_100L
        row.toExclusiveMs shouldBe 1_500L
        row.sections.filterIsInstance<HistorySection.Location>().single().points.map { it.timeMs } shouldBe
            listOf(1_100L, 1_499L)
        row.sections.filterIsInstance<HistorySection.Steps>() shouldBe emptyList()
        row.problems shouldBe setOf(HistoryProblem(HistorySource.STEPS, HistoryProblemReason.TIME_UNCERTAIN))
        database.trackingHistoryReadDao().segments(listOf(1L)).single().steps shouldBe 100
        database.locationSampleDao().byIds(saved.map { it.second }).map { it.timeMs }.sorted() shouldBe
            listOf(1_099L, 1_100L, 1_499L, 1_500L)
    }

    @Test fun `truncated shared contents always marks later annotation partial for an unseen long recording`() = runTest {
        repeat(100) { offset -> legacyLocation(offset + 1L, 1_000L + offset * 10L, 1_005L + offset * 10L) }
        legacyLocation(101, 50, 11_000)
        point("inside-long-recording", 10_500)
        annotation("early", 100, 200, null)
        annotation("late", 10_000, 11_000, null)
        val range = organizationRange().copy(toExclusiveMs = 12_000,
            origins = setOf(HistoryOrigin.LEGACY, HistoryOrigin.ORGANIZATION))
        val row = reader.read(HistoryRangeRequest(range, limit = 1))
            .shouldBeInstanceOf<HistoryRangeRead.Page>().rows.single()
        row.name shouldBe "late"
        row.sections.filterIsInstance<HistorySection.Location>() shouldBe emptyList()
        row.problems shouldBe setOf(HistoryProblem(null, HistoryProblemReason.DEPENDENCY_OVERFLOW))
        // The unconsumed recording starts before this group but really overlaps it.
        database.trackingHistoryReadDao().segments(listOf(101L)).single().endTimeMs shouldBe 11_000L
    }

    @Test fun `paging keeps the bounded range and metadata changes invalidate its source`() = runTest {
        for (id in 1L..125L) legacyLocation(id, id * 10, id * 10 + 5)
        val source = UnifiedHistoryPagingSource(database, range(0, 2_000), reader::read)
        val first = source.load(PagingSource.LoadParams.Refresh<HistoryContinuation>(null, 200, false))
            .shouldBeInstanceOf<PagingSource.LoadResult.Page<HistoryContinuation, com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow>>()
        first.data.size shouldBe 100
        first.prevKey shouldBe null
        val next = requireNotNull(first.nextKey)
        next.range shouldBe range(0, 2_000)
        val second = source.load(PagingSource.LoadParams.Append(next, 200, false))
            .shouldBeInstanceOf<PagingSource.LoadResult.Page<HistoryContinuation, com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow>>()
        (first.data + second.data).map { it.position }.distinct().size shouldBe 125
        second.nextKey shouldBe null
        val invalidated = CompletableDeferred<Unit>()
        source.registerInvalidatedCallback { invalidated.complete(Unit) }
        database.organizationAnnotationDao().insert(OrganizationAnnotationEntity("hidden", 1, 0, 0,
            "segment:1", null, null, null, null, null, true))
        database.invalidationTracker.refreshAsync()
        withContext(Dispatchers.IO) { withTimeout(5_000) { invalidated.await() } }
        source.invalid shouldBe true
        source.load(PagingSource.LoadParams.Refresh<HistoryContinuation>(null, 10, false))
            .shouldBeInstanceOf<PagingSource.LoadResult.Invalid<HistoryContinuation, com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow>>()
    }

    @Test fun `invalid display zone is unavailable instead of shifting observation dates`() = runTest {
        reader.read(HistoryRangeRequest(range(0, 100).copy(viewZoneId = "not/a-zone"))) shouldBe
            HistoryRangeRead.Unavailable(HistoryProblemReason.TIME_UNCERTAIN)
    }

    private fun range(from: Long, to: Long) = HistoryRange(from, to, "UTC", setOf(HistorySource.LOCATION),
        setOf(HistoryOrigin.LEGACY), setOf(HistoryPurpose.CAPTURE))
    private fun organizationRange() = HistoryRange(0, 3_000, "UTC", setOf(HistorySource.LOCATION),
        setOf(HistoryOrigin.ORGANIZATION, HistoryOrigin.IMPORTED),
        setOf(HistoryPurpose.ORGANIZATION, HistoryPurpose.CAPTURE, HistoryPurpose.BACKGROUND))
    private suspend fun legacyLocation(id: Long, start: Long, end: Long) {
        database.sessionSegmentDao().insert(SessionSegment(id, start, end, 0f, null, null, null,
            1, SegmentSource.USER_CREATED, null, end))
        point("legacy:$id", start + 1, imported = false)
    }
    private suspend fun point(id: String, time: Long, origin: String = "unknown", imported: Boolean = true): Pair<String, Long> {
        val sampleId = database.locationSampleDao().insert(LocationSample(timeMs = time,
            elapsedRealtimeNanos = 0, latE7 = 500_000_000, lonE7 = 140_000_000,
            altitudeM = null, rawGpsAltitudeM = null, hAccM = 10f, vAccM = null,
            speedMps = null, speedAccuracyMps = null, provider = "import", quality = SampleQuality.HIGH,
            motionState = null, policy = null, bucketId = null, createdAt = time))
        database.locationFileIdentityDao().insertIfAbsent(LocationFileIdentityEntity(id, sampleId, imported, origin))
        return id to sampleId
    }
    private suspend fun archive(id: String, points: List<Pair<String, Long>>) {
        database.locationFileIdentityDao().recordArchive(LocationFileArchiveEntity(id,
            LocationFileArchiveEntity.FORMAT, LocationFileArchiveEntity.VERSION, 0, points.size))
        database.locationFileIdentityDao().recordArchivePoints(points.mapIndexed { index, point ->
            LocationFileArchivePointEntity(id, index, point.first, point.second) })
    }
    private suspend fun annotation(id: String, from: Long, to: Long, selection: List<OrganizationSourceSelector>?) {
        database.organizationAnnotationDao().insert(OrganizationAnnotationEntity(id, 1, 0, 0, null,
            from, to, "UTC", id, null, false, selection?.let(OrganizationSourceSelectionCodec::encode)))
    }
}
