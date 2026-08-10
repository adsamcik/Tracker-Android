package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity

/** Durable provider baseline/watermark state that is not itself source evidence. */
@Entity(
	tableName = "source_runtime_state",
	primaryKeys = ["source_kind", "owner_scope"],
)
data class SourceRuntimeStateEntity(
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "owner_scope") val ownerScope: String,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "clock_domain_id") val clockDomainId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "last_provider_sequence") val lastProviderSequence: Long,
	@ColumnInfo(name = "last_admitted_source_sequence") val lastAdmittedSourceSequence: Long?,
	@ColumnInfo(name = "last_admission_ordinal") val lastAdmissionOrdinal: Long?,
	@ColumnInfo(name = "state_version") val stateVersion: Int,
	@ColumnInfo(name = "payload") val payload: ByteArray,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)
