package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
	tableName = "retention_work_execution_receipt",
	indices = [
		Index(
			value = ["work_request_id", "execution_generation"],
			unique = true,
			name = "idx_retention_work_execution_generation",
		),
		Index(
			value = ["work_request_id", "state"],
			name = "idx_retention_work_execution_state",
		),
	],
)
data class RetentionWorkExecutionReceiptEntity(
	@PrimaryKey
	@ColumnInfo(name = "execution_id")
	val executionId: String,
	@ColumnInfo(name = "work_request_id")
	val workRequestId: String,
	@ColumnInfo(name = "execution_generation")
	val executionGeneration: Long,
	@ColumnInfo(name = "worker_kind")
	val workerKind: String,
	@ColumnInfo(name = "started_at_ms")
	val startedAtMs: Long,
	val state: String,
	@ColumnInfo(name = "destructive_plan")
	val destructivePlan: String?,
	@ColumnInfo(name = "updated_at_ms")
	val updatedAtMs: Long,
) {
	init {
		require(executionId.isNotBlank())
		require(workRequestId.isNotBlank())
		require(executionGeneration > 0L)
		require(workerKind.isNotBlank())
		require(startedAtMs >= 0L)
		require(state in STATES)
		require(updatedAtMs >= startedAtMs)
	}

	companion object {
		const val STATE_OPEN = "OPEN"
		const val STATE_FINAL = "FINAL"
		const val STATE_ACKNOWLEDGED = "ACKNOWLEDGED"
		const val STATE_ABANDONED = "ABANDONED"
		const val STATE_SUPERSEDED = "SUPERSEDED"

		private val STATES = setOf(
			STATE_OPEN,
			STATE_FINAL,
			STATE_ACKNOWLEDGED,
			STATE_ABANDONED,
			STATE_SUPERSEDED,
		)
	}
}
