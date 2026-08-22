package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionDrainEntity
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionTargetEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity

@Dao
interface LegacyV27ProjectionDrainDao {
	/** Fixture/import insert. Runtime ownership changes must use the guarded CAS methods below. */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun saveDrain(entity: LegacyV27ProjectionDrainEntity)

	/** Fixture/import insert. Runtime cursor changes must use [advanceTarget]. */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun saveTarget(entity: LegacyV27ProjectionTargetEntity)

	@Query("SELECT * FROM legacy_v27_projection_drain WHERE id = 1")
	suspend fun get(): LegacyV27ProjectionDrainEntity?

	@Query(
		"SELECT * FROM legacy_v27_projection_target " +
			"ORDER BY projection_id, projection_version",
	)
	suspend fun targets(): List<LegacyV27ProjectionTargetEntity>

	@Query(
		"SELECT * FROM legacy_v27_projection_target " +
			"WHERE projection_id = :projectionId AND projection_version = :projectionVersion",
	)
	suspend fun target(
		projectionId: String,
		projectionVersion: Int,
	): LegacyV27ProjectionTargetEntity?

	/**
	 * Acquires or idempotently re-enters the singleton drain lease.
	 *
	 * Wall time is display metadata only. Lease authority is the boot identity plus elapsed time.
	 * A new boot can take the lease immediately because elapsed readings from different boots are
	 * incomparable. Every mutation also checks the Room deletion epoch captured by migration.
	 */
	@Query(
		"UPDATE legacy_v27_projection_drain SET " +
			"status = 'RUNNING', owner_boot_id = :bootId, owner_token = :ownerToken, " +
			"lease_generation = CASE WHEN status = 'RUNNING' " +
			"AND owner_boot_id = :bootId AND owner_token = :ownerToken " +
			"AND lease_expires_elapsed_nanos > :nowElapsedNanos " +
			"THEN lease_generation ELSE lease_generation + 1 END, " +
			"lease_expires_elapsed_nanos = :leaseExpiresElapsedNanos, " +
			"started_at_ms = COALESCE(started_at_ms, :startedAtMs), failure_code = NULL " +
			"WHERE id = 1 AND status IN ('PENDING', 'RUNNING', 'FAILED_RETRYABLE') " +
			"AND :leaseExpiresElapsedNanos > :nowElapsedNanos " +
			"AND collected_data_epoch = (SELECT collected_data_epoch " +
			"FROM source_evidence_state WHERE id = 1) AND (" +
			"status != 'RUNNING' OR owner_boot_id IS NULL OR owner_token IS NULL OR " +
			"owner_boot_id != :bootId OR lease_expires_elapsed_nanos IS NULL OR " +
			"lease_expires_elapsed_nanos <= :nowElapsedNanos OR " +
			"(owner_boot_id = :bootId AND owner_token = :ownerToken))",
	)
	suspend fun acquireLease(
		bootId: String,
		ownerToken: String,
		nowElapsedNanos: Long,
		leaseExpiresElapsedNanos: Long,
		startedAtMs: Long,
	): Int

	@Query(
		"UPDATE legacy_v27_projection_drain SET " +
			"lease_expires_elapsed_nanos = :leaseExpiresElapsedNanos " +
			"WHERE id = 1 AND status = 'RUNNING' " +
			"AND owner_boot_id = :bootId AND owner_token = :ownerToken " +
			"AND lease_generation = :leaseGeneration " +
			"AND lease_expires_elapsed_nanos > :nowElapsedNanos " +
			"AND :leaseExpiresElapsedNanos > lease_expires_elapsed_nanos " +
			"AND collected_data_epoch = (SELECT collected_data_epoch " +
			"FROM source_evidence_state WHERE id = 1)",
	)
	suspend fun renewLease(
		bootId: String,
		ownerToken: String,
		leaseGeneration: Long,
		nowElapsedNanos: Long,
		leaseExpiresElapsedNanos: Long,
	): Int

