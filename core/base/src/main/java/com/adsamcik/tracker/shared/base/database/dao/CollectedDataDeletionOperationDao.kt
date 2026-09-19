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
		"DELETE FROM collected_data_deletion_operation WHERE phase = 'RETENTION_FINAL'",
	)
	suspend fun deleteFinalizedRetentionFloorSettlements()

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
