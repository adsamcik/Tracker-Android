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

    /** Reads all-day observations and imported copies within the measured window. */
    @Query(
        "SELECT * FROM pressure_sample WHERE source_signal_id LIKE 'all-day:pressure:%' " +
            "AND time_ms >= :fromMs AND time_ms < :toMs " +
            "ORDER BY time_ms, source_signal_id LIMIT :limit",
    )
    suspend fun allDayBetween(fromMs: Long, toMs: Long, limit: Int): List<PressureSample>

    /** Reads on-device all-day observations, excluding imported copies before applying the row limit. */
    @Query("""
        SELECT * FROM pressure_sample
        WHERE source_signal_id LIKE 'all-day:pressure:%' AND time_ms >= :fromMs AND time_ms < :toMs
          AND instr(',' || COALESCE(capability_flags, '') || ',', ',PORTABLE_IMPORT,') = 0
        ORDER BY time_ms, source_signal_id LIMIT :limit
    """)
    suspend fun nativeAllDayBetween(fromMs: Long, toMs: Long, limit: Int): List<PressureSample>

    /** Pages all-day observations and imported copies by stable row ID within the measured window. */
    @Query(
        "SELECT * FROM pressure_sample WHERE source_signal_id LIKE 'all-day:pressure:%' " +
            "AND time_ms >= :fromMs AND time_ms < :toMs " +
            "AND id > :afterId ORDER BY id LIMIT :limit",
    )
    suspend fun allDayPage(fromMs: Long, toMs: Long, afterId: Long, limit: Int): List<PressureSample>

    @Query("SELECT * FROM pressure_sample WHERE source_signal_id = :sourceSignalId LIMIT 1")
    suspend fun getBySourceSignalId(sourceSignalId: String): PressureSample?

    /** Returns saved observations selected by their original source identities. */
    @Query("SELECT * FROM pressure_sample WHERE source_signal_id IN (:identities)")
    suspend fun bySourceSignalIds(identities: List<String>): List<PressureSample>

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

    /** Exact saved leaves selected by the bounded measured-history key page. */
    @Query("SELECT * FROM pressure_sample WHERE id IN (:ids) ORDER BY id")
    suspend fun historyRows(ids: List<Long>): List<PressureSample>
}
