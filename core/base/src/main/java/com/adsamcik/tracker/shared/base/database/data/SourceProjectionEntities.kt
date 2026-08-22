package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
	tableName = "source_projection_registration",
	primaryKeys = ["projection_id", "projection_version"],
	indices = [Index(value = ["status", "activation_ordinal"], name = "idx_source_projection_registration_status")],
)
data class SourceProjectionRegistrationEntity(
	@ColumnInfo(name = "projection_id") val projectionId: String,
	@ColumnInfo(name = "projection_version") val projectionVersion: Int,
	@ColumnInfo(name = "activation_ordinal") val activationOrdinal: Long,
	@ColumnInfo(name = "retention_required") val retentionRequired: Boolean,
	@ColumnInfo(name = "status") val status: String,
	@ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

@Entity(
	tableName = "source_projection_checkpoint",
	primaryKeys = ["projection_id", "projection_version"],
	indices = [Index(value = ["contiguous_admission_ordinal"], name = "idx_source_projection_checkpoint_ordinal")],
)
data class SourceProjectionCheckpointEntity(
	@ColumnInfo(name = "projection_id") val projectionId: String,
	@ColumnInfo(name = "projection_version") val projectionVersion: Int,
	@ColumnInfo(name = "contiguous_admission_ordinal") val contiguousAdmissionOrdinal: Long,
	@ColumnInfo(name = "state_version") val stateVersion: Int,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(
	tableName = "source_projection_failure",
	primaryKeys = ["projection_id", "projection_version", "admission_ordinal"],
	indices = [Index(value = ["terminal", "last_attempt_at_ms"], name = "idx_source_projection_failure_retry")],
)
data class SourceProjectionFailureEntity(
	@ColumnInfo(name = "projection_id") val projectionId: String,
	@ColumnInfo(name = "projection_version") val projectionVersion: Int,
	@ColumnInfo(name = "admission_ordinal") val admissionOrdinal: Long,
	@ColumnInfo(name = "attempt_count") val attemptCount: Int,
	@ColumnInfo(name = "failure_code") val failureCode: String,
	@ColumnInfo(name = "terminal") val terminal: Boolean,
	@ColumnInfo(name = "last_attempt_at_ms") val lastAttemptAtMs: Long,
)

@Entity(
	tableName = "source_projection_join_state",
	primaryKeys = ["projection_id", "projection_version", "state_key"],
	indices = [
		Index(value = ["logical_tracking_id"], name = "idx_source_projection_join_tracking"),
		Index(value = ["updated_at_ms"], name = "idx_source_projection_join_updated"),
		Index(value = ["minimum_required_ordinal"], name = "idx_source_projection_join_retention"),
	],
)
data class SourceProjectionJoinStateEntity(
	@ColumnInfo(name = "projection_id") val projectionId: String,
	@ColumnInfo(name = "projection_version") val projectionVersion: Int,
	@ColumnInfo(name = "state_key") val stateKey: String,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String?,
	@ColumnInfo(name = "minimum_required_ordinal") val minimumRequiredOrdinal: Long,
	@ColumnInfo(name = "payload_version") val payloadVersion: Int,
	@ColumnInfo(name = "payload") val payload: ByteArray,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(
	tableName = "source_projection_outbox",
	primaryKeys = ["stable_id"],
	indices = [
		Index(value = ["delivered_at_ms", "created_at_ms"], name = "idx_source_projection_outbox_delivery"),
		Index(
			value = ["effect_kind", "delivered_at_ms", "admission_ordinal"],
			name = "idx_source_projection_outbox_kind_pending",
		),
		Index(
			value = ["terminal_at_ms", "admission_ordinal"],
			name = "idx_source_projection_outbox_terminal",
		),
	],
)
data class SourceProjectionOutboxEntity(
	@ColumnInfo(name = "stable_id") val stableId: String,
	@ColumnInfo(name = "projection_id") val projectionId: String,
	@ColumnInfo(name = "projection_version") val projectionVersion: Int,
	@ColumnInfo(name = "admission_ordinal") val admissionOrdinal: Long,
	@ColumnInfo(name = "effect_kind") val effectKind: String,
	@ColumnInfo(name = "payload_version") val payloadVersion: Int,
	@ColumnInfo(name = "payload") val payload: ByteArray,
	@ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
	@ColumnInfo(name = "delivered_at_ms") val deliveredAtMs: Long?,
	@ColumnInfo(name = "terminal_disposition") val terminalDisposition: String? = null,
	@ColumnInfo(name = "terminal_at_ms") val terminalAtMs: Long? = null,
)

@Entity(tableName = "source_coordinator_lease")
data class SourceCoordinatorLeaseEntity(
	@androidx.room.PrimaryKey
	@ColumnInfo(name = "lease_name") val leaseName: String,
	@ColumnInfo(name = "owner_token") val ownerToken: String,
	@ColumnInfo(name = "acquired_at_ms") val acquiredAtMs: Long,
	@ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long,
	@ColumnInfo(name = "boot_id", defaultValue = "'LEGACY_UNKNOWN'")
	val bootId: String = "LEGACY_UNKNOWN",
	@ColumnInfo(name = "generation", defaultValue = "0") val generation: Long = 0,
	@ColumnInfo(name = "acquired_elapsed_realtime_nanos", defaultValue = "0")
	val acquiredElapsedRealtimeNanos: Long = 0,
	@ColumnInfo(name = "expires_elapsed_realtime_nanos", defaultValue = "0")
	val expiresElapsedRealtimeNanos: Long = 0,
)
