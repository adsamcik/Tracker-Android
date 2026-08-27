package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.SourceCoordinatorLeaseEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionCheckpointEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionJoinStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionRegistrationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity

@Dao
interface SourceProjectionStateDao {
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun installProductLane(entity: SourceProductProjectionLaneEntity)

	@Query(
		"SELECT * FROM source_product_projection_lane " +
			"WHERE source_kind = :sourceKind AND status = 'ACTIVE'",
	)
	suspend fun activeProductLane(sourceKind: Int): SourceProductProjectionLaneEntity?

	@Query(
		"SELECT * FROM source_product_projection_lane WHERE source_kind = :sourceKind " +
			"ORDER BY binding_generation DESC LIMIT 1",
	)
	suspend fun latestProductLane(sourceKind: Int): SourceProductProjectionLaneEntity?

	/** Exact historical lane lookup; retired generations remain valid materialization evidence. */
	@Query(
		"SELECT * FROM source_product_projection_lane WHERE source_kind = :sourceKind " +
			"AND binding_generation = :bindingGeneration " +
			"AND projection_id = :projectionId AND projection_version = :projectionVersion",
	)
	suspend fun productLane(
		sourceKind: Int,
		bindingGeneration: Long,
		projectionId: String,
		projectionVersion: Int,
	): SourceProductProjectionLaneEntity?

	@Query(
		"SELECT lane.* FROM source_product_projection_lane lane WHERE lane.status = 'ACTIVE' " +
			"AND NOT EXISTS (SELECT 1 FROM source_projection_registration registration " +
			"WHERE registration.projection_id = lane.projection_id " +
			"AND registration.projection_version = lane.projection_version)",
	)
	suspend fun activeProductLanes(): List<SourceProductProjectionLaneEntity>

	/** Includes malformed/conflicting active rows so containment can retire their retention pins. */
	@Query("SELECT * FROM source_product_projection_lane WHERE status = 'ACTIVE'")
	suspend fun allActiveProductLanes(): List<SourceProductProjectionLaneEntity>

	/** Fail-closed provider check for callers that cannot depend on the tracker rollout model. */
	@Query(
		"SELECT EXISTS(SELECT 1 FROM source_product_projection_lane " +
			"WHERE source_kind = :sourceKind AND projection_id != '' " +
			"AND binding_generation > 0 AND projection_version > 0 " +
			"AND capture_mode_mask > 0 " +
			"AND product_stage IN ('EVENT_SHADOW', 'EVENT_CANONICAL') " +
			"AND product_stage = :productStage " +
			"AND activated_rollout_revision > 0 " +
			"AND activated_rollout_revision <= :rolloutRevision " +
			"AND activation_ordinal > 0 " +
			"AND contiguous_admission_ordinal >= activation_ordinal - 1 " +
			"AND capture_admission_cutoff_ordinal IS NULL " +
			"AND retention_required = 1 AND status = 'ACTIVE' " +
			"AND terminal_disposition IS NULL AND terminal_at_ms IS NULL " +
			"AND NOT EXISTS (SELECT 1 FROM source_projection_registration registration " +
			"WHERE registration.projection_id = source_product_projection_lane.projection_id " +
			"AND registration.projection_version = " +
			"source_product_projection_lane.projection_version) " +
			"AND (SELECT COUNT(*) FROM source_product_projection_lane active_lane " +
			"WHERE active_lane.source_kind = :sourceKind " +
			"AND active_lane.status = 'ACTIVE') = 1)",
	)
	suspend fun isProductLaneReachable(
		sourceKind: Int,
		productStage: String,
		rolloutRevision: Long,
	): Boolean

