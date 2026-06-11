package com.adsamcik.tracker.logger

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.logger.database.LoggerBaseDao

/**
 * DAO for crash data
 */
@Dao
interface CrashDataDao : LoggerBaseDao<CrashData> {
    /**
     * Get all crash data from database
     */
    @Query("SELECT * from crash_data")
    suspend fun getAll(): List<CrashData>

    /**
     * Get all crash data ordered by id from database
     */
    @Query("SELECT * from crash_data ORDER BY id DESC")
    suspend fun getAllOrderedDesc(): List<CrashData>

    /**
     * Get limited number of crash data ordered by descending order with id.
     */
    @Query("SELECT * from crash_data ORDER BY id DESC LIMIT :count")
    suspend fun getLastOrderedDesc(count: Int): List<CrashData>

    /**
     * Get crash count
     */
    @Query("SELECT COUNT(*) from crash_data")
    fun getCrashCount(): Int

    /**
     * Clear all crash data
     */
    @Query("DELETE from crash_data")
    fun clearAll()
}
