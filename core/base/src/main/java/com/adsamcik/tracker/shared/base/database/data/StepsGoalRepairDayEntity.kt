package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One structural day whose qualified Steps goal effects must be re-evaluated.
 *
 * The row carries no observation payload and does not prove that Steps were captured. Producers
 * enqueue it only after source evidence has advanced, in the same transaction as the corresponding
 * day repair when one is required. Repeated mutations collapse by [epochDay], while
 * [sourceEvidenceRevision] lets the consumer remove only the exact request it settled.
 */
@Entity(tableName = "steps_goal_repair_day")
data class StepsGoalRepairDayEntity(
	@PrimaryKey
	@ColumnInfo(name = "epoch_day") val epochDay: Long,
	@ColumnInfo(name = "source_evidence_revision") val sourceEvidenceRevision: Long,
) {
	init {
		require(sourceEvidenceRevision >= 0L)
	}
}
