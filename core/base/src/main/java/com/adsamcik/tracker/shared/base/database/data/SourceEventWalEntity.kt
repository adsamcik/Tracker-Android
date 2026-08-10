package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
	tableName = "source_event_wal",
	indices = [
		Index(value = ["event_id"], unique = true, name = "idx_source_event_wal_event_id"),
		Index(
			value = ["source_kind", "provider_dedup_key"],
			unique = true,
			name = "idx_source_event_wal_provider_dedup",
		),
		Index(
			value = ["logical_tracking_id", "admission_ordinal"],
			name = "idx_source_event_wal_tracking_ordinal",
		),
		Index(
			value = ["source_kind", "source_instance_id", "source_sequence"],
			unique = true,
			name = "idx_source_event_wal_source_sequence",
		),
		Index(
			value = ["captured_collected_data_epoch", "acquired_at_ms"],
			name = "idx_source_event_wal_lifecycle",
		),
		Index(
			value = ["created_at_ms", "admission_ordinal"],
			name = "idx_source_event_wal_retention",
		),
	],
)
data class SourceEventWalEntity(
	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "admission_ordinal")
	val admissionOrdinal: Long = 0L,
	@ColumnInfo(name = "event_id")
	val eventId: String,
	@ColumnInfo(name = "provider_dedup_key")
	val providerDedupKey: String?,
	@ColumnInfo(name = "logical_tracking_id")
	val logicalTrackingId: String?,
	@ColumnInfo(name = "service_run_id")
	val serviceRunId: String?,
	@ColumnInfo(name = "source_kind")
	val sourceKind: Int,
	@ColumnInfo(name = "source_instance_id")
	val sourceInstanceId: String,
	@ColumnInfo(name = "registration_generation")
	val registrationGeneration: Long,
	@ColumnInfo(name = "source_sequence")
	val sourceSequence: Long,
	@ColumnInfo(name = "config_revision")
	val configRevision: Long?,
	@ColumnInfo(name = "plan_attribution")
	val planAttribution: Int,
	@ColumnInfo(name = "clock_domain_id")
	val clockDomainId: String,
	@ColumnInfo(name = "observed_elapsed_nanos")
	val observedElapsedNanos: Long,
	@ColumnInfo(name = "received_elapsed_nanos")
	val receivedElapsedNanos: Long,
	@ColumnInfo(name = "wall_time_ms")
	val wallTimeMs: Long?,
	@ColumnInfo(name = "wall_time_uncertainty_ms")
	val wallTimeUncertaintyMs: Long?,
	@ColumnInfo(name = "captured_collected_data_epoch")
	val capturedCollectedDataEpoch: Long,
	@ColumnInfo(name = "acquired_at_ms")
	val acquiredAtMs: Long,
	@ColumnInfo(name = "quality_flags")
	val qualityFlags: Long,
	@ColumnInfo(name = "quality_confidence")
	val qualityConfidence: Float?,
	@ColumnInfo(name = "payload_version")
	val payloadVersion: Int,
	@ColumnInfo(name = "payload")
	val payload: ByteArray,
	@ColumnInfo(name = "payload_checksum")
	val payloadChecksum: String,
	@ColumnInfo(name = "created_at_ms")
	val createdAtMs: Long,
)
