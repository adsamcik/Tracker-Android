package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Immutable boundary and durable outcome for the one-time released-v27 source projection drain.
 *
 * A row exists only when an on-device database was migrated from v27. Fresh v28 databases have no
 * row. [cutoffAdmissionOrdinal] is captured by `MIGRATION_27_28` before any v28 provider can admit
 * work, so recovery can never grant a released row a new v28 purpose or consume a v28 row through
 * the legacy contract.
 */
@Entity(tableName = "legacy_v27_projection_drain")
data class LegacyV27ProjectionDrainEntity(
	@PrimaryKey
	val id: Int = SINGLETON_ID,
	@ColumnInfo(name = "source_schema_version")
	val sourceSchemaVersion: Int = SOURCE_SCHEMA_VERSION,
	@ColumnInfo(name = "contract_version")
	val contractVersion: Int = CONTRACT_VERSION,
	@ColumnInfo(name = "cutoff_admission_ordinal")
	val cutoffAdmissionOrdinal: Long,
	@ColumnInfo(name = "collected_data_epoch")
	val collectedDataEpoch: Long,
	@ColumnInfo(name = "status")
	val status: String,
	@ColumnInfo(name = "owner_boot_id")
	val ownerBootId: String?,
	@ColumnInfo(name = "owner_token")
	val ownerToken: String?,
	@ColumnInfo(name = "lease_generation")
	val leaseGeneration: Long,
	@ColumnInfo(name = "lease_expires_elapsed_nanos")
	val leaseExpiresElapsedNanos: Long?,
	@ColumnInfo(name = "started_at_ms")
	val startedAtMs: Long?,
	@ColumnInfo(name = "completed_at_ms")
	val completedAtMs: Long?,
	@ColumnInfo(name = "suppressed_outbox_count")
	val suppressedOutboxCount: Long,
	@ColumnInfo(name = "failure_code")
	val failureCode: String?,
) {
	companion object {
		const val SINGLETON_ID = 1
		const val SOURCE_SCHEMA_VERSION = 27
		const val CONTRACT_VERSION = 1

		const val STATUS_NOT_REQUIRED = "NOT_REQUIRED"
		const val STATUS_PENDING = "PENDING"
		const val STATUS_RUNNING = "RUNNING"
		const val STATUS_COMPLETE = "COMPLETE"
		const val STATUS_COMPLETE_PARTIAL = "COMPLETE_PARTIAL"
		const val STATUS_FAILED_RETRYABLE = "FAILED_RETRYABLE"
		const val STATUS_BLOCKED_UNSUPPORTED_TARGET = "BLOCKED_UNSUPPORTED_TARGET"
	}
}

/** Frozen per-projection starting point captured by the v27 -> v28 migration transaction. */
@Entity(
	tableName = "legacy_v27_projection_target",
	primaryKeys = ["projection_id", "projection_version"],
)
data class LegacyV27ProjectionTargetEntity(
	@ColumnInfo(name = "projection_id")
	val projectionId: String,
	@ColumnInfo(name = "projection_version")
	val projectionVersion: Int,
	@ColumnInfo(name = "initial_activation_ordinal")
	val initialActivationOrdinal: Long,
	@ColumnInfo(name = "initial_checkpoint_ordinal")
	val initialCheckpointOrdinal: Long,
	@ColumnInfo(name = "required_through_ordinal")
	val requiredThroughOrdinal: Long,
	@ColumnInfo(name = "last_completed_ordinal")
	val lastCompletedOrdinal: Long,
	@ColumnInfo(name = "retention_required")
	val retentionRequired: Boolean,
	@ColumnInfo(name = "initial_registration_status")
	val initialRegistrationStatus: String,
	@ColumnInfo(name = "disposition")
	val disposition: String,
	@ColumnInfo(name = "completed_at_ms")
	val completedAtMs: Long?,
	@ColumnInfo(name = "failure_code")
	val failureCode: String?,
) {
	companion object {
		const val DISPOSITION_PENDING = "PENDING"
		const val DISPOSITION_BLOCKED_UNSUPPORTED = "BLOCKED_UNSUPPORTED"
	}
}