	/** Admission-time fence paired transactionally with source-local lane containment. */
	@Query(
		"SELECT EXISTS(SELECT 1 FROM source_product_projection_lane " +
			"WHERE source_kind = :sourceKind AND projection_id != '' " +
			"AND binding_generation > 0 AND projection_version > 0 " +
			"AND capture_mode_mask > 0 " +
			"AND product_stage IN ('EVENT_SHADOW', 'EVENT_CANONICAL') " +
			"AND activated_rollout_revision > 0 AND activation_ordinal > 0 " +
			"AND contiguous_admission_ordinal >= activation_ordinal - 1 " +
			"AND capture_admission_cutoff_ordinal IS NULL " +
			"AND retention_required = 1 AND status = 'ACTIVE' " +
			"AND terminal_disposition IS NULL AND terminal_at_ms IS NULL " +
			"AND NOT EXISTS (SELECT 1 FROM source_projection_registration registration " +
			"WHERE registration.projection_id = source_product_projection_lane.projection_id " +
			"AND registration.projection_version = " +
			"source_product_projection_lane.projection_version) " +
			"AND (SELECT COUNT(*) FROM source_product_projection_lane active_lane " +
			"WHERE active_lane.source_kind = :sourceKind " +
			"AND active_lane.status = 'ACTIVE') = 1)",
	)
	suspend fun isCaptureAdmissionOpen(sourceKind: Int): Boolean

	@Query(
		"SELECT * FROM source_product_projection_lane " +
			"WHERE projection_id = :projectionId AND projection_version = :projectionVersion " +
			"ORDER BY source_kind, binding_generation",
	)
	suspend fun productLanesByProjection(
		projectionId: String,
		projectionVersion: Int,
	): List<SourceProductProjectionLaneEntity>

	/**
	 * Moves exactly one source-local cursor monotonically under its installed writer identity.
	 * A future source materializer must call this in the same transaction as its destination write.
	 */
	@Query(
		"UPDATE source_product_projection_lane SET " +
			"contiguous_admission_ordinal = :throughOrdinal, updated_at_ms = :updatedAtMs " +
			"WHERE source_kind = :sourceKind AND projection_id = :projectionId " +
			"AND projection_version = :projectionVersion " +
			"AND binding_generation = :bindingGeneration AND status = 'ACTIVE' " +
			"AND :throughOrdinal >= activation_ordinal - 1 " +
			"AND (capture_admission_cutoff_ordinal IS NULL " +
			"OR :throughOrdinal <= capture_admission_cutoff_ordinal) " +
			"AND contiguous_admission_ordinal = :expectedCurrentOrdinal " +
			"AND contiguous_admission_ordinal < :throughOrdinal",
	)
	suspend fun advanceProductLaneCursor(
		sourceKind: Int,
		bindingGeneration: Long,
		projectionId: String,
		projectionVersion: Int,
		expectedCurrentOrdinal: Long,
		throughOrdinal: Long,
		updatedAtMs: Long,
	): Int

	/**
	 * Moves one source-local cursor only while every installed binding field still matches the
	 * projection transaction that produced the destination effect. This is intentionally stricter
	 * than [advanceProductLaneCursor]: a source writer must not commit under a concurrently replaced
	 * stage, capture-mode binding, activation boundary, or retirement cutoff.
	 */
	@Query(
		"UPDATE source_product_projection_lane SET " +
			"contiguous_admission_ordinal = :throughOrdinal, updated_at_ms = :updatedAtMs " +
			"WHERE source_kind = :sourceKind AND binding_generation = :bindingGeneration " +
			"AND projection_id = :projectionId AND projection_version = :projectionVersion " +
			"AND capture_mode_mask = :captureModeMask AND product_stage = :productStage " +
			"AND activated_rollout_revision = :activatedRolloutRevision " +
			"AND activation_ordinal = :activationOrdinal " +
			"AND ((capture_admission_cutoff_ordinal IS NULL AND :expectedCutoffOrdinal IS NULL) " +
			"OR capture_admission_cutoff_ordinal = :expectedCutoffOrdinal) " +
			"AND retention_required = 1 AND status = 'ACTIVE' " +
			"AND terminal_disposition IS NULL AND terminal_at_ms IS NULL " +
			"AND contiguous_admission_ordinal = :expectedCurrentOrdinal " +
			"AND :throughOrdinal >= activation_ordinal - 1 " +
			"AND (capture_admission_cutoff_ordinal IS NULL " +
			"OR :throughOrdinal <= capture_admission_cutoff_ordinal) " +
			"AND contiguous_admission_ordinal < :throughOrdinal",
	)
	suspend fun advanceExactProductLaneCursor(
		sourceKind: Int,
		bindingGeneration: Long,
		projectionId: String,
		projectionVersion: Int,
		captureModeMask: Long,
		productStage: String,
		activatedRolloutRevision: Long,
		activationOrdinal: Long,
		expectedCutoffOrdinal: Long?,
		expectedCurrentOrdinal: Long,
		throughOrdinal: Long,
		updatedAtMs: Long,
	): Int

