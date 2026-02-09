package com.adsamcik.tracker.app.maintenance

import androidx.sqlite.db.SupportSQLiteDatabase

class StorageAnalyzer(private val db: SupportSQLiteDatabase) {

    fun analyze(): StorageBreakdown {
        val pageSize = queryLong("PRAGMA page_size")
        val pageCount = queryLong("PRAGMA page_count")
        val totalSize = pageSize * pageCount

        val rowCounts = mutableMapOf<String, Long>()
        var oldestMs: Long? = null

        for (table in listUserTables()) {
            val count = queryLong("SELECT COUNT(*) FROM " + quoteId(table))
            rowCounts[table] = count

            val oldest = queryLongOrNull("SELECT MIN(time) FROM " + quoteId(table))
            if (oldest != null && (oldestMs == null || oldest < oldestMs)) {
                oldestMs = oldest
            }
        }

        val dailyGrowth = if (oldestMs != null && oldestMs > 0L) {
            val daysElapsed = ((System.currentTimeMillis() - oldestMs).toDouble() / 86_400_000.0).coerceAtLeast(1.0)
            (totalSize / daysElapsed).toLong()
        } else {
            0L
        }

        return StorageBreakdown(
            totalSizeBytes = totalSize,
            tableSizes = emptyMap(),
            rowCounts = rowCounts,
            oldestDataMs = oldestMs,
            estimatedDailyGrowthBytes = dailyGrowth,
        )
    }

    private fun listUserTables(): List<String> {
        val tables = mutableListOf<String>()
