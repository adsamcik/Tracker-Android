package com.adsamcik.tracker.app.maintenance

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class StorageAnalyzerTest {

    private fun mockDb(
        pageSize: Long = 4096,
        pageCount: Long = 100,
        tables: List<String> = emptyList(),
        rowCounts: Map<String, Long> = emptyMap(),
        minTimes: Map<String, Long?> = emptyMap(),
    ): SupportSQLiteDatabase {
        val db = mockk<SupportSQLiteDatabase>()

        fun cursorOf(value: Long): Cursor = mockk {
            every { moveToFirst() } returns true
            every { getLong(0) } returns value
            every { isNull(0) } returns false
            every { close() } returns Unit
        }

        fun nullCursor(): Cursor = mockk {
            every { moveToFirst() } returns true
            every { isNull(0) } returns true
            every { getLong(0) } returns 0L
            every { close() } returns Unit
        }

        every { db.query("PRAGMA page_size") } returns cursorOf(pageSize)
        every { db.query("PRAGMA page_count") } returns cursorOf(pageCount)

        val tableCursor = mockk<Cursor>()
        var callCount = 0
        every { tableCursor.moveToNext() } answers {
            if (callCount < tables.size) {
                callCount++
                true
            } else {
                false
            }
        }
        var tableIdx = -1
        every { tableCursor.getString(0) } answers {
            tableIdx++
            tables[tableIdx]
        }
        every { tableCursor.close() } returns Unit
        every { db.query(match { it.startsWith("SELECT name FROM sqlite_master") }) } returns tableCursor

        for (table in tables) {
            val count = rowCounts[table] ?: 0L
            val qid = "\"" + table + "\""
            every { db.query("SELECT COUNT(*) FROM " + qid) } returns cursorOf(count)
            val minTime = minTimes[table]
            if (minTime != null) {
                every { db.query("SELECT MIN(time) FROM " + qid) } returns cursorOf(minTime)
            } else {
                every { db.query("SELECT MIN(time) FROM " + qid) } returns nullCursor()
            }
        }

        return db
    }

    @Nested
    inner class TotalSize {
        @Test
        fun totalSizeIsPageSizeTimesPageCount() {
            val result = StorageAnalyzer(mockDb(pageSize = 4096, pageCount = 100)).analyze()
            assertEquals(409_600L, result.totalSizeBytes)
        }
    }

    @Nested
    inner class EmptyDatabase {
        @Test
        fun emptyDatabaseReturnsZeroRowsAndNullOldest() {
            val result = StorageAnalyzer(mockDb(tables = emptyList())).analyze()
            assertEquals(emptyMap<String, Long>(), result.rowCounts)
            assertNull(result.oldestDataMs)
        }
    }

    @Nested
    inner class RowCounts {
        @Test
        fun rowCountsAreReportedPerTable() {
            val result = StorageAnalyzer(mockDb(
                tables = listOf("location_sample", "step_interval"),
                rowCounts = mapOf("location_sample" to 1000L, "step_interval" to 500L),
            )).analyze()
            assertEquals(1000L, result.rowCounts["location_sample"])
            assertEquals(500L, result.rowCounts["step_interval"])
        }
    }

    @Nested
    inner class OldestData {
        @Test
        fun oldestDataIsMinimumAcrossTables() {
            val result = StorageAnalyzer(mockDb(
                tables = listOf("location_sample", "step_interval"),
                rowCounts = mapOf("location_sample" to 1L, "step_interval" to 1L),
                minTimes = mapOf(
                    "location_sample" to 1_600_000_000_000L,
                    "step_interval" to 1_500_000_000_000L,
                ),
            )).analyze()
            assertNotNull(result.oldestDataMs)
            assertEquals(1_500_000_000_000L, result.oldestDataMs)
        }

        @Test
        fun nullOldestWhenNoTimeColumns() {
            val result = StorageAnalyzer(mockDb(
                tables = listOf("some_table"),
                rowCounts = mapOf("some_table" to 10L),
                minTimes = mapOf("some_table" to null),
            )).analyze()
            assertNull(result.oldestDataMs)
        }
    }

    @Nested
    inner class DailyGrowth {
        @Test
        fun dailyGrowthIsZeroWhenNoData() {
            assertEquals(0L, StorageAnalyzer(mockDb()).analyze().estimatedDailyGrowthBytes)
        }

        @Test
        fun dailyGrowthCalculatedFromTotalSizeAndAge() {
            val now = System.currentTimeMillis()
            val tenDaysAgo = now - 10L * 86_400_000L
            val result = StorageAnalyzer(mockDb(
                pageSize = 4096,
                pageCount = 1000,
                tables = listOf("location_sample"),
                rowCounts = mapOf("location_sample" to 100L),
                minTimes = mapOf("location_sample" to tenDaysAgo),
            )).analyze()
            assert(result.estimatedDailyGrowthBytes in 300_000L..500_000L)
        }
    }
}
