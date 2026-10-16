package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity

/**
 * Persists collected-data deletion operations and their recovery state without controlling
 * physical providers.
 */
@Dao
interface CollectedDataDeletionOperationDao {
	/**
	 * Reads `collected_data_deletion_operation` rows. Query conditions include `operation_id =
	 * :operationId`. Returns at most 1 row. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM collected_data_deletion_operation " +
			"WHERE operation_id = :operationId LIMIT 1",
	)
	suspend fun get(operationId: String): CollectedDataDeletionOperationEntity?

	/**
	 * Inserts [CollectedDataDeletionOperationEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insert(operation: CollectedDataDeletionOperationEntity)

	/**
	 * Reads `collected_data_deletion_operation` rows. Query conditions include `phase IN (
	 * 'RETENTION_PREPARED', 'RETENTION_DATASTORE_ACKNOWLEDGED', 'RETENTION_ROOM_GUARD_COMMITTED',
	 * 'RETENTION_AUTHORITY_REISSUED', 'RETENTION_PROVIDER_RECONCILED',
	 * 'RETENTION_SOURCE_MAINTENANCE_COMPLETED' )`. Orders by `deleted_at_ms ASC, operation_id ASC`.
	 * Returns at most 1 row. Returns null when no row matches.
	 */
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

	/**
	 * Reads `collected_data_deletion_operation` rows. Query conditions include
	 * `retention_work_execution_id = :workExecutionId`, `phase IN ( 'RETENTION_PREPARED',
	 * 'RETENTION_DATASTORE_ACKNOWLEDGED', 'RETENTION_ROOM_GUARD_COMMITTED',
	 * 'RETENTION_AUTHORITY_REISSUED', 'RETENTION_PROVIDER_RECONCILED',
	 * 'RETENTION_SOURCE_MAINTENANCE_COMPLETED', 'RETENTION_FINAL' )`. Orders by `deleted_at_ms DESC,
	 * operation_id DESC`. Returns at most 1 row. Returns null when no row matches.
	 */
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

	/**
	 * Updates `collected_data_deletion_operation` rows matching the supplied scope. Query conditions
	 * include `retention_work_execution_id = :workExecutionId`, `phase = 'RETENTION_FINAL'`. Returns
	 * the affected row count.
	 */
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

	/**
	 * Updates `collected_data_deletion_operation` rows matching the supplied scope. Query conditions
	 * include `operation_id = :operationId`, `retention_work_execution_id =
	 * :expectedWorkExecutionId`, `phase IN ( 'RETENTION_PREPARED',
	 * 'RETENTION_DATASTORE_ACKNOWLEDGED', 'RETENTION_ROOM_GUARD_COMMITTED',
	 * 'RETENTION_AUTHORITY_REISSUED', 'RETENTION_PROVIDER_RECONCILED',
	 * 'RETENTION_SOURCE_MAINTENANCE_COMPLETED' )`. Returns the affected row count.
	 */
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

	/**
	 * Reads `collected_data_deletion_operation` rows. Query conditions include
	 * `target_collected_data_epoch > :expectedCollectedDataEpoch`, `phase IN ('DATABASE_CLEARED',
	 * 'WRITERS_REARMED')`. Orders by `target_collected_data_epoch DESC`. Returns at most 1 row.
	 * Returns null when no row matches.
	 */
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

	/**
	 * Updates `collected_data_deletion_operation` rows matching the supplied scope. Query conditions
	 * include `operation_id = :operationId`, `target_collected_data_epoch =
	 * :targetCollectedDataEpoch`, `retained_from_ms = :requestedRetainedFromMs`, `phase =
	 * :expectedPhase`. Returns the affected row count.
	 */
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

	/**
	 * Returns the latest UTC millisecond authority timestamp across the current data lifecycle and
	 * retained Location, Steps, Wi-Fi and Cell floors; null means no saved authority timestamp
	 * exists.
	 */
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
			UNION ALL
			SELECT effective_wall_time_ms AS authority_time
			FROM ambient_location_retention_authority
		)
		""",
	)
	suspend fun retentionAuthorityTimestampHighWater(): Long?

	/**
	 * Updates `collected_data_deletion_operation` rows matching the supplied scope. Query conditions
	 * include `operation_id = :operationId`, `target_collected_data_epoch =
	 * :targetCollectedDataEpoch`, `retained_from_ms = :requestedRetainedFromMs`, `phase =
	 * :expectedPhase`. Returns the affected row count.
	 */
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

	/**
	 * Deletes `collected_data_deletion_operation` rows matching the supplied scope. Query conditions
	 * include `operation_id != :operationId`.
	 */
	@Query(
		"DELETE FROM collected_data_deletion_operation WHERE operation_id != :operationId",
	)
	suspend fun deleteAllExcept(operationId: String)

	/**
	 * Updates `collected_data_deletion_operation` rows matching the supplied scope. Query conditions
	 * include `operation_id = :operationId`, `target_collected_data_epoch =
	 * :targetCollectedDataEpoch`, `phase = :expectedPhase`. Returns the affected row count.
	 */
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

	/**
	 * Updates `collected_data_deletion_operation` rows matching the supplied scope. Query conditions
	 * include `operation_id = :operationId`, `target_collected_data_epoch =
	 * :targetCollectedDataEpoch`, `retained_from_ms = :requestedRetainedFromMs`, `phase =
	 * :expectedPhase`.
	 */
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