	@Query(
		"UPDATE source_product_projection_lane SET status = 'RETIRED', " +
			"retention_required = 0, updated_at_ms = :updatedAtMs " +
		"WHERE source_kind = :sourceKind AND binding_generation = :bindingGeneration " +
			"AND projection_id = :projectionId AND projection_version = :projectionVersion " +
			"AND contiguous_admission_ordinal = :expectedCurrentOrdinal " +
			"AND status = 'ACTIVE'",
	)
	suspend fun retireProductLane(
		sourceKind: Int,
		bindingGeneration: Long,
		projectionId: String,
		projectionVersion: Int,
		expectedCurrentOrdinal: Long,
		updatedAtMs: Long,
	): Int

	@Query(
		"UPDATE source_product_projection_lane SET " +
			"capture_admission_cutoff_ordinal = :cutoffOrdinal, updated_at_ms = :updatedAtMs " +
			"WHERE source_kind = :sourceKind AND binding_generation = :bindingGeneration " +
			"AND projection_id = :projectionId AND projection_version = :projectionVersion " +
			"AND status = 'ACTIVE' AND retention_required = 1 " +
			"AND capture_admission_cutoff_ordinal IS NULL",
	)
	suspend fun fenceProductLaneCaptureAdmission(
		sourceKind: Int,
		bindingGeneration: Long,
		projectionId: String,
		projectionVersion: Int,
		cutoffOrdinal: Long,
		updatedAtMs: Long,
	): Int

	@Query(
		"UPDATE source_product_projection_lane SET status = 'RETIRED', " +
			"retention_required = 0, terminal_disposition = :disposition, " +
			"terminal_at_ms = :terminalAtMs, updated_at_ms = :terminalAtMs " +
			"WHERE source_kind = :sourceKind AND binding_generation = :bindingGeneration " +
			"AND projection_id = :projectionId AND projection_version = :projectionVersion " +
			"AND contiguous_admission_ordinal = :expectedCurrentOrdinal " +
			"AND capture_admission_cutoff_ordinal = :expectedCutoffOrdinal " +
			"AND status = 'ACTIVE'",
	)
	suspend fun retireFencedProductLane(
		sourceKind: Int,
		bindingGeneration: Long,
		projectionId: String,
		projectionVersion: Int,
		expectedCurrentOrdinal: Long,
		expectedCutoffOrdinal: Long,
		disposition: String,
		terminalAtMs: Long,
	): Int

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun register(entity: SourceProjectionRegistrationEntity)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun saveCheckpoint(entity: SourceProjectionCheckpointEntity)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun saveFailure(entity: SourceProjectionFailureEntity)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun saveJoinState(entity: SourceProjectionJoinStateEntity)

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertOutbox(entity: SourceProjectionOutboxEntity): Long

	@Query(
		"SELECT * FROM source_projection_registration " +
			"WHERE projection_id = :projectionId AND projection_version = :projectionVersion",
	)
	suspend fun registration(projectionId: String, projectionVersion: Int): SourceProjectionRegistrationEntity?

	@Query(
		"SELECT * FROM source_projection_checkpoint " +
			"WHERE projection_id = :projectionId AND projection_version = :projectionVersion",
	)
	suspend fun checkpoint(projectionId: String, projectionVersion: Int): SourceProjectionCheckpointEntity?