	@Query(
		"UPDATE legacy_v27_projection_drain SET status = 'FAILED_RETRYABLE', " +
			"owner_boot_id = NULL, owner_token = NULL, lease_expires_elapsed_nanos = NULL, " +
			"failure_code = :failureCode WHERE id = 1 AND status = 'RUNNING' " +
			"AND owner_boot_id = :bootId AND owner_token = :ownerToken " +
			"AND lease_generation = :leaseGeneration " +
			"AND lease_expires_elapsed_nanos > :nowElapsedNanos " +
			"AND collected_data_epoch = (SELECT collected_data_epoch " +
			"FROM source_evidence_state WHERE id = 1)",
	)
	suspend fun markRetryableFailure(
		bootId: String,
		ownerToken: String,
		leaseGeneration: Long,
		nowElapsedNanos: Long,
		failureCode: String,
	): Int

	/** Persistently closes startup when the released database contains an unknown contract. */
	@Query(
		"UPDATE legacy_v27_projection_drain SET status = 'BLOCKED_UNSUPPORTED_TARGET', " +
			"owner_boot_id = NULL, owner_token = NULL, lease_expires_elapsed_nanos = NULL, " +
			"failure_code = :failureCode WHERE id = 1 AND status = 'RUNNING' " +
			"AND owner_boot_id = :bootId AND owner_token = :ownerToken " +
			"AND lease_generation = :leaseGeneration " +
			"AND lease_expires_elapsed_nanos > :nowElapsedNanos " +
			"AND collected_data_epoch = (SELECT collected_data_epoch " +
			"FROM source_evidence_state WHERE id = 1)",
	)
	suspend fun blockUnsupportedTarget(
		failureCode: String,
		bootId: String,
		ownerToken: String,
		leaseGeneration: Long,
		nowElapsedNanos: Long,
	): Int

	/** Advances one target cursor without assuming that WAL admission ordinals are dense. */
	@Query(
		"UPDATE legacy_v27_projection_target SET last_completed_ordinal = :newCompletedOrdinal " +
			"WHERE projection_id = :projectionId AND projection_version = :projectionVersion " +
			"AND disposition = 'PENDING' AND last_completed_ordinal = :expectedCompletedOrdinal " +
			"AND :newCompletedOrdinal >= :expectedCompletedOrdinal " +
			"AND :newCompletedOrdinal <= required_through_ordinal AND EXISTS (" +
			"SELECT 1 FROM legacy_v27_projection_drain AS drain " +
			"JOIN source_evidence_state AS evidence ON evidence.id = 1 " +
			"AND evidence.collected_data_epoch = drain.collected_data_epoch " +
			"WHERE drain.id = 1 AND drain.status = 'RUNNING' " +
			"AND drain.owner_boot_id = :bootId AND drain.owner_token = :ownerToken " +
			"AND drain.lease_generation = :leaseGeneration " +
			"AND drain.lease_expires_elapsed_nanos > :nowElapsedNanos)",
	)
	suspend fun advanceTarget(
		projectionId: String,
		projectionVersion: Int,
		expectedCompletedOrdinal: Long,
		newCompletedOrdinal: Long,
		bootId: String,
		ownerToken: String,
		leaseGeneration: Long,
		nowElapsedNanos: Long,
	): Int

	/** Records the first lossy-recovery reason without terminalizing or moving the cursor. */
	@Query(
		"UPDATE legacy_v27_projection_target SET failure_code = COALESCE(failure_code, :failureCode) " +
			"WHERE projection_id = :projectionId AND projection_version = :projectionVersion " +
			"AND disposition = 'PENDING' AND EXISTS (" +
			"SELECT 1 FROM legacy_v27_projection_drain AS drain " +
			"JOIN source_evidence_state AS evidence ON evidence.id = 1 " +
			"AND evidence.collected_data_epoch = drain.collected_data_epoch " +
			"WHERE drain.id = 1 AND drain.status = 'RUNNING' " +
			"AND drain.owner_boot_id = :bootId AND drain.owner_token = :ownerToken " +
			"AND drain.lease_generation = :leaseGeneration " +
			"AND drain.lease_expires_elapsed_nanos > :nowElapsedNanos)",
	)
	suspend fun markTargetPartial(
		projectionId: String,
		projectionVersion: Int,
		failureCode: String,
		bootId: String,
		ownerToken: String,
		leaseGeneration: Long,
		nowElapsedNanos: Long,
	): Int

