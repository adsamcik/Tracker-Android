package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity

/**
 * DAO for the write-ahead log of tracking signals.
 * Used by [DurableSignalBuffer] to checkpoint in-memory signals
 * and recover them after a crash.
 */
@Dao
interface PendingSignalDao {

	@Insert
	suspend fun insertAll(signals: List<PendingSignalEntity>)

	@Query(
		"""
		SELECT * FROM pending_signal
		WHERE session_id = :sessionId
		ORDER BY created_at ASC, id ASC
		LIMIT :limit
		""",
	)
	suspend fun getOldest(sessionId: Long, limit: Int = 100): List<PendingSignalEntity>

	@Query("DELETE FROM pending_signal WHERE id IN (:ids)")
	suspend fun deleteByIds(ids: List<Long>)

	@Query("DELETE FROM pending_signal WHERE session_id = :sessionId")
	suspend fun deleteBySession(sessionId: Long)

	@Query("SELECT COUNT(*) FROM pending_signal WHERE session_id = :sessionId")
	suspend fun countForSession(sessionId: Long): Int

	@Query("SELECT COUNT(*) FROM pending_signal")
	suspend fun countAll(): Int

	@Query("DELETE FROM pending_signal")
	fun deleteAll()
}
