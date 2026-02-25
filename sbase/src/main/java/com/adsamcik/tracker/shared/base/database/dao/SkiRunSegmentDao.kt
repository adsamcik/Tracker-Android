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

    /** Delete all segments for a session. */
    @Query("DELETE FROM ski_run_segment WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: Long): Int

    /** Delete all ski run segments. */
    @Query("DELETE FROM ski_run_segment")
    fun deleteAll()
}
