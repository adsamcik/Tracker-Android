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

@Dao
interface SourceProjectionStateDao {
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
		"UPDATE source_coordinator_lease SET owner_token = :ownerToken, acquired_at_ms = :nowMs, " +
			"expires_at_ms = :expiresAtMs WHERE lease_name = :leaseName " +
			"AND (owner_token = :ownerToken OR expires_at_ms <= :nowMs)",
	)
	suspend fun acquireOrRenewLease(
		leaseName: String,
		ownerToken: String,
		nowMs: Long,
		expiresAtMs: Long,
	): Int

	@Query("DELETE FROM source_coordinator_lease WHERE lease_name = :leaseName AND owner_token = :ownerToken")
	suspend fun releaseLease(leaseName: String, ownerToken: String): Int

	@Query(
		"SELECT * FROM source_projection_outbox WHERE delivered_at_ms IS NULL " +
			"ORDER BY created_at_ms ASC LIMIT :limit",
	)
	suspend fun pendingOutbox(limit: Int): List<SourceProjectionOutboxEntity>

	@Query(
		"SELECT * FROM source_projection_outbox WHERE delivered_at_ms IS NULL " +
			"AND effect_kind = :effectKind ORDER BY admission_ordinal ASC LIMIT :limit",
	)
	suspend fun pendingOutbox(effectKind: String, limit: Int): List<SourceProjectionOutboxEntity>

	@Query(
		"UPDATE source_projection_outbox SET delivered_at_ms = :deliveredAtMs " +
			"WHERE stable_id = :stableId AND delivered_at_ms IS NULL",
	)
	suspend fun markOutboxDelivered(stableId: String, deliveredAtMs: Long): Int

	@Query(
		"DELETE FROM source_projection_outbox WHERE stable_id IN (" +
			"SELECT stable_id FROM source_projection_outbox " +
			"WHERE delivered_at_ms IS NOT NULL AND delivered_at_ms < :deliveredBeforeMs " +
			"AND admission_ordinal <= :safeOrdinal " +
			"ORDER BY delivered_at_ms, admission_ordinal LIMIT :limit)",
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

	@Query("DELETE FROM source_coordinator_lease")
	fun deleteAllLeases()
}
