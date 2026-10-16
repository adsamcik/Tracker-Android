package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.RetentionWorkExecutionReceiptEntity

/**
 * Stores retention execution identity, destructive-plan preparation and terminal state for
 * resumable cleanup.
 */
@Dao
interface RetentionWorkExecutionReceiptDao {
	/**
	 * Reads `retention_work_execution_receipt` rows. Query conditions include `execution_id =
	 * :executionId`. Returns at most 1 row. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM retention_work_execution_receipt " +
			"WHERE execution_id = :executionId LIMIT 1",
	)
	suspend fun get(executionId: String): RetentionWorkExecutionReceiptEntity?

	/**
	 * Reads `retention_work_execution_receipt` rows. Query conditions include `work_request_id =
	 * :workRequestId`. Orders by `execution_generation DESC`. Returns at most 1 row. Returns null
	 * when no row matches.
	 */
	@Query(
		"""
		SELECT * FROM retention_work_execution_receipt
		WHERE work_request_id = :workRequestId
		ORDER BY execution_generation DESC
		LIMIT 1
		""",
	)
	suspend fun latest(workRequestId: String): RetentionWorkExecutionReceiptEntity?

	/**
	 * Inserts [RetentionWorkExecutionReceiptEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insert(receipt: RetentionWorkExecutionReceiptEntity)

	/**
	 * Updates `retention_work_execution_receipt` rows matching the supplied scope. Query conditions
	 * include `execution_id = :executionId`, `state = 'OPEN'`. Returns the affected row count.
	 */
	@Query(
		"""
		UPDATE retention_work_execution_receipt
		SET destructive_plan = :destructivePlan, updated_at_ms = :updatedAtMs
		WHERE execution_id = :executionId
			AND state = 'OPEN'
			AND destructive_plan IS NULL
		""",
	)
	suspend fun attachDestructivePlan(
		executionId: String,
		destructivePlan: String,
		updatedAtMs: Long,
	): Int

	/**
	 * Updates `retention_work_execution_receipt` rows matching the supplied scope. Query conditions
	 * include `execution_id = :executionId`, `state = :expectedState`. Returns the affected row
	 * count.
	 */
	@Query(
		"""
		UPDATE retention_work_execution_receipt
		SET state = :newState, updated_at_ms = :updatedAtMs
		WHERE execution_id = :executionId
			AND state = :expectedState
		""",
	)
	suspend fun compareAndSetState(
		executionId: String,
		expectedState: String,
		newState: String,
		updatedAtMs: Long,
	): Int

	/**
	 * Reads `retention_work_execution_receipt` rows. Query conditions include `worker_kind IN
	 * (:workerKinds)`, `state = 'CANCELLATION_REQUESTED'`. Orders by `started_at_ms, execution_id`.
	 */
	@Query(
		"""
		SELECT * FROM retention_work_execution_receipt
		WHERE worker_kind IN (:workerKinds)
			AND state = 'CANCELLATION_REQUESTED'
		ORDER BY started_at_ms, execution_id
		""",
	)
	suspend fun pendingCancellations(
		workerKinds: Collection<String>,
	): List<RetentionWorkExecutionReceiptEntity>

	/**
	 * Updates `retention_work_execution_receipt` rows matching the supplied scope. Query conditions
	 * include `state = 'OPEN'`.
	 */
	@Query(
		"""
		UPDATE retention_work_execution_receipt
		SET state = 'SUPERSEDED', updated_at_ms = MAX(updated_at_ms, :updatedAtMs)
		WHERE state = 'OPEN'
		""",
	)
	suspend fun supersedeOpenExecutions(updatedAtMs: Long): Int
}
