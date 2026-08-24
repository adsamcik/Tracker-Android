package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState

@Dao
interface SourceEvidenceStateDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun ensure(state: SourceEvidenceState = SourceEvidenceState())

	@Query("SELECT * FROM source_evidence_state WHERE id = 1")
	suspend fun get(): SourceEvidenceState?

	@Query(
		"""
		UPDATE source_evidence_state
		SET revision = revision + 1, updated_at_ms = :updatedAtMs
		WHERE id = 1
		""",
	)
	suspend fun incrementRevision(updatedAtMs: Long): Int

	@Query(
		"""
		UPDATE source_evidence_state
		SET collected_data_epoch = :epoch,
			retained_from_ms = :retainedFromMs,
			revision = revision + 1,
			updated_at_ms = :updatedAtMs
		WHERE id = 1
		""",
	)
	suspend fun updateLifecycle(
		epoch: Long,
		retainedFromMs: Long?,
		updatedAtMs: Long,
	): Int

	@Query(
		"""
		UPDATE source_evidence_state
		SET collected_data_epoch = :epoch,
			retained_from_ms = :retainedFromMs,
			deleted_source_event_high_water_ordinal = :deletedSourceEventHighWaterOrdinal,
			revision = revision + 1,
			updated_at_ms = :updatedAtMs
		WHERE id = 1
		""",
	)
	suspend fun updateAfterFullDeletion(
		epoch: Long,
		retainedFromMs: Long?,
		deletedSourceEventHighWaterOrdinal: Long,
		updatedAtMs: Long,
	): Int
}

/**
 * Records the lifecycle and global WAL boundary of a full collected-data deletion.
 *
 * The state row survives the delete. Keeping the monotonic epoch and deleted ordinal together lets
 * a fresh projection start after SQLite's retained AUTOINCREMENT high-water instead of assuming an
 * empty WAL will restart at ordinal one.
 */
suspend fun SourceEvidenceStateDao.recordFullDeletion(
	epoch: Long,
	retainedFromMs: Long?,
	deletedSourceEventHighWaterOrdinal: Long,
	updatedAtMs: Long,
) {
	require(epoch >= 0L)
	require(retainedFromMs == null || retainedFromMs >= 0L)
	require(deletedSourceEventHighWaterOrdinal >= 0L)
	ensure()
	val current = requireNotNull(get()) { "Source-evidence state disappeared inside transaction" }
	check(
		updateAfterFullDeletion(
			epoch = maxOf(current.collectedDataEpoch, epoch),
			retainedFromMs = listOfNotNull(current.retainedFromMs, retainedFromMs).maxOrNull(),
			deletedSourceEventHighWaterOrdinal = maxOf(
				current.deletedSourceEventHighWaterOrdinal,
				deletedSourceEventHighWaterOrdinal,
			),
			updatedAtMs = updatedAtMs,
		) == 1,
	) { "Unable to record full collected-data deletion" }
}

/**
 * Mirrors the durable, non-Room lifecycle into the singleton evidence guard.
 *
 * Call this only from the same Room transaction as the evidence mutation it
 * protects. It never weakens an already stricter Room guard when an older
 * preference snapshot reaches the database after a newer transition.
 *
 * @return true when [updateLifecycle] advanced the guard and therefore already
 * incremented its revision; callers that mutate evidence when this returns
 * false must increment the revision themselves.
 */
suspend fun SourceEvidenceStateDao.synchronizeLifecycle(
	epoch: Long,
	retainedFromMs: Long?,
	updatedAtMs: Long,
): Boolean {
	ensure()
	val current = requireNotNull(get()) { "Source-evidence state disappeared inside transaction" }
	val desiredEpoch = maxOf(current.collectedDataEpoch, epoch)
	val desiredRetainedFrom = listOfNotNull(current.retainedFromMs, retainedFromMs).maxOrNull()
	if (
		desiredEpoch == current.collectedDataEpoch &&
		desiredRetainedFrom == current.retainedFromMs
	) {
		return false
	}
	check(updateLifecycle(desiredEpoch, desiredRetainedFrom, updatedAtMs) == 1) {
		"Unable to synchronize source-evidence lifecycle"
	}
	return true
}
