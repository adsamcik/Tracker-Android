package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing pressure_sample table.
 */
@Dao
interface PressureSampleDao : BaseDao<PressureSample> {

    /** Get all pressure samples within time range, ordered by time. */
    @Query("SELECT * FROM pressure_sample WHERE time_ms >= :fromMs AND time_ms <= :toMs ORDER BY time_ms")
    suspend fun getAllBetween(fromMs: Long, toMs: Long): List<PressureSample>

    /** Get pressure samples within time range as Flow. */
    @Query("SELECT * FROM pressure_sample WHERE time_ms >= :fromMs AND time_ms <= :toMs ORDER BY time_ms")
    fun getAllBetweenFlow(fromMs: Long, toMs: Long): Flow<List<PressureSample>>

    /** Count samples in time range. */
    @Query("SELECT COUNT(*) FROM pressure_sample WHERE time_ms >= :fromMs AND time_ms <= :toMs")
    suspend fun countBetween(fromMs: Long, toMs: Long): Int

    /** Delete all pressure samples. */
    @Query("DELETE FROM pressure_sample")
    fun deleteAll()

    /** Delete samples older than given timestamp. */
    @Query("DELETE FROM pressure_sample WHERE time_ms < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int
}