	@Query(
		"SELECT * FROM source_projection_failure WHERE projection_id = :projectionId " +
			"AND projection_version = :projectionVersion AND admission_ordinal = :admissionOrdinal",
	)
	suspend fun failure(
		projectionId: String,
		projectionVersion: Int,
		admissionOrdinal: Long,
	): SourceProjectionFailureEntity?

	@Query(
		"SELECT * FROM source_projection_failure WHERE projection_id = :projectionId " +
			"AND projection_version = :projectionVersion AND terminal = 1 " +
			"AND admission_ordinal > :afterOrdinal AND admission_ordinal <= :throughOrdinal " +
			"ORDER BY admission_ordinal ASC LIMIT 1",
	)
	suspend fun firstTerminalFailureAfterThrough(
		projectionId: String,
		projectionVersion: Int,
		afterOrdinal: Long,
		throughOrdinal: Long,
	): SourceProjectionFailureEntity?

	@Query(
		"DELETE FROM source_projection_failure WHERE projection_id = :projectionId " +
			"AND projection_version = :projectionVersion AND admission_ordinal = :admissionOrdinal",
	)
	suspend fun deleteFailure(projectionId: String, projectionVersion: Int, admissionOrdinal: Long): Int

	@Query(
		"SELECT * FROM source_projection_join_state " +
			"WHERE projection_id = :projectionId AND projection_version = :projectionVersion",
	)
	suspend fun joinStates(projectionId: String, projectionVersion: Int): List<SourceProjectionJoinStateEntity>

	@Query(
		"SELECT * FROM source_projection_join_state " +
			"WHERE projection_id = :projectionId AND projection_version = :projectionVersion " +
			"AND state_key = :stateKey",
	)
	suspend fun joinState(
		projectionId: String,
		projectionVersion: Int,
		stateKey: String,
	): SourceProjectionJoinStateEntity?

	@Query(
		"DELETE FROM source_projection_join_state " +
			"WHERE projection_id = :projectionId AND projection_version = :projectionVersion AND state_key = :stateKey",
	)
	suspend fun deleteJoinState(projectionId: String, projectionVersion: Int, stateKey: String)

	@Query(
		"SELECT MIN(COALESCE(checkpoint.contiguous_admission_ordinal, " +
			"registration.activation_ordinal - 1)) " +
			"FROM source_projection_registration registration " +
			"LEFT JOIN source_projection_checkpoint checkpoint " +
			"ON registration.projection_id = checkpoint.projection_id " +
			"AND registration.projection_version = checkpoint.projection_version " +
			"WHERE registration.retention_required = 1 AND registration.status = 'ACTIVE'",
	)
	suspend fun minimumRequiredCheckpoint(): Long?

	@Query(
		"SELECT MIN(contiguous_admission_ordinal) FROM source_product_projection_lane " +
			"WHERE source_kind = :sourceKind AND retention_required = 1",
	)
	suspend fun minimumRequiredProductLaneCheckpoint(sourceKind: Int): Long?

	@Query(
		"SELECT MIN(COALESCE(checkpoint.contiguous_admission_ordinal, " +
			"registration.activation_ordinal - 1)) " +
			"FROM source_projection_registration registration " +
			"LEFT JOIN source_projection_checkpoint checkpoint " +
			"ON registration.projection_id = checkpoint.projection_id " +
			"AND registration.projection_version = checkpoint.projection_version " +
			"WHERE registration.status = 'ACTIVE'",
	)
	suspend fun minimumActiveCheckpoint(): Long?

	@Query("SELECT MIN(minimum_required_ordinal) FROM source_projection_join_state")
	suspend fun minimumJoinRequiredOrdinal(): Long?

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertLeaseIfAbsent(entity: SourceCoordinatorLeaseEntity): Long