	@Query(
		"UPDATE legacy_v27_projection_target SET disposition = :disposition, " +
			"completed_at_ms = :completedAtMs, " +
			"failure_code = COALESCE(failure_code, :failureCode) " +
			"WHERE projection_id = :projectionId AND projection_version = :projectionVersion " +
			"AND disposition = 'PENDING' AND last_completed_ordinal = :expectedCompletedOrdinal " +
			"AND last_completed_ordinal = required_through_ordinal " +
			"AND :disposition != 'PENDING' AND EXISTS (" +
			"SELECT 1 FROM legacy_v27_projection_drain AS drain " +
			"JOIN source_evidence_state AS evidence ON evidence.id = 1 " +
			"AND evidence.collected_data_epoch = drain.collected_data_epoch " +
			"WHERE drain.id = 1 AND drain.status = 'RUNNING' " +
			"AND drain.owner_boot_id = :bootId AND drain.owner_token = :ownerToken " +
			"AND drain.lease_generation = :leaseGeneration " +
			"AND drain.lease_expires_elapsed_nanos > :nowElapsedNanos)",
	)
	suspend fun terminalizeTarget(
		projectionId: String,
		projectionVersion: Int,
		expectedCompletedOrdinal: Long,
		disposition: String,
		completedAtMs: Long,
		failureCode: String?,
		bootId: String,
		ownerToken: String,
		leaseGeneration: Long,
		nowElapsedNanos: Long,
	): Int

	/** Finds outbox-only generations that were not represented by a v27 registration row. */
	@Query(
		"SELECT DISTINCT outbox.projection_id, outbox.projection_version " +
			"FROM source_projection_outbox AS outbox " +
			"JOIN legacy_v27_projection_drain AS drain ON drain.id = 1 " +
			"LEFT JOIN legacy_v27_projection_target AS target " +
			"ON target.projection_id = outbox.projection_id " +
			"AND target.projection_version = outbox.projection_version " +
			"WHERE outbox.admission_ordinal <= drain.cutoff_admission_ordinal " +
			"AND outbox.delivered_at_ms IS NULL AND outbox.terminal_disposition IS NULL " +
			"AND target.projection_id IS NULL ORDER BY outbox.projection_id, outbox.projection_version",
	)
	suspend fun unknownPendingOutboxGenerations(): List<LegacyV27OutboxGenerationRow>

	@Query(
		"SELECT COUNT(*) FROM source_projection_outbox AS outbox " +
			"JOIN legacy_v27_projection_drain AS drain ON drain.id = 1 " +
			"WHERE outbox.projection_id = :projectionId " +
			"AND outbox.projection_version = :projectionVersion " +
			"AND outbox.admission_ordinal <= drain.cutoff_admission_ordinal " +
			"AND outbox.delivered_at_ms IS NULL AND outbox.terminal_disposition IS NULL",
	)
	suspend fun pendingOutboxCount(
		projectionId: String,
		projectionVersion: Int,
	): Long

	/** Bounded exact generation read used when a retained outbox is the only recovery source. */
	@Query(
		"SELECT outbox.* FROM source_projection_outbox AS outbox " +
			"JOIN legacy_v27_projection_drain AS drain ON drain.id = 1 " +
			"WHERE outbox.projection_id = :projectionId " +
			"AND outbox.projection_version = :projectionVersion " +
			"AND outbox.admission_ordinal <= drain.cutoff_admission_ordinal " +
			"AND outbox.delivered_at_ms IS NULL AND outbox.terminal_disposition IS NULL " +
			"ORDER BY outbox.admission_ordinal, outbox.stable_id LIMIT :limit",
	)
	suspend fun pendingOutbox(
		projectionId: String,
		projectionVersion: Int,
		limit: Int,
	): List<SourceProjectionOutboxEntity>

	@Query(
		"UPDATE source_projection_outbox SET terminal_disposition = :disposition, " +
			"terminal_at_ms = :terminalAtMs WHERE stable_id = :stableId " +
			"AND admission_ordinal <= (SELECT cutoff_admission_ordinal " +
			"FROM legacy_v27_projection_drain WHERE id = 1) " +
			"AND delivered_at_ms IS NULL AND terminal_disposition IS NULL AND EXISTS (" +
			"SELECT 1 FROM legacy_v27_projection_drain AS drain " +
			"JOIN source_evidence_state AS evidence ON evidence.id = 1 " +
			"AND evidence.collected_data_epoch = drain.collected_data_epoch " +
			"WHERE drain.id = 1 AND drain.status = 'RUNNING' " +
			"AND drain.owner_boot_id = :bootId AND drain.owner_token = :ownerToken " +
			"AND drain.lease_generation = :leaseGeneration " +
			"AND drain.lease_expires_elapsed_nanos > :nowElapsedNanos)",
	)
	suspend fun terminalizePendingOutbox(
		stableId: String,
		disposition: String,
		terminalAtMs: Long,
		bootId: String,
		ownerToken: String,
		leaseGeneration: Long,
		nowElapsedNanos: Long,
	): Int

