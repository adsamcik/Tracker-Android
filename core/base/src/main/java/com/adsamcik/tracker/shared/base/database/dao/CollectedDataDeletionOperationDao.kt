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
}
