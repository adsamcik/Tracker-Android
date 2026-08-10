package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
	tableName = "logical_tracking_session",
	primaryKeys = ["logical_tracking_id"],
	indices = [Index(value = ["state", "started_at_ms"], name = "idx_logical_tracking_session_state")],
)
data class LogicalTrackingSessionEntity(
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "state") val state: String,
	@ColumnInfo(name = "lifecycle_revision") val lifecycleRevision: Long,
	@ColumnInfo(name = "desired_plan_revision") val desiredPlanRevision: Long,
	@ColumnInfo(name = "rollout_revision") val rolloutRevision: Long,
	@ColumnInfo(name = "start_origin") val startOrigin: String,
	@ColumnInfo(name = "clock_domain_id") val clockDomainId: String,
	@ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
	@ColumnInfo(name = "started_elapsed_nanos") val startedElapsedNanos: Long,
	@ColumnInfo(name = "cutoff_at_ms") val cutoffAtMs: Long?,
	@ColumnInfo(name = "cutoff_elapsed_nanos") val cutoffElapsedNanos: Long?,
	@ColumnInfo(name = "completed_at_ms") val completedAtMs: Long?,
	@ColumnInfo(name = "final_admission_ordinal") val finalAdmissionOrdinal: Long?,
	@ColumnInfo(name = "failure_code") val failureCode: String?,
)

@Entity(
	tableName = "source_service_run",
	primaryKeys = ["service_run_id"],
	indices = [Index(value = ["logical_tracking_id", "started_at_ms"], name = "idx_source_service_run_tracking")],
)
data class SourceServiceRunEntity(
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "state") val state: String,
	@ColumnInfo(name = "desired_plan_revision") val desiredPlanRevision: Long,
	@ColumnInfo(name = "rollout_revision") val rolloutRevision: Long,
	@ColumnInfo(name = "foreground_capability_flags") val foregroundCapabilityFlags: Long,
	@ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
	@ColumnInfo(name = "started_elapsed_nanos") val startedElapsedNanos: Long,
	@ColumnInfo(name = "completed_at_ms") val completedAtMs: Long?,
	@ColumnInfo(name = "completion_reason") val completionReason: String?,
)

@Entity(
	tableName = "source_event_session_binding",
	primaryKeys = ["event_id", "binding_revision"],
	indices = [Index(value = ["logical_tracking_id", "admission_ordinal"], name = "idx_source_event_binding_tracking")],
)
data class SourceEventSessionBindingEntity(
	@ColumnInfo(name = "event_id") val eventId: String,
	@ColumnInfo(name = "binding_revision") val bindingRevision: Long,
	@ColumnInfo(name = "admission_ordinal") val admissionOrdinal: Long,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String?,
	@ColumnInfo(name = "binding_reason") val bindingReason: String,
	@ColumnInfo(name = "decision_status") val decisionStatus: String,
	@ColumnInfo(name = "clock_domain_id") val clockDomainId: String,
	@ColumnInfo(name = "interval_start_elapsed_nanos") val intervalStartElapsedNanos: Long,
	@ColumnInfo(name = "interval_end_elapsed_nanos") val intervalEndElapsedNanos: Long?,
	@ColumnInfo(name = "bound_at_ms") val boundAtMs: Long,
)

@Entity(
	tableName = "source_session_completeness",
	primaryKeys = ["logical_tracking_id", "source_kind", "source_instance_id"],
)
data class SourceSessionCompletenessEntity(
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "last_admission_ordinal") val lastAdmissionOrdinal: Long?,
	@ColumnInfo(name = "last_source_sequence") val lastSourceSequence: Long?,
	@ColumnInfo(name = "app_drain_complete") val appDrainComplete: Boolean,
	@ColumnInfo(name = "provider_coverage") val providerCoverage: String,
	@ColumnInfo(name = "stop_status") val stopStatus: String,
	@ColumnInfo(name = "unresolved_sequence_start") val unresolvedSequenceStart: Long?,
	@ColumnInfo(name = "unresolved_sequence_end") val unresolvedSequenceEnd: Long?,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(tableName = "tracking_rollout_state")
data class TrackingRolloutStateEntity(
	@androidx.room.PrimaryKey val id: Int = 1,
	@ColumnInfo(name = "revision") val revision: Long,
	@ColumnInfo(name = "schema_version") val schemaVersion: Int,
	@ColumnInfo(name = "coordinator_mode") val coordinatorMode: String,
	@ColumnInfo(name = "projection_mode") val projectionMode: String,
	@ColumnInfo(name = "source_owners") val sourceOwners: String,
	@ColumnInfo(name = "semantic_settings_enabled") val semanticSettingsEnabled: Boolean,
	@ColumnInfo(name = "battery_estimate_mode") val batteryEstimateMode: String,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(tableName = "acquisition_plan_revision")
data class AcquisitionPlanRevisionEntity(
	@androidx.room.PrimaryKey
	@ColumnInfo(name = "revision") val revision: Long,
	@ColumnInfo(name = "plan_id") val planId: String,
	@ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
	@ColumnInfo(name = "status") val status: String,
)

@Entity(
	tableName = "source_desired_plan",
	primaryKeys = ["revision", "source_kind"],
)
data class SourceDesiredPlanEntity(
	@ColumnInfo(name = "revision") val revision: Long,
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "payload_version") val payloadVersion: Int,
	@ColumnInfo(name = "payload") val payload: ByteArray,
	@ColumnInfo(name = "payload_checksum") val payloadChecksum: String,
)

@Entity(tableName = "source_applied_plan_state")
data class SourceAppliedPlanStateEntity(
	@androidx.room.PrimaryKey
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "desired_revision") val desiredRevision: Long,
	@ColumnInfo(name = "applied_revision") val appliedRevision: Long?,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String?,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long?,
	@ColumnInfo(name = "applied_at_elapsed_nanos") val appliedAtElapsedNanos: Long?,
	@ColumnInfo(name = "status") val status: String,
	@ColumnInfo(name = "degraded_reasons") val degradedReasons: String,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)
