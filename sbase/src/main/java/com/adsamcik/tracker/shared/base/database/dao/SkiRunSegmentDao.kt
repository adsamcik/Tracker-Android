package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.SkiRunSegment
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing ski_run_segment table.
 */
@Dao
interface SkiRunSegmentDao : BaseDao<SkiRunSegment> {

    /** Get all ski segments for a session, ordered by run index. */
    @Query("SELECT * FROM ski_run_segment WHERE session_id = :sessionId ORDER BY run_index")
    suspend fun getBySession(sessionId: Long): List<SkiRunSegment>

    /** Get ski segments for a session as Flow. */
    @Query("SELECT * FROM ski_run_segment WHERE session_id = :sessionId ORDER BY run_index")
    fun getBySessionFlow(sessionId: Long): Flow<List<SkiRunSegment>>

    /** Get only downhill run segments for a session. */
    @Query("SELECT * FROM ski_run_segment WHERE session_id = :sessionId AND segment_type = 'DOWNHILL_RUN' ORDER BY run_index")
    suspend fun getDownhillRunsBySession(sessionId: Long): List<SkiRunSegment>

    /** Get only lift segments for a session. */
    @Query("SELECT * FROM ski_run_segment WHERE session_id = :sessionId AND segment_type = 'LIFT_UP' ORDER BY run_index")
    suspend fun getLiftSegmentsBySession(sessionId: Long): List<SkiRunSegment>

    /** Check whether any ski segments exist for a session. */
    @Query("SELECT EXISTS(SELECT 1 FROM ski_run_segment WHERE session_id = :sessionId LIMIT 1)")
    suspend fun hasSkiSegments(sessionId: Long): Boolean

    /** Get ski segments overlapping a time range (for matching to trips/sessions by time). */
    @Query("SELECT * FROM ski_run_segment WHERE start_time_ms < :endMs AND end_time_ms > :startMs ORDER BY run_index")
    suspend fun getByTimeRange(startMs: Long, endMs: Long): List<SkiRunSegment>

    /** Delete all segments for a session. */
    @Query("DELETE FROM ski_run_segment WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: Long): Int

    /** Delete all ski run segments. */
    @Query("DELETE FROM ski_run_segment")
    fun deleteAll()
}
