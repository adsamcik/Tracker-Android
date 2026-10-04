package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.AmbientRadioRetentionDecision
import com.adsamcik.tracker.shared.base.database.applyAmbientCellRetentionDecision
import com.adsamcik.tracker.shared.base.database.applyAmbientWifiRetentionDecision
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.PortableRadioChildIntegrity
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.*
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Room discovery, source import/qualification and day composition; no provider is started. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnifiedHistoryMeasuredDayRoomTest {
    private lateinit var database: AppDatabase
    private lateinit var reader: UnifiedHistoryRangeReader
    private val io = UnconfinedTestDispatcher()

    @Before fun setup() = runTest {
        val context: Application = ApplicationProvider.getApplicationContext()
        database = AppDatabase.testDatabase(context)
        database.sourceEvidenceStateDao().ensure()
        database.applyAmbientWifiRetentionDecision(
            AmbientRadioRetentionDecision.GrantPortableImport("wifi-retention", 0, "boot", 0, 0))
        database.applyAmbientCellRetentionDecision(
            AmbientRadioRetentionDecision.GrantPortableImport("cell-retention", 0, "boot", 0, 0))
        reader = rangeReaderForRoomTest(database, context)
    }

    @After fun close() = database.close()

    @Test fun `a captured Location recording does not widen the selected measured dates`() = runTest {
        legacySession(1, 10, 400)
        val outside = listOf(point(99, false), point(200, false))
        val inside = listOf(point(100, false), point(199, false))
        val row = page(HistoryRange(100, 200, "UTC", setOf(HistorySource.LOCATION),
            setOf(HistoryOrigin.LEGACY), setOf(HistoryPurpose.CAPTURE))).rows.single()
        assertEquals("segment:1", row.position.stableId)
        assertEquals(inside, row.locations().map { it.sampleId })
        assertTrue(row.locations().none { it.sampleId in outside })
        assertEquals(listOf(100L, 199L), row.locations().map { it.timeMs })
        assertEquals(HistoryLocationCoverage.LEGACY_UNVERIFIABLE,
            row.sections.filterIsInstance<HistorySection.Location>().single().coverage)
        assertEquals(4L, database.locationSampleDao().countAll())
    }

    @Test fun `out of date saved points do not exhaust the selected Location payload budget`() = runTest {
        legacySession(1, 0, 20_000)
        database.withTransaction { for (time in 1L..4_097L) point(time, false) }
        val saved = point(10_000, false)
        val row = page(HistoryRange(10_000, 10_001, "UTC", setOf(HistorySource.LOCATION),
            setOf(HistoryOrigin.LEGACY), setOf(HistoryPurpose.CAPTURE))).rows.single()
        assertEquals(listOf(saved), row.locations().map { it.sampleId })
        assertTrue(row.problems.isEmpty())
        assertEquals(4_098L, database.locationSampleDao().countAll())
    }

    @Test fun `one overnight delivery shows each radio child once on its measured local day`() = runTest {
        val measured = listOf(DAY - 1, DAY)
        val archives = importRadios("overnight", measured, DAY + 500)
        val range = radioRange(DAY - 1, DAY + 1)
        var next: HistoryContinuation? = null
        val rows = mutableListOf<UnifiedHistoryRow>()
        do {
            val read = page(range, 1, next)
            rows += read.rows
            next = read.next
        } while (next != null)
        assertEquals(2, rows.size)
        assertEquals(listOf(DAY, DAY - 1), rows.flatMap { it.wifi().networks.map { child -> child.observedTimeMs } })
        assertEquals(listOf(DAY, DAY - 1), rows.flatMap { it.cell().towers.map { child -> child.observedTimeMs } })
        rows.forEach {
            assertEquals(1, it.wifi().readingCount)
            assertEquals(1, it.cell().readingCount)
            assertEquals(2, it.wifi().read.facts.single().observationCount)
            assertEquals(2, it.cell().read.facts.single().observationCount)
            assertEquals("America/Los_Angeles", it.wifi().read.facts.single().storedZoneId)
        }
        assertEquals(archives.first, rows.first().wifi().read.facts.single().identity)
        assertEquals(archives.second, rows.last().cell().read.facts.single().identity)
        assertNull(database.sourcePolicyDao().authority())
        assertEquals(2, database.importedRadioChildDao().wifiBackgroundChildrenForFacts(
            listOf(rows.first().wifi().read.facts.single().identity), 10).size)
    }

    @Test fun `changing display zone regroups radio children without editing the source day or bounds`() = runTest {
        val measured = listOf(DAY - 1, DAY)
        importRadios("zone", measured, DAY + 500)
        val utc = page(radioRange(DAY - 1, DAY + 1))
        val newYork = page(radioRange(DAY - 1, DAY + 1).copy(viewZoneId = "America/New_York"))
        assertEquals(2, utc.rows.size)
        val row = newYork.rows.single()
        assertEquals(measured, row.wifi().networks.map { it.observedTimeMs })
        assertEquals(measured, row.cell().towers.map { it.observedTimeMs })
        assertEquals(2, row.wifi().readingCount)
        assertEquals(2, row.cell().readingCount)
        assertEquals(utc.rows.first().wifi().read.facts.single(), row.wifi().read.facts.single())
        assertEquals(utc.rows.first().cell().read.facts.single(), row.cell().read.facts.single())
        assertTrue(row.position.stableId.endsWith(":America/New_York"))
    }

    @Test fun `a later delivery time cannot create a measured zero or move its earlier child`() = runTest {
        importRadios("delayed", listOf(DAY - 1), DAY + 500)
        val earlier = page(radioRange(0, DAY)).rows.single()
        assertEquals(listOf(DAY - 1), earlier.wifi().networks.map { it.observedTimeMs })
        assertEquals(listOf(DAY - 1), earlier.cell().towers.map { it.observedTimeMs })
        assertTrue(page(radioRange(DAY, DAY + 1_000)).rows.isEmpty())
    }

    @Test fun `positive radio gaps include the day start and exclude the next day boundary`() = runTest {
        val intervals = listOf((DAY - 1) to DAY, DAY to (DAY + 1), (2 * DAY) to (2 * DAY + 1))
        importGaps("boundary", intervals)
        val row = page(radioRange(DAY, 2 * DAY)).rows.single()
        assertEquals(listOf(DAY to (DAY + 1)), row.wifi().read.gaps.map { it.startTimeMs to it.endTimeMs })
        assertEquals(listOf(DAY to (DAY + 1)), row.cell().read.gaps.map { it.startTimeMs to it.endTimeMs })
        assertNull(row.wifi().readingCount)
        assertNull(row.cell().readingCount)
    }

    @Test fun `an overnight gap keeps its actual unsplit interval in both displayed day sections`() = runTest {
        importGaps("crossing", listOf((DAY - 1) to (DAY + 1)))
        val rows = page(radioRange(DAY - 1, DAY + 1)).rows
        assertEquals(2, rows.size)
        assertEquals(1, rows.map { it.wifi().read.gaps.single().identity }.distinct().size)
        assertEquals(1, rows.map { it.cell().read.gaps.single().identity }.distinct().size)
        rows.forEach {
            assertEquals((DAY - 1) to (DAY + 1), it.wifi().read.gaps.single().let { gap -> gap.startTimeMs to gap.endTimeMs })
            assertEquals((DAY - 1) to (DAY + 1), it.cell().read.gaps.single().let { gap -> gap.startTimeMs to gap.endTimeMs })
            assertNull(it.wifi().readingCount)
            assertNull(it.cell().readingCount)
        }
    }

    @Test fun `an unavailable radio sibling does not discard saved Location or the other radio`() = runTest {
        importRadios("sibling", listOf(DAY), DAY + 1)
        val saved = point(DAY)
        val archiveId = AmbientWifiPortableIntegrity.opaqueIdentity("archive", "sibling")
        database.ambientWifiFactDao().deleteImportReceipts(listOf(archiveId))
        val row = page(radioRange(DAY, DAY + 10).copy(
            sources = setOf(HistorySource.LOCATION, HistorySource.WIFI, HistorySource.CELL))).rows.single()
        assertEquals(listOf(saved), row.locations().map { it.sampleId })
        assertEquals(1, row.cell().readingCount)
        assertTrue(row.sections.none { it is HistorySection.BackgroundWifi })
        assertTrue(HistoryProblem(HistorySource.WIFI, HistoryProblemReason.INTEGRITY_UNAVAILABLE) in row.problems)
    }

    private suspend fun page(range: HistoryRange, limit: Int = 100, after: HistoryContinuation? = null) =
        assertIs<HistoryRangeRead.Page>(reader.read(HistoryRangeRequest(range, limit, after)))

    private fun radioRange(from: Long, to: Long) = HistoryRange(from, to, "UTC",
        setOf(HistorySource.WIFI, HistorySource.CELL), setOf(HistoryOrigin.IMPORTED), setOf(HistoryPurpose.BACKGROUND))

    private fun UnifiedHistoryRow.wifi() = sections.filterIsInstance<HistorySection.BackgroundWifi>().single()
    private fun UnifiedHistoryRow.cell() = sections.filterIsInstance<HistorySection.BackgroundCell>().single()
    private fun UnifiedHistoryRow.locations() = sections.filterIsInstance<HistorySection.Location>().flatMap { it.points }

    private suspend fun legacySession(id: Long, start: Long, end: Long) {
        database.sessionSegmentDao().insert(SessionSegment(id, start, end, 0f, null, null, null,
            1, SegmentSource.USER_CREATED, null, end))
    }

    private suspend fun point(time: Long, imported: Boolean = true): Long {
        val id = database.locationSampleDao().insert(LocationSample(timeMs = time, elapsedRealtimeNanos = 0,
            latE7 = 500_000_000, lonE7 = 140_000_000, altitudeM = null, rawGpsAltitudeM = null,
            hAccM = 10f, vAccM = null, speedMps = null, speedAccuracyMps = null, provider = "import",
            quality = SampleQuality.HIGH, motionState = null, policy = null, bucketId = null, createdAt = time))
        database.locationFileIdentityDao().insertIfAbsent(LocationFileIdentityEntity("measured:$id", id, imported, "unknown"))
        return id
    }

    private suspend fun importRadios(name: String, measured: List<Long>, delivered: Long): Pair<String, String> {
        val zone = "America/Los_Angeles"
        val epochDay = Instant.ofEpochMilli(delivered).atZone(ZoneId.of(zone)).toLocalDate().toEpochDay()
        val wifiId = AmbientWifiPortableIntegrity.opaqueIdentity("fact", name)
        val cellId = AmbientCellPortableIntegrity.opaqueIdentity("fact", name)
        val wifiFact = AmbientWifiFact(wifiId, AmbientWifiOrigin.PORTABLE_IMPORT, measured.min() - 1, delivered,
            delivered + 1, epochDay, zone, AmbientWifiCoverage.UNVERIFIABLE, measured.size, measured.size,
            0, 0, 0, -60, -60, -60.0)
        val cellFact = AmbientCellFact(cellId, AmbientCellOrigin.PORTABLE_IMPORT, measured.min() - 1, delivered,
            delivered + 1, epochDay, zone, AmbientCellCoverage.UNVERIFIABLE, AmbientCellSubscriptionCompleteness.UNKNOWN,
            measured.size, measured.size, AmbientCellTechnologyMix(0, 0, 0, 0, measured.size, 0),
            AmbientCellQualityDistribution(measured.size, 0, 0, 0, 0, 0))
        val wifiArchive = AmbientWifiPortableIntegrity.createArchive(
            AmbientWifiPortableIntegrity.opaqueIdentity("archive", name),
            listOf(AmbientWifiPortableIntegrity.createFact(wifiFact)), emptyList())
        val cellArchive = AmbientCellPortableIntegrity.createArchive(
            AmbientCellPortableIntegrity.opaqueIdentity("archive", name),
            listOf(AmbientCellPortableIntegrity.createFact(cellFact)), emptyList())
        assertIs<ImportPortableAmbientWifiResult.Applied>(RoomAmbientWifiPortableTransfer(database,
            RoomAmbientWifiRepository(database, io), io).importArchive(ImportPortableAmbientWifiRequest(wifiArchive,
            PortableAmbientWifiImportReceipt("wifi:$name", "wifi:$name", "wifi", delivered + 10), 0)))
        assertIs<ImportPortableAmbientCellResult.Applied>(RoomAmbientCellPortableTransfer(database,
            RoomAmbientCellRepository(database, io), io).importArchive(ImportPortableAmbientCellRequest(cellArchive,
            PortableAmbientCellImportReceipt("cell:$name", "cell:$name", "cell", delivered + 10), 0)))
        database.importedRadioChildDao().insertWifiBackground(measured.mapIndexed { index, time ->
            ImportedAmbientWifiChildEntity(wifiArchive.archiveId, wifiId, 1, index, index + 1L, time, 1,
                "bssid:$index", "Network $index", 2412, -60,
                PortableRadioChildIntegrity.digest("wifi", listOf(wifiId), listOf(1L, index, index + 1L,
                    time, 1L, "bssid:$index", "Network $index", 2412, -60)))
        })
        database.importedRadioChildDao().insertCellBackground(measured.mapIndexed { index, time ->
            ImportedAmbientCellChildEntity(cellArchive.archiveId, cellId, 1, index, index + 1L, time, 1,
                "LTE:$index", "LTE", true, -60, PortableRadioChildIntegrity.digest("cell", listOf(cellId),
                    listOf(1L, index, index + 1L, time, 1L, "LTE:$index", "LTE", true, -60)))
        })
        return wifiId to cellId
    }

    private suspend fun importGaps(name: String, intervals: List<Pair<Long, Long>>) {
        val wifi = AmbientWifiPortableIntegrity.createArchive(
            AmbientWifiPortableIntegrity.opaqueIdentity("archive", name), emptyList(), intervals.mapIndexed { index, (start, end) ->
                AmbientWifiPortableIntegrity.createGap(AmbientWifiGap(
                    AmbientWifiPortableIntegrity.opaqueIdentity("gap", "$name:$index"), AmbientWifiOrigin.PORTABLE_IMPORT,
                    start / DAY, start, end, "UTC", "OS_LIMITED")) })
        val cell = AmbientCellPortableIntegrity.createArchive(
            AmbientCellPortableIntegrity.opaqueIdentity("archive", name), emptyList(), intervals.mapIndexed { index, (start, end) ->
                AmbientCellPortableIntegrity.createGap(AmbientCellGap(
                    AmbientCellPortableIntegrity.opaqueIdentity("gap", "$name:$index"), AmbientCellOrigin.PORTABLE_IMPORT,
                    start / DAY, start, end, "UTC", "OS_LIMITED")) })
        val received = intervals.maxOf { it.second } + 10
        assertIs<ImportPortableAmbientWifiResult.Applied>(RoomAmbientWifiPortableTransfer(database,
            RoomAmbientWifiRepository(database, io), io).importArchive(ImportPortableAmbientWifiRequest(wifi,
            PortableAmbientWifiImportReceipt("wifi:$name", "wifi:$name", "wifi", received), 0)))
        assertIs<ImportPortableAmbientCellResult.Applied>(RoomAmbientCellPortableTransfer(database,
            RoomAmbientCellRepository(database, io), io).importArchive(ImportPortableAmbientCellRequest(cell,
            PortableAmbientCellImportReceipt("cell:$name", "cell:$name", "cell", received), 0)))
    }

    private companion object { const val DAY = 86_400_000L }
}
