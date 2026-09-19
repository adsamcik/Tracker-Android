package com.adsamcik.tracker.shared.base.database.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

/**
 * Durable journal for destructive collected-data lifecycle transitions.
 *
 * The table name is retained for v28 schema compatibility; full deletion and retention-floor
 * settlement use disjoint phase families.
 */
@Entity(tableName = "collected_data_deletion_operation")
data class CollectedDataDeletionOperationEntity(
	@PrimaryKey
	@ColumnInfo(name = "operation_id")
	val operationId: String,
	@ColumnInfo(name = "target_collected_data_epoch")
	val targetCollectedDataEpoch: Long,
	@ColumnInfo(name = "retained_from_ms")
	val retainedFromMs: Long?,
	@ColumnInfo(name = "deleted_at_ms")
	val deletedAtMs: Long,
	val phase: String,
	@ColumnInfo(name = "updated_at_ms")
	val updatedAtMs: Long,
	@ColumnInfo(name = "retention_work_execution_id")
	val retentionWorkExecutionId: String? = null,
	@ColumnInfo(name = "retention_destructive_plan")
	val retentionDestructivePlan: String? = null,
	@ColumnInfo(name = "settled_retained_from_ms")
	val settledRetainedFromMs: Long? = null,
) {
	init {
		require(operationId.isNotBlank())
		require(targetCollectedDataEpoch >= 0L)
		require(retainedFromMs == null || retainedFromMs >= 0L)
		require(deletedAtMs >= 0L)
		require(phase in PHASES)
		require(updatedAtMs >= deletedAtMs)
		require(retentionWorkExecutionId == null || retentionWorkExecutionId.isNotBlank())
		require(
			(retentionWorkExecutionId == null) == (retentionDestructivePlan == null),
		)
		require(settledRetainedFromMs == null || settledRetainedFromMs >= 0L)
		require(
			settledRetainedFromMs == null ||
				phase in RETENTION_PHASES.drop(RETENTION_ROOM_GUARD_PHASE_INDEX),
		)
	}

	companion object {
		const val PHASE_DATABASE_CLEARED = "DATABASE_CLEARED"
		const val PHASE_WRITERS_REARMED = "WRITERS_REARMED"
		const val PHASE_RETENTION_PREPARED = "RETENTION_PREPARED"
		const val PHASE_RETENTION_DATASTORE_ACKNOWLEDGED =
			"RETENTION_DATASTORE_ACKNOWLEDGED"
		const val PHASE_RETENTION_ROOM_GUARD_COMMITTED =
			"RETENTION_ROOM_GUARD_COMMITTED"
		const val PHASE_RETENTION_AUTHORITY_REISSUED =
			"RETENTION_AUTHORITY_REISSUED"
		const val PHASE_RETENTION_PROVIDER_RECONCILED =
			"RETENTION_PROVIDER_RECONCILED"
		const val PHASE_RETENTION_SOURCE_MAINTENANCE_COMPLETED =
			"RETENTION_SOURCE_MAINTENANCE_COMPLETED"
		const val PHASE_RETENTION_FINAL = "RETENTION_FINAL"
		const val PHASE_RETENTION_ACKNOWLEDGED = "RETENTION_ACKNOWLEDGED"

		private val PHASES = setOf(
			PHASE_DATABASE_CLEARED,
			PHASE_WRITERS_REARMED,
			PHASE_RETENTION_PREPARED,
			PHASE_RETENTION_DATASTORE_ACKNOWLEDGED,
			PHASE_RETENTION_ROOM_GUARD_COMMITTED,
			PHASE_RETENTION_AUTHORITY_REISSUED,
			PHASE_RETENTION_PROVIDER_RECONCILED,
			PHASE_RETENTION_SOURCE_MAINTENANCE_COMPLETED,
			PHASE_RETENTION_FINAL,
			PHASE_RETENTION_ACKNOWLEDGED,
		)

		val RETENTION_PHASES = listOf(
			PHASE_RETENTION_PREPARED,
			PHASE_RETENTION_DATASTORE_ACKNOWLEDGED,
			PHASE_RETENTION_ROOM_GUARD_COMMITTED,
			PHASE_RETENTION_AUTHORITY_REISSUED,
			PHASE_RETENTION_PROVIDER_RECONCILED,
			PHASE_RETENTION_SOURCE_MAINTENANCE_COMPLETED,
			PHASE_RETENTION_FINAL,
			PHASE_RETENTION_ACKNOWLEDGED,
		)

		private val RETENTION_ROOM_GUARD_PHASE_INDEX =
			RETENTION_PHASES.indexOf(PHASE_RETENTION_ROOM_GUARD_COMMITTED)
	}
}

fun CollectedDataDeletionOperationEntity.hasReachedRetentionPhase(expected: String): Boolean {
	val currentIndex = CollectedDataDeletionOperationEntity.RETENTION_PHASES.indexOf(phase)
	val expectedIndex = CollectedDataDeletionOperationEntity.RETENTION_PHASES.indexOf(expected)
	require(currentIndex >= 0) { "Operation is not a retention-floor settlement" }
	require(expectedIndex >= 0) { "Unknown retention-floor settlement phase" }
	return currentIndex >= expectedIndex
}
