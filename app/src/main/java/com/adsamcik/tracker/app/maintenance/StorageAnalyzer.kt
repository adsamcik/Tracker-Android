package com.adsamcik.tracker.app.maintenance

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Analyzes SQLite database storage usage using PRAGMA queries.
 *
 * Provides total size, per-table row counts, oldest data timestamp,
 * and estimated daily growth rate for the data retention UI.
 */
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
        val sql = "SELECT name FROM sqlite_master WHERE type='table'" +
            " AND name NOT LIKE 'sqlite_%'" +
            " AND name NOT LIKE 'room_%'" +
            " AND name NOT LIKE 'android_%'"
        val cursor = db.query(sql)
        cursor.use {
            while (it.moveToNext()) {
                tables.add(it.getString(0))
            }
        }
        return tables
    }

    private fun quoteId(name: String): String {
        return "\"" + name.replace("\"", "\"\"") + "\""
    }

    private fun queryLong(sql: String): Long {
        val cursor = db.query(sql)
        cursor.use {
            it.moveToFirst()
            return it.getLong(0)
        }
    }

    private fun queryLongOrNull(sql: String): Long? {
        val cursor = db.query(sql)
        cursor.use {
            if (!it.moveToFirst()) return null
            if (it.isNull(0)) return null
            return it.getLong(0)
        }
    }
}

/**
 * Breakdown of database storage usage metrics.
 */
data class StorageBreakdown(
    val totalSizeBytes: Long,
    val tableSizes: Map<String, Long>,
    val rowCounts: Map<String, Long>,
    val oldestDataMs: Long?,
    val estimatedDailyGrowthBytes: Long,
)
