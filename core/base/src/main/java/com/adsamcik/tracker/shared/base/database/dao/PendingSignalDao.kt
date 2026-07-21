package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

	/**
	 * Insert pending rows without failing when a prior attempt already committed the same stable
	 * signal identity. A returned `-1` means the unique `signal_id` index resolved the insert as a
	 * duplicate; callers should use [insertOrResolveIds] rather than interpreting this result as a
	 * failed admission.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertAllIgnoringSignalIdConflicts(signals: List<PendingSignalEntity>): List<Long>

	/** Fetch rows by their immutable producer-assigned signal identities. */
	@Query("SELECT * FROM pending_signal WHERE signal_id IN (:signalIds)")
	suspend fun getBySignalIds(signalIds: List<String>): List<PendingSignalEntity>

	/**
	 * Admit each distinct logical signal exactly once, returning the authoritative durable row for
	 * every input in input order. The insert and identity resolution share one Room transaction, so
	 * a retry after an ambiguous successful commit resolves the original row instead of failing on
	 * the `signal_id` unique index.
	 *
	 * Reusing an identity for different serialized contents is a producer bug, not an idempotent
	 * retry. Detect it here rather than silently acknowledging a different logical signal. Callers
	 * must use the returned row's lifecycle metadata: a retry can observe a newer lifecycle epoch
	 * than the row that actually committed.
	 */
	@Transaction
	suspend fun insertOrResolveEntities(signals: List<PendingSignalEntity>): List<PendingSignalEntity> {
		if (signals.isEmpty()) return emptyList()

		val signalIds = signals.map(PendingSignalEntity::signalId)
		require(signalIds.all(String::isNotBlank)) {
			"Pending signal admission requires a non-blank signalId"
		}
		require(signalIds.toSet().size == signalIds.size) {
			"A pending-signal admission batch cannot contain duplicate signalIds"
		}

		insertAllIgnoringSignalIdConflicts(signals)
		val resolvedRows = mutableListOf<PendingSignalEntity>()
		for (signalIdChunk in signalIds.chunked(MAX_SIGNAL_IDS_PER_LOOKUP)) {
			resolvedRows += getBySignalIds(signalIdChunk)
		}
		val resolvedBySignalId = resolvedRows.associateBy(PendingSignalEntity::signalId)

		return signals.map { candidate ->
			val resolved = requireNotNull(resolvedBySignalId[candidate.signalId]) {
				"Unable to resolve admitted pending signal ${candidate.signalId}"
			}
			check(
				resolved.envelopeVersion == candidate.envelopeVersion &&
					resolved.payloadChecksum == candidate.payloadChecksum &&
					resolved.signalJson == candidate.signalJson,
			) {
				"Pending signal ${candidate.signalId} already exists with different serialized contents"
			}
			resolved
		}
	}

	/**
	 * Compatibility helper for callers that only need generated IDs. New admission code that tracks
	 * lifecycle state should use [insertOrResolveEntities] so retries retain the original row's
	 * immutable metadata.
	 */
	suspend fun insertOrResolveIds(signals: List<PendingSignalEntity>): List<Long> =
		insertOrResolveEntities(signals).map(PendingSignalEntity::id)

	@Query(
		"""
		SELECT * FROM pending_signal
		WHERE session_id = :sessionId
		ORDER BY id ASC
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
	 * generated row id follows durable producer admission order and cannot be
	 * re-ordered by wall-clock regression.
	 */
	@Query(
		"""
		SELECT * FROM pending_signal
		ORDER BY id ASC
		LIMIT :limit
		""",
	)
	suspend fun getOldestAcrossSessions(limit: Int = 100): List<PendingSignalEntity>

	@Query("DELETE FROM pending_signal WHERE id IN (:ids)")
	suspend fun deleteByIds(ids: List<Long>)

	@Query("SELECT COUNT(*) FROM pending_signal WHERE id IN (:ids)")
	suspend fun countByIds(ids: List<Long>): Int

	@Query("DELETE FROM pending_signal WHERE session_id = :sessionId")
	suspend fun deleteBySession(sessionId: Long)

	@Query("SELECT COUNT(*) FROM pending_signal WHERE session_id = :sessionId")
	suspend fun countForSession(sessionId: Long): Int

	@Query("SELECT COUNT(*) FROM pending_signal")
	suspend fun countAll(): Int

	@Query("SELECT EXISTS(SELECT 1 FROM pending_signal LIMIT 1)")
	suspend fun hasAny(): Boolean

	@Query("DELETE FROM pending_signal")
	fun deleteAll()

	companion object {
		// Leave headroom below SQLite's guaranteed 999 bind-variable limit.
		private const val MAX_SIGNAL_IDS_PER_LOOKUP = 900
	}
}
