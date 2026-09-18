package com.adsamcik.tracker.shared.base.database.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

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
) {
	init {
		require(operationId.isNotBlank())
		require(targetCollectedDataEpoch > 0L)
		require(retainedFromMs == null || retainedFromMs >= 0L)
		require(deletedAtMs >= 0L)
		require(phase in PHASES)
		require(updatedAtMs >= deletedAtMs)
	}

	companion object {
		const val PHASE_DATABASE_CLEARED = "DATABASE_CLEARED"
		const val PHASE_WRITERS_REARMED = "WRITERS_REARMED"

		private val PHASES = setOf(
			PHASE_DATABASE_CLEARED,
			PHASE_WRITERS_REARMED,
		)
	}
}