	@Query(
		"UPDATE source_projection_outbox SET terminal_disposition = :disposition, " +
			"terminal_at_ms = :terminalAtMs WHERE projection_id = :projectionId " +
			"AND projection_version = :projectionVersion AND admission_ordinal <= (" +
			"SELECT cutoff_admission_ordinal FROM legacy_v27_projection_drain WHERE id = 1) " +
			"AND delivered_at_ms IS NULL AND terminal_disposition IS NULL AND EXISTS (" +
			"SELECT 1 FROM legacy_v27_projection_drain AS drain " +
			"JOIN source_evidence_state AS evidence ON evidence.id = 1 " +
			"AND evidence.collected_data_epoch = drain.collected_data_epoch " +
			"WHERE drain.id = 1 AND drain.status = 'RUNNING' " +
			"AND drain.owner_boot_id = :bootId AND drain.owner_token = :ownerToken " +
			"AND drain.lease_generation = :leaseGeneration " +
			"AND drain.lease_expires_elapsed_nanos > :nowElapsedNanos)",
	)
	suspend fun terminalizePendingOutbox(
		projectionId: String,
		projectionVersion: Int,
		disposition: String,
		terminalAtMs: Long,
		bootId: String,
		ownerToken: String,
		leaseGeneration: Long,
		nowElapsedNanos: Long,
	): Int

	@Query(
		"SELECT COUNT(*) FROM source_projection_outbox AS outbox " +
			"JOIN legacy_v27_projection_drain AS drain ON drain.id = 1 " +
			"WHERE outbox.admission_ordinal <= drain.cutoff_admission_ordinal " +
			"AND outbox.delivered_at_ms IS NULL " +
			"AND outbox.terminal_disposition IN (:dispositions)",
	)
	suspend fun terminalizedOutboxCount(dispositions: List<String>): Long

	/** Stores a recomputed count, so a crash between terminalization and accounting is harmless. */
	@Query(
		"UPDATE legacy_v27_projection_drain SET suppressed_outbox_count = :suppressedCount " +
			"WHERE id = 1 AND status = 'RUNNING' " +
			"AND owner_boot_id = :bootId AND owner_token = :ownerToken " +
			"AND lease_generation = :leaseGeneration " +
			"AND lease_expires_elapsed_nanos > :nowElapsedNanos " +
			"AND collected_data_epoch = (SELECT collected_data_epoch " +
			"FROM source_evidence_state WHERE id = 1)",
	)
	suspend fun setSuppressedOutboxCount(
		suppressedCount: Long,
		bootId: String,
		ownerToken: String,
		leaseGeneration: Long,
		nowElapsedNanos: Long,
	): Int

	@Query(
		"DELETE FROM source_projection_join_state WHERE projection_id = :projectionId " +
			"AND projection_version = 1 AND EXISTS (" + OWNED_DRAIN_EXISTS + ")",
	)
	suspend fun deleteLegacyV1JoinState(
		projectionId: String,
		bootId: String,
		ownerToken: String,
		leaseGeneration: Long,
		nowElapsedNanos: Long,
	): Int

	@Query(
		"DELETE FROM source_projection_failure WHERE projection_id = :projectionId " +
			"AND projection_version = 1 AND EXISTS (" + OWNED_DRAIN_EXISTS + ")",
	)
	suspend fun deleteLegacyV1Failures(
		projectionId: String,
		bootId: String,
		ownerToken: String,
		leaseGeneration: Long,
		nowElapsedNanos: Long,
	): Int

	@Query(
		"DELETE FROM source_projection_checkpoint WHERE projection_id = :projectionId " +
			"AND projection_version = 1 AND EXISTS (" + OWNED_DRAIN_EXISTS + ")",
	)
	suspend fun deleteLegacyV1Checkpoint(
		projectionId: String,
		bootId: String,
		ownerToken: String,
		leaseGeneration: Long,
		nowElapsedNanos: Long,
	): Int

