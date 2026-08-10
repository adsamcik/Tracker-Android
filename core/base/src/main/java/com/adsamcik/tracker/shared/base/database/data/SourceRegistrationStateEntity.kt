package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
	tableName = "source_registration_state",
	primaryKeys = ["source_kind", "owner_scope"],
)
data class SourceRegistrationStateEntity(
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "owner_scope") val ownerScope: String,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "clock_domain_id") val clockDomainId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "next_sequence") val nextSequence: Long,
	@ColumnInfo(name = "applied_revision") val appliedRevision: Long?,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)