	@Query(
		"UPDATE source_coordinator_lease SET " +
			"owner_token = :ownerToken, boot_id = :bootId, " +
			"generation = CASE WHEN owner_token = :ownerToken AND boot_id = :bootId " +
			"AND expires_elapsed_realtime_nanos > :nowElapsedNanos " +
			"THEN generation ELSE generation + 1 END, " +
			"acquired_at_ms = :nowMs, expires_at_ms = :expiresAtMs, " +
			"acquired_elapsed_realtime_nanos = :nowElapsedNanos, " +
			"expires_elapsed_realtime_nanos = :expiresElapsedNanos " +
			"WHERE lease_name = :leaseName AND (" +
			"(owner_token = :ownerToken AND boot_id = :bootId) OR " +
			"boot_id != :bootId OR expires_elapsed_realtime_nanos <= :nowElapsedNanos)",
	)
	suspend fun acquireOrRenewLease(
		leaseName: String,
		ownerToken: String,
		bootId: String,
		nowMs: Long,
		expiresAtMs: Long,
		nowElapsedNanos: Long,
		expiresElapsedNanos: Long,
	): Int

	@Query("SELECT * FROM source_coordinator_lease WHERE lease_name = :leaseName")
	suspend fun lease(leaseName: String): SourceCoordinatorLeaseEntity?

	@Query(
		"UPDATE source_coordinator_lease SET expires_at_ms = :nowMs, " +
			"expires_elapsed_realtime_nanos = :nowElapsedNanos " +
			"WHERE lease_name = :leaseName AND owner_token = :ownerToken AND boot_id = :bootId " +
			"AND generation = :generation",
	)
	suspend fun releaseLease(
		leaseName: String,
		ownerToken: String,
		bootId: String,
		generation: Long,
		nowMs: Long,
		nowElapsedNanos: Long,
	): Int

	@Query(
		"SELECT * FROM source_projection_outbox WHERE delivered_at_ms IS NULL " +
			"AND terminal_disposition IS NULL " +
			"ORDER BY created_at_ms ASC LIMIT :limit",
	)
	suspend fun pendingOutbox(limit: Int): List<SourceProjectionOutboxEntity>

	@Query(
		"SELECT * FROM source_projection_outbox WHERE delivered_at_ms IS NULL " +
			"AND terminal_disposition IS NULL AND effect_kind = :effectKind " +
			"ORDER BY admission_ordinal ASC LIMIT :limit",
	)
	suspend fun pendingOutbox(effectKind: String, limit: Int): List<SourceProjectionOutboxEntity>

	@Query(
		"SELECT * FROM source_projection_outbox WHERE delivered_at_ms IS NULL " +
			"AND terminal_disposition IS NULL AND projection_id = :projectionId " +
			"AND projection_version = :projectionVersion AND effect_kind = :effectKind " +
			"ORDER BY admission_ordinal ASC LIMIT :limit",
	)
	suspend fun pendingOutbox(
		projectionId: String,
		projectionVersion: Int,
		effectKind: String,
		limit: Int,
	): List<SourceProjectionOutboxEntity>

	@Query("SELECT * FROM source_projection_outbox WHERE stable_id = :stableId")
	suspend fun outbox(stableId: String): SourceProjectionOutboxEntity?

	@Query(
		"UPDATE source_projection_outbox SET delivered_at_ms = :deliveredAtMs " +
			"WHERE stable_id = :stableId AND delivered_at_ms IS NULL " +
			"AND terminal_disposition IS NULL",
	)
	suspend fun markOutboxDelivered(stableId: String, deliveredAtMs: Long): Int

	@Query(
		"UPDATE source_projection_outbox SET terminal_disposition = :disposition, " +
			"terminal_at_ms = :terminalAtMs WHERE stable_id = :stableId " +
			"AND delivered_at_ms IS NULL AND terminal_disposition IS NULL",
	)
	suspend fun markOutboxTerminal(
		stableId: String,
		disposition: String,
		terminalAtMs: Long,
	): Int

