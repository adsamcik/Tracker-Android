package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.stats.api.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*

/** Real Room count discovery/source qualification/facade; released rows are constructed fixtures. NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasuredLegacyWifiCountRoomTest {
    private lateinit var database: AppDatabase
    @Before fun setup() = kotlinx.coroutines.runBlocking {
        database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
        database.sourceEvidenceStateDao().ensure()
    }
    @After fun close() = database.close()

    @Test fun `original count and position appear without networks session or modern authority`() = runTest {
        count(7, 2_000L, 12)
        val range = range(2_000L, 2_001L)
        val read = database.withTransaction {
            val keys = radio().candidates(HistorySource.WIFI, range, null)
            assertEquals(7L, keys.single().rowId)
            radio().qualifyBackgroundAndLegacy(HistorySource.WIFI, keys, range)
        }
        val row = read.rows.values.single()
        assertEquals(2_000L, row.position.startTimeMs); assertNull(row.toExclusiveMs); assertNull(row.name)
        assertEquals(HistoryPurpose.UNKNOWN, row.purpose); assertEquals(setOf(HistoryOrigin.LEGACY), row.origins)
        assertEquals(HistoryMeasurement.LegacyWifiCount(12, 50.1, 14.2, 350.0), row.measurement)
        assertTrue(row.sections.isEmpty()); assertTrue(read.problems.isEmpty())
        assertEquals(0, database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM wifi_captured_fact_revision").use {
            it.moveToFirst(); it.getInt(0) })
    }

    @Test fun `the actual range facade keeps original counts on the displayed measured day`() = runTest {
        val zone = ZoneId.of("Europe/Prague")
        val day = LocalDate.of(2026, 10, 8)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        count(1, start - 1, 5); count(2, start, 8); count(3, end, 11)
        val reader = rangeReaderForRoomTest(database, ApplicationProvider.getApplicationContext())
        val page = assertIs<HistoryRangeRead.Page>(reader.read(HistoryRangeRequest(range(start, end).copy(viewZoneId = zone.id))))
        assertEquals(1, page.rows.size); assertNull(page.next)
        assertEquals(start, page.rows.single().position.startTimeMs)
        assertEquals(8, assertIs<HistoryMeasurement.LegacyWifiCount>(page.rows.single().measurement).count)
    }

    @Test fun `more than4096 original counts remain reachable through strict bounded same-time keysets`() = runTest {
        database.withTransaction { for (id in 1L..4_201L) count(id, 2_000L, 3) }
        val reader = radio(); val selected = range(0, 3_000L)
        val ids = mutableListOf<String>(); var after: HistoryPosition? = null
        var pages = 0
        while (true) {
            val keys = database.withTransaction { reader.candidates(HistorySource.WIFI, selected, after) }
            assertTrue(keys.size <= 101)
            val consumed = keys.take(100)
            if (consumed.isEmpty()) break
            assertTrue(++pages <= 43)
            assertTrue(consumed.zipWithNext().all { (a, b) -> measuredHistoryPosition(a) < measuredHistoryPosition(b) })
            val read = database.withTransaction { reader.qualifyBackgroundAndLegacy(HistorySource.WIFI, consumed, selected) }
            assertEquals(consumed.size, read.rows.size); assertTrue(read.problems.isEmpty())
            ids += read.rows.keys.map { it.stableId }
            after = measuredHistoryPosition(consumed.last())
            if (keys.size <= 100) break
        }
        assertEquals(4_201, ids.size); assertEquals(4_201, ids.distinct().size)
    }

    @Test fun `source purpose and origin exclusions never lend modern recording ownership to old counts`() = runTest {
        count(1, 2_000L, 5)
        val reader = radio(); val selected = range(0, 3_000L)
        val keys = reader.candidates(HistorySource.WIFI, selected, null)
        for (excluded in listOf(selected.copy(sources = setOf(HistorySource.CELL)),
            selected.copy(origins = setOf(HistoryOrigin.LOCAL)), selected.copy(purposes = setOf(HistoryPurpose.CAPTURE)))) {
            assertTrue(reader.candidates(HistorySource.WIFI, excluded, null).isEmpty())
            assertTrue(reader.qualifyBackgroundAndLegacy(HistorySource.WIFI, keys, excluded).rows.isEmpty())
        }
    }

    @Test fun `original AP and scan count at the same time stay independent rather than guessed duplicates`() = runTest {
        count(1, 2_000L, 9)
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO legacy_wifi_child (legacy_row_id,time_ms,bssid,ssid,capabilities,frequency,level,provenance,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf(1L, 2_000L, "aa:bb:cc:dd:ee:ff", "Saved AP", "", 2_412, -55, "LEGACY_RELEASED", 2_000L))
        val selected = range(0, 3_000L); val reader = radio()
        val read = database.withTransaction { reader.qualifyBackgroundAndLegacy(HistorySource.WIFI,
            reader.candidates(HistorySource.WIFI, selected, null), selected) }
        assertEquals(2, read.rows.size)
        assertEquals(1, read.rows.values.count { it.measurement is HistoryMeasurement.LegacyWifi })
        assertEquals(9, assertIs<HistoryMeasurement.LegacyWifiCount>(read.rows.values.single {
            it.measurement is HistoryMeasurement.LegacyWifiCount }.measurement).count)
    }

    @Test fun `retention floor and exact deletion remove counts without changing a surviving sibling`() = runTest {
        count(1, 1_000L, 4); count(2, 2_000L, 7)
        val selected = range(0, 3_000L); val reader = radio()
        val keys = reader.candidates(HistorySource.WIFI, selected, null)
        database.sourceEvidenceStateDao().updateLifecycle(0, 1_500L, 3_000L)
        val kept = database.withTransaction { reader.qualifyBackgroundAndLegacy(HistorySource.WIFI, keys, selected) }
        assertEquals(listOf(2_000L), kept.rows.keys.map { it.startTimeMs })
        database.openHelper.writableDatabase.execSQL("DELETE FROM legacy_location_wifi_count WHERE id = 2")
        assertTrue(database.withTransaction { reader.qualifyBackgroundAndLegacy(HistorySource.WIFI, keys, selected) }.rows.isEmpty())
    }

    @Test fun `a failed AP sibling read does not discard valid original count data`() = runTest {
        count(1, 2_000L, 9)
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO legacy_wifi_child (legacy_row_id,time_ms,bssid,ssid,capabilities,frequency,level,provenance,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf(1L, 2_000L, "aa:bb:cc:dd:ee:ff", "Saved AP", "", 2_412, -55, "LEGACY_RELEASED", 2_000L))
        val selected = range(0, 3_000L); val reader = radio()
        val keys = reader.candidates(HistorySource.WIFI, selected, null)
        database.openHelper.writableDatabase.execSQL("DROP TABLE legacy_wifi_child")
        val read = database.withTransaction { reader.qualifyBackgroundAndLegacy(HistorySource.WIFI, keys, selected) }
        assertEquals(9, assertIs<HistoryMeasurement.LegacyWifiCount>(read.rows.values.single().measurement).count)
        assertTrue(HistoryProblem(HistorySource.WIFI, HistoryProblemReason.STORAGE_UNAVAILABLE) in read.problems)
    }

    private fun count(id: Long, time: Long, value: Int) = database.openHelper.writableDatabase.execSQL(
        "INSERT INTO legacy_location_wifi_count(id,time,count,lat,lon,alt) VALUES (?,?,?,?,?,?)",
        arrayOf(id, time, value, 50.1, 14.2, 350.0))
    private fun radio() = MeasuredRadioHistoryReader(database, RoomAmbientWifiRepository(database, Dispatchers.Unconfined),
        RoomAmbientCellRepository(database, Dispatchers.Unconfined))
    private fun range(from: Long, to: Long) = HistoryRange(from, to, "UTC", sources = setOf(HistorySource.WIFI),
        origins = setOf(HistoryOrigin.LEGACY), purposes = setOf(HistoryPurpose.UNKNOWN), mode = HistoryBrowseMode.OBSERVATIONS)
}
