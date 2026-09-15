package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Durable source-registration admission seal published after callback entry has been fenced.
 *
 * The authorization revision, not provider wall time, is the boundary. Exact replay of already
 * durable WAL remains valid; a later authorization revision may reopen the same physical provider
 * generation for a new capture join without deleting this historical seal.
 */
@Entity(
	tableName = "source_capture_admission_barrier",
	primaryKeys = ["source_kind", "registration_generation"],
)
data class SourceCaptureAdmissionBarrierEntity(
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "through_authorization_revision") val throughAuthorizationRevision: Long,
	@ColumnInfo(name = "last_admission_ordinal") val lastAdmissionOrdinal: Long,
	@ColumnInfo(name = "last_source_sequence") val lastSourceSequence: Long,
	@ColumnInfo(name = "sealed_elapsed_realtime_nanos") val sealedElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "sealed_at_ms") val sealedAtMs: Long,
) {
	init {
		require(sourceKind > 0)
		require(registrationGeneration > 0L)
		require(sourceInstanceId.isNotBlank())
		require(throughAuthorizationRevision > 0L)
		require(lastAdmissionOrdinal >= 0L)
		require(lastSourceSequence >= 0L)
		require(sealedElapsedRealtimeNanos >= 0L)
		require(sealedAtMs >= 0L)
	}
}