	@Query(
		"UPDATE source_projection_outbox SET terminal_disposition = :disposition, " +
			"terminal_at_ms = :terminalAtMs WHERE admission_ordinal <= :cutoffAdmissionOrdinal " +
			"AND delivered_at_ms IS NULL AND terminal_disposition IS NULL",
	)
	suspend fun terminalizeUndeliveredThrough(
		cutoffAdmissionOrdinal: Long,
		disposition: String,
		terminalAtMs: Long,
	): Int

	/**
	 * Deletes aged receipts only through the retaining product checkpoint for their source.
	 *
	 * The released schema does not stamp source_kind on the outbox, so the still-retained WAL row
	 * is the authoritative source association. Maintenance calls this before deleting that WAL row.
	 */
	@Query(
		"DELETE FROM source_projection_outbox WHERE stable_id IN (" +
			"SELECT outbox.stable_id FROM source_projection_outbox AS outbox " +
			"JOIN source_event_wal AS wal " +
			"ON wal.admission_ordinal = outbox.admission_ordinal " +
			"WHERE wal.source_kind = :sourceKind " +
			"AND ((outbox.delivered_at_ms IS NOT NULL " +
			"AND outbox.delivered_at_ms < :deliveredBeforeMs) " +
			"OR (outbox.terminal_at_ms IS NOT NULL " +
			"AND outbox.terminal_at_ms < :deliveredBeforeMs)) " +
			"AND outbox.admission_ordinal <= :safeOrdinal " +
			"ORDER BY COALESCE(outbox.delivered_at_ms, outbox.terminal_at_ms), " +
			"outbox.admission_ordinal LIMIT :limit)",
	)
	suspend fun deleteDeliveredOutboxForSourceBatch(
		sourceKind: Int,
		safeOrdinal: Long,
		deliveredBeforeMs: Long,
		limit: Int,
	): Int

	/**
	 * Cleans outbox-only rows after applying any product floor recoverable from writer identity.
	 * Shared writer identities use their most conservative source checkpoint.
	 */
	@Query(
		"DELETE FROM source_projection_outbox WHERE stable_id IN (" +
			"SELECT outbox.stable_id FROM source_projection_outbox AS outbox " +
			"WHERE ((outbox.delivered_at_ms IS NOT NULL " +
			"AND outbox.delivered_at_ms < :deliveredBeforeMs) " +
			"OR (outbox.terminal_at_ms IS NOT NULL " +
			"AND outbox.terminal_at_ms < :deliveredBeforeMs)) " +
			"AND outbox.admission_ordinal <= :safeOrdinal " +
			"AND NOT EXISTS (SELECT 1 FROM source_event_wal AS wal " +
			"WHERE wal.admission_ordinal = outbox.admission_ordinal) " +
			"AND outbox.admission_ordinal <= COALESCE((" +
			"SELECT MIN(lane.contiguous_admission_ordinal) " +
			"FROM source_product_projection_lane AS lane " +
			"WHERE lane.projection_id = outbox.projection_id " +
			"AND lane.projection_version = outbox.projection_version " +
			"AND lane.retention_required = 1), :safeOrdinal) " +
			"ORDER BY COALESCE(outbox.delivered_at_ms, outbox.terminal_at_ms), " +
			"outbox.admission_ordinal LIMIT :limit)",
	)
	suspend fun deleteOrphanedDeliveredOutboxBatch(
		safeOrdinal: Long,
		deliveredBeforeMs: Long,
		limit: Int,
	): Int

	@Query("DELETE FROM source_projection_outbox")
	fun deleteAllOutbox()

	@Query("DELETE FROM source_projection_join_state")
	fun deleteAllJoinState()

	@Query("DELETE FROM source_projection_failure")
	fun deleteAllFailures()

	@Query("DELETE FROM source_projection_checkpoint")
	fun deleteAllCheckpoints()

	@Query("DELETE FROM source_projection_registration")
	fun deleteAllRegistrations()

	@Query("DELETE FROM source_product_projection_lane")
	fun deleteAllProductLanes()

	@Query("DELETE FROM source_coordinator_lease")
	fun deleteAllLeases()
}
