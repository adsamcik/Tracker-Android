package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.QuarantinedSignalEntity
import kotlinx.coroutines.flow.Flow

/** Read/admin access to the durable pending-signal quarantine ledger. */
@Dao
interface QuarantinedSignalDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insert(signal: QuarantinedSignalEntity): Long

	@Query(
		"""
		SELECT * FROM quarantined_signal
		ORDER BY quarantined_at DESC, id DESC
		LIMIT :limit
		""",
	)
	fun observeRecent(limit: Int = 100): Flow<List<QuarantinedSignalEntity>>

	@Query("SELECT COUNT(*) FROM quarantined_signal")
	suspend fun countAll(): Int

	/**
	 * Quarantine retains the original serialized signal, so its raw payload follows the same
	 * collection-time retention boundary as the pending WAL it replaced.
	 */
	@Query("DELETE FROM quarantined_signal WHERE acquired_at_ms < :cutoffMs")
	fun deleteAcquiredBefore(cutoffMs: Long): Int

	@Query("DELETE FROM quarantined_signal")
	fun deleteAll()
}
