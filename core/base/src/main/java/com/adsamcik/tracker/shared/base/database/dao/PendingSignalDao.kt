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

	/**
	 * Insert the given pending-signal rows and return their generated row IDs
	 * in the same order. The IDs let the durable buffer acknowledge (delete)
	 * exactly the rows it persisted, in the same transaction as the destination
	 * writes.
	 */
	@Insert
	suspend fun insertAll(signals: List<PendingSignalEntity>): List<Long>

	@Query(
		"""
		SELECT * FROM pending_signal
		WHERE session_id = :sessionId
		ORDER BY created_at ASC, id ASC
		LIMIT :limit
		""",
	)
	suspend fun getOldest(sessionId: Long, limit: Int = 100): List<PendingSignalEntity>

	/**
	 * Oldest pending rows across **all** sessions.
	 *
	 * Recovery must not filter by the restarted session's id: a fresh process
	 * mints a brand-new session id (Room autoincrement), so WAL rows written
	 * under a previous session would otherwise be orphaned forever. Ordering by
	 * `created_at, id` keeps the original chronological replay order regardless
	 * of which session produced each row.
	 */
	@Query(
		"""
		SELECT * FROM pending_signal
		ORDER BY created_at ASC, id ASC
		LIMIT :limit
		""",
	)
	suspend fun getOldestAcrossSessions(limit: Int = 100): List<PendingSignalEntity>

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
