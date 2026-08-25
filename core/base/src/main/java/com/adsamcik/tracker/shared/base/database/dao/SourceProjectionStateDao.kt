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
		"SELECT lane.* FROM source_product_projection_lane lane WHERE lane.status = 'ACTIVE' " +
			"AND NOT EXISTS (SELECT 1 FROM source_projection_registration registration " +
			"WHERE registration.projection_id = lane.projection_id " +
			"AND registration.projection_version = lane.projection_version)",
	)
	suspend fun activeProductLanes(): List<SourceProductProjectionLaneEntity>

	/** Fail-closed provider check for callers that cannot depend on the tracker rollout model. */
	@Query(
		"SELECT EXISTS(SELECT 1 FROM source_product_projection_lane " +
			"WHERE source_kind = :sourceKind AND projection_id != '' " +
			"AND projection_version > 0 AND product_stage = :productStage " +
			"AND activated_rollout_revision > 0 " +
			"AND activated_rollout_revision <= :rolloutRevision " +
			"AND activation_ordinal > 0 " +
			"AND contiguous_admission_ordinal >= activation_ordinal - 1 " +
			"AND retention_required = 1 AND status = 'ACTIVE' " +
			"AND NOT EXISTS (SELECT 1 FROM source_projection_registration registration " +
			"WHERE registration.projection_id = source_product_projection_lane.projection_id " +
			"AND registration.projection_version = " +
			"source_product_projection_lane.projection_version))",
	)
	suspend fun isProductLaneReachable(
		sourceKind: Int,
		productStage: String,
		rolloutRevision: Long,
	): Boolean

	@Query(
		"SELECT * FROM source_product_projection_lane " +
			"WHERE projection_id = :projectionId AND projection_version = :projectionVersion",
	)
	suspend fun productLaneByProjection(
		projectionId: String,
		projectionVersion: Int,
	): SourceProductProjectionLaneEntity?

	/**
	 * Moves exactly one source-local cursor monotonically under its installed writer identity.
	 * A future source materializer must call this in the same transaction as its destination write.
	 */
	@Query(
		"UPDATE source_product_projection_lane SET " +
			"contiguous_admission_ordinal = :throughOrdinal, updated_at_ms = :updatedAtMs " +
			"WHERE source_kind = :sourceKind AND projection_id = :projectionId " +
			"AND projection_version = :projectionVersion AND status = 'ACTIVE' " +
			"AND :throughOrdinal >= activation_ordinal - 1 " +
			"AND contiguous_admission_ordinal = :expectedCurrentOrdinal " +
			"AND contiguous_admission_ordinal < :throughOrdinal",
	)
	suspend fun advanceProductLaneCursor(
		sourceKind: Int,
		projectionId: String,
		projectionVersion: Int,
		expectedCurrentOrdinal: Long,
		throughOrdinal: Long,
		updatedAtMs: Long,
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
		"SELECT MIN(checkpoint.contiguous_admission_ordinal) " +
			"FROM source_projection_registration registration " +
			"JOIN source_projection_checkpoint checkpoint " +
			"ON registration.projection_id = checkpoint.projection_id " +
			"AND registration.projection_version = checkpoint.projection_version " +
			"WHERE registration.retention_required = 1 AND registration.status = 'ACTIVE'",
	)
	suspend fun minimumRequiredCheckpoint(): Long?

	@Query(
		"SELECT MIN(contiguous_admission_ordinal) FROM source_product_projection_lane " +
			"WHERE retention_required = 1 AND status = 'ACTIVE'",
	)
	suspend fun minimumRequiredProductLaneCheckpoint(): Long?

	@Query(
		"SELECT MIN(checkpoint.contiguous_admission_ordinal) " +
			"FROM source_projection_registration registration " +
			"JOIN source_projection_checkpoint checkpoint " +
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

	@Query(
		"DELETE FROM source_projection_outbox WHERE stable_id IN (" +
			"SELECT stable_id FROM source_projection_outbox " +
			"WHERE ((delivered_at_ms IS NOT NULL AND delivered_at_ms < :deliveredBeforeMs) " +
			"OR (terminal_at_ms IS NOT NULL AND terminal_at_ms < :deliveredBeforeMs)) " +
			"AND admission_ordinal <= :safeOrdinal " +
			"ORDER BY COALESCE(delivered_at_ms, terminal_at_ms), admission_ordinal LIMIT :limit)",
	)
	suspend fun deleteDeliveredOutboxBatch(
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
