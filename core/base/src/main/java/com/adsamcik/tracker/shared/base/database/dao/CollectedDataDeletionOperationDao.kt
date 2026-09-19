package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity

@Dao
interface CollectedDataDeletionOperationDao {
	@Query(
		"SELECT * FROM collected_data_deletion_operation " +
			"WHERE operation_id = :operationId LIMIT 1",
	)
	suspend fun get(operationId: String): CollectedDataDeletionOperationEntity?

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insert(operation: CollectedDataDeletionOperationEntity)

	@Query(
		"""
		SELECT * FROM collected_data_deletion_operation
		WHERE phase IN (
			'RETENTION_PREPARED',
			'RETENTION_DATASTORE_ACKNOWLEDGED',
			'RETENTION_ROOM_GUARD_COMMITTED',
			'RETENTION_AUTHORITY_REISSUED',
			'RETENTION_PROVIDER_RECONCILED',
			'RETENTION_SOURCE_MAINTENANCE_COMPLETED'
		)
		ORDER BY deleted_at_ms ASC, operation_id ASC
		LIMIT 1
		""",
	)
	suspend fun activeRetentionFloorSettlement(): CollectedDataDeletionOperationEntity?

	@Query(
		"""
		SELECT * FROM collected_data_deletion_operation
		WHERE retention_work_execution_id = :workExecutionId
			AND phase IN (
				'RETENTION_PREPARED',
				'RETENTION_DATASTORE_ACKNOWLEDGED',
				'RETENTION_ROOM_GUARD_COMMITTED',
				'RETENTION_AUTHORITY_REISSUED',
				'RETENTION_PROVIDER_RECONCILED',
				'RETENTION_SOURCE_MAINTENANCE_COMPLETED',
				'RETENTION_FINAL'
			)
		ORDER BY deleted_at_ms DESC, operation_id DESC
		LIMIT 1
		""",
	)
	suspend fun latestRetentionFloorSettlementForExecution(
		workExecutionId: String,
	): CollectedDataDeletionOperationEntity?

	@Query(
		"""
		UPDATE collected_data_deletion_operation
		SET phase = 'RETENTION_ACKNOWLEDGED'
		WHERE retention_work_execution_id = :workExecutionId
			AND phase = 'RETENTION_FINAL'
		""",
	)
	suspend fun acknowledgeFinalRetentionFloorSettlementsForExecution(
		workExecutionId: String,
	): Int

	@Query(
		"""
		UPDATE collected_data_deletion_operation
		SET retention_work_execution_id = :newWorkExecutionId
		WHERE operation_id = :operationId
			AND (
				(retention_work_execution_id IS NULL AND :expectedWorkExecutionId IS NULL)
				OR retention_work_execution_id = :expectedWorkExecutionId
			)
			AND phase IN (
				'RETENTION_PREPARED',
				'RETENTION_DATASTORE_ACKNOWLEDGED',
				'RETENTION_ROOM_GUARD_COMMITTED',
				'RETENTION_AUTHORITY_REISSUED',
				'RETENTION_PROVIDER_RECONCILED',
				'RETENTION_SOURCE_MAINTENANCE_COMPLETED'
			)
		""",
	)
	suspend fun claimActiveRetentionExecution(
		operationId: String,
		expectedWorkExecutionId: String?,
		newWorkExecutionId: String,
	): Int

	@Query(
		"""
		SELECT * FROM collected_data_deletion_operation
		WHERE target_collected_data_epoch > :expectedCollectedDataEpoch
			AND phase IN ('DATABASE_CLEARED', 'WRITERS_REARMED')
		ORDER BY target_collected_data_epoch DESC
		LIMIT 1
		""",
	)
	suspend fun completedFullDeletionAfter(
		expectedCollectedDataEpoch: Long,
	): CollectedDataDeletionOperationEntity?

	@Query(
		"""
		UPDATE collected_data_deletion_operation
		SET settled_retained_from_ms = :settledRetainedFromMs,
			phase = :newPhase,
			updated_at_ms = :updatedAtMs
		WHERE operation_id = :operationId
			AND target_collected_data_epoch = :targetCollectedDataEpoch
			AND retained_from_ms = :requestedRetainedFromMs
			AND settled_retained_from_ms IS NULL
			AND phase = :expectedPhase
		""",
	)
	suspend fun compareAndSetRetentionRoomGuard(
		operationId: String,
		targetCollectedDataEpoch: Long,
		requestedRetainedFromMs: Long,
		settledRetainedFromMs: Long,
		expectedPhase: String,
		newPhase: String,
		updatedAtMs: Long,
	): Int

	@Query(
		"""
		SELECT MAX(authority_time) FROM (
			SELECT updated_at_ms AS authority_time
			FROM source_evidence_state
			WHERE id = 1
			UNION ALL
			SELECT effective_wall_time_ms AS authority_time
			FROM ambient_steps_retention_authority
			UNION ALL
			SELECT effective_wall_time_ms AS authority_time
			FROM ambient_wifi_retention_authority
			UNION ALL
			SELECT effective_wall_time_ms AS authority_time
			FROM ambient_cell_retention_authority
		)
		""",
	)
	suspend fun retentionAuthorityTimestampHighWater(): Long?

	@Query(
		"""
		UPDATE collected_data_deletion_operation
		SET source_maintenance_at_ms = :sourceMaintenanceAtMs,
			retention_active_sources = :activeSources,
			phase = :newPhase,
			updated_at_ms = :sourceMaintenanceAtMs
		WHERE operation_id = :operationId
			AND target_collected_data_epoch = :targetCollectedDataEpoch
			AND retained_from_ms = :requestedRetainedFromMs
			AND source_maintenance_at_ms IS NULL
			AND phase = :expectedPhase
		""",
	)
	suspend fun compareAndSetRetentionAuthorityReissued(
		operationId: String,
		targetCollectedDataEpoch: Long,
		requestedRetainedFromMs: Long,
		sourceMaintenanceAtMs: Long,
		activeSources: String,
		expectedPhase: String,
		newPhase: String,
	): Int

	@Query(
		"DELETE FROM collected_data_deletion_operation WHERE operation_id != :operationId",
	)
	suspend fun deleteAllExcept(operationId: String)

	@Query(
		"UPDATE collected_data_deletion_operation " +
			"SET phase = :newPhase, updated_at_ms = :updatedAtMs " +
			"WHERE operation_id = :operationId " +
			"AND target_collected_data_epoch = :targetCollectedDataEpoch " +
			"AND phase = :expectedPhase",
	)
	suspend fun compareAndSetPhase(
		operationId: String,
		targetCollectedDataEpoch: Long,
		expectedPhase: String,
		newPhase: String,
		updatedAtMs: Long,
	): Int

	@Query(
		"""
		UPDATE collected_data_deletion_operation
		SET phase = :newPhase, updated_at_ms = :updatedAtMs
		WHERE operation_id = :operationId
			AND target_collected_data_epoch = :targetCollectedDataEpoch
			AND retained_from_ms = :requestedRetainedFromMs
			AND phase = :expectedPhase
		""",
	)
	suspend fun compareAndSetRetentionPhase(
		operationId: String,
		targetCollectedDataEpoch: Long,
		requestedRetainedFromMs: Long,
		expectedPhase: String,
		newPhase: String,
		updatedAtMs: Long,
	): Int
}
