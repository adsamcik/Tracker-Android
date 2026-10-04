package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationFileIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.OrganizationAnnotationEntity
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Room discovery/retained saved-point reads. Providers and UI are deliberately not activated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnifiedHistoryDayGroupingRoomTest {
    private lateinit var database: AppDatabase
    private lateinit var reader: UnifiedHistoryRangeReader

    @Before fun setup() = runTest {
        val context: Application = ApplicationProvider.getApplicationContext()
        database = AppDatabase.testDatabase(context)
        database.sourceEvidenceStateDao().ensure()
        reader = rangeReaderForRoomTest(database, context)
    }
    @After fun close() = database.close()

    @Test fun `UTC midnight and a different displayed zone group the same saved points honestly`() = runTest {
        val midnight = Instant.parse("2026-10-03T00:00:00Z").toEpochMilli()
        val ids = listOf(point(midnight - 1), point(midnight))
        val utc = page(range(midnight - 1, midnight + 1))
        assertEquals(2, utc.rows.size)
        assertEquals(listOf(midnight, midnight - 1), utc.rows.flatMap { it.locations().map { p -> p.timeMs } })
        val newYork = page(range(midnight - 1, midnight + 1, "America/New_York"))
        assertEquals(1, newYork.rows.size)
        assertEquals("${LocalDate.of(2026, 10, 2).toEpochDay()}:America/New_York", newYork.rows.single().position.stableId)
        assertEquals(ids.toSet(), newYork.rows.single().locations().map { it.sampleId }.toSet())
        assertEquals(setOf(HistoryOrigin.IMPORTED), newYork.rows.single().origins)
        assertEquals(listOf(midnight - 1, midnight), newYork.rows.single().locations().map { it.timeMs })
        assertEquals(2L, database.locationSampleDao().countAll())
        assertNull(database.sourcePolicyDao().authority())
    }

    @Test fun `a Prague spring clock change has a 23 hour displayed day and half open boundaries`() = runTest {
        val zone = ZoneId.of("Europe/Prague")
        val date = LocalDate.of(2026, 3, 29)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        point(start - 1)
        val selected = listOf(point(start), point(end - 1))
        point(end)
        val row = page(range(start, end, zone.id)).rows.single()
        assertEquals(23 * 3_600_000L, requireNotNull(row.toExclusiveMs) - row.position.startTimeMs)
        assertEquals(start, row.position.startTimeMs)
        assertEquals(end, row.toExclusiveMs)
        assertEquals(selected.toSet(), row.locations().map { it.sampleId }.toSet())
    }

    @Test fun `sparse all time day pagination skips whole dense days without losing the frontier`() = runTest {
        val start = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli()
        for (index in 0..250) {
            point(start + index * 2 * DAY + 1)
            point(start + index * 2 * DAY + 2)
        }
        val selection = range(start, start + 502 * DAY)
        var after: HistoryContinuation? = null
        val positions = mutableListOf<HistoryPosition>()
        val sampleIds = mutableListOf<Long?>()
        do {
            val page = assertIs<HistoryRangeRead.Page>(reader.read(HistoryRangeRequest(selection, 37, after)))
            positions += page.rows.map { it.position }
            sampleIds += page.rows.flatMap { it.locations().map { point -> point.sampleId } }
            after = page.next
        } while (after != null)
        assertEquals(251, positions.size)
        assertEquals(251, positions.distinct().size)
        assertTrue(positions.zipWithNext().all { (a, b) -> a < b })
        assertEquals(502, sampleIds.size)
        assertEquals(502, sampleIds.distinct().size)
    }

    @Test fun `a file point does not join an unrelated old session merely through its time`() = runTest {
        database.sessionSegmentDao().insert(SessionSegment(1, 1_000, 2_000, 0f, null, null, null,
            1, SegmentSource.USER_CREATED, null, 2_000))
        val existing = point(1_100, imported = false)
        val imported = point(1_200)
        val result = page(range(0, 3_000).copy(origins = setOf(HistoryOrigin.LEGACY, HistoryOrigin.IMPORTED),
            purposes = setOf(HistoryPurpose.CAPTURE, HistoryPurpose.BACKGROUND)))
        assertEquals(listOf(existing), result.rows.single { it.purpose == HistoryPurpose.CAPTURE }.locations().map { it.sampleId })
        assertEquals(listOf(imported), result.rows.single { it.purpose == HistoryPurpose.BACKGROUND }.locations().map { it.sampleId })
    }

    @Test fun `a hidden or narrowed legacy display interval does not make its remaining saved points disappear`() = runTest {
        database.sessionSegmentDao().insert(SessionSegment(1, 1_000, 2_000, 0f, null, null, null,
            1, SegmentSource.USER_CREATED, null, 2_000))
        val saved = point(1_100, imported = false)
        database.organizationAnnotationDao().insert(OrganizationAnnotationEntity("edited", 1, 0, 0,
            "segment:1", 1_500, 2_000, "UTC", null, null, false))
        assertEquals(listOf(saved), page(range(0, 3_000).copy(origins = setOf(HistoryOrigin.LEGACY)))
            .rows.single().locations().map { it.sampleId })
    }

    @Test fun `an oversized day reports a source limit instead of returning a truncated point list`() = runTest {
        for (time in 1L..4_097L) point(time)
        val row = page(range(0, DAY)).rows.single()
        assertTrue(row.sections.isEmpty())
        assertEquals(setOf(HistoryProblem(HistorySource.LOCATION, HistoryProblemReason.DEPENDENCY_OVERFLOW)), row.problems)
    }

    private suspend fun page(range: HistoryRange) = assertIs<HistoryRangeRead.Page>(reader.read(HistoryRangeRequest(range)))
    private fun range(from: Long, to: Long, zone: String = "UTC") = HistoryRange(from, to, zone,
        setOf(HistorySource.LOCATION), setOf(HistoryOrigin.IMPORTED), setOf(HistoryPurpose.BACKGROUND))
    private fun UnifiedHistoryRow.locations() = sections.filterIsInstance<HistorySection.Location>().flatMap { it.points }
    private suspend fun point(time: Long, imported: Boolean = true): Long {
        val id = database.locationSampleDao().insert(LocationSample(timeMs = time, elapsedRealtimeNanos = 0,
            latE7 = 500_000_000, lonE7 = 140_000_000, altitudeM = null, rawGpsAltitudeM = null,
            hAccM = 10f, vAccM = null, speedMps = null, speedAccuracyMps = null, provider = "import",
            quality = SampleQuality.HIGH, motionState = null, policy = null, bucketId = null, createdAt = time))
        database.locationFileIdentityDao().insertIfAbsent(LocationFileIdentityEntity("day-point:$id", id, imported, "unknown"))
        return id
    }
    private companion object { const val DAY = 86_400_000L }
}