	@Query(
		"UPDATE source_projection_registration SET status = 'RETIRED' " +
			"WHERE projection_id = :projectionId AND projection_version = 1 " +
			"AND status = 'LEGACY_V27_PENDING' AND EXISTS (" + OWNED_DRAIN_EXISTS + ")",
	)
	suspend fun retireLegacyV1Registration(
		projectionId: String,
		bootId: String,
		ownerToken: String,
		leaseGeneration: Long,
		nowElapsedNanos: Long,
	): Int

	@Query(
		"UPDATE legacy_v27_projection_drain SET status = :status, completed_at_ms = :completedAtMs, " +
			"owner_boot_id = NULL, owner_token = NULL, lease_expires_elapsed_nanos = NULL, " +
			"failure_code = :failureCode WHERE id = 1 AND status = 'RUNNING' " +
			"AND :status IN ('COMPLETE', 'COMPLETE_PARTIAL') " +
			"AND owner_boot_id = :bootId AND owner_token = :ownerToken " +
			"AND lease_generation = :leaseGeneration " +
			"AND lease_expires_elapsed_nanos > :nowElapsedNanos " +
			"AND collected_data_epoch = (SELECT collected_data_epoch " +
			"FROM source_evidence_state WHERE id = 1) " +
			"AND NOT EXISTS (SELECT 1 FROM legacy_v27_projection_target " +
			"WHERE disposition = 'PENDING') " +
			"AND NOT EXISTS (SELECT 1 FROM source_projection_outbox AS outbox " +
			"WHERE outbox.admission_ordinal <= cutoff_admission_ordinal " +
			"AND outbox.delivered_at_ms IS NULL AND outbox.terminal_disposition IS NULL)",
	)
	suspend fun completeDrain(
		status: String,
		completedAtMs: Long,
		failureCode: String?,
		bootId: String,
		ownerToken: String,
		leaseGeneration: Long,
		nowElapsedNanos: Long,
	): Int

	@Query(
		"SELECT MIN(last_completed_ordinal + 1) FROM legacy_v27_projection_target " +
			"WHERE disposition = 'PENDING' AND last_completed_ordinal < required_through_ordinal",
	)
	suspend fun minimumPendingOrdinal(): Long?

	@Query(
		"SELECT MIN(outbox.admission_ordinal) FROM source_projection_outbox AS outbox " +
			"JOIN legacy_v27_projection_drain AS drain ON drain.id = 1 " +
			"WHERE outbox.admission_ordinal <= drain.cutoff_admission_ordinal " +
			"AND outbox.delivered_at_ms IS NULL AND outbox.terminal_disposition IS NULL",
	)
	suspend fun minimumPendingOutboxOrdinal(): Long?

	@Query(
		"SELECT MIN(wal.admission_ordinal) FROM source_event_wal AS wal " +
			"JOIN legacy_v27_projection_drain AS drain ON drain.id = 1 " +
			"WHERE wal.admission_ordinal <= drain.cutoff_admission_ordinal " +
			"AND drain.status IN ('BLOCKED_UNSUPPORTED_TARGET', 'FAILED_RETRYABLE')",
	)
	suspend fun minimumBlockedWalOrdinal(): Long?

	@Query(
		"SELECT CASE WHEN cutoff_admission_ordinal > 0 THEN cutoff_admission_ordinal + 1 ELSE 1 END " +
			"FROM legacy_v27_projection_drain WHERE id = 1",
	)
	suspend fun liveActivationOrdinal(): Long?

	@Query("DELETE FROM legacy_v27_projection_target")
	fun deleteAllTargets()

	@Query("DELETE FROM legacy_v27_projection_drain")
	fun deleteDrain()

	companion object {
		private const val OWNED_DRAIN_EXISTS =
			"SELECT 1 FROM legacy_v27_projection_drain AS drain " +
				"JOIN source_evidence_state AS evidence ON evidence.id = 1 " +
				"AND evidence.collected_data_epoch = drain.collected_data_epoch " +
				"WHERE drain.id = 1 AND drain.status = 'RUNNING' " +
				"AND drain.owner_boot_id = :bootId AND drain.owner_token = :ownerToken " +
				"AND drain.lease_generation = :leaseGeneration " +
				"AND drain.lease_expires_elapsed_nanos > :nowElapsedNanos"
	}
}

data class LegacyV27OutboxGenerationRow(
	@ColumnInfo(name = "projection_id") val projectionId: String,
	@ColumnInfo(name = "projection_version") val projectionVersion: Int,
)
