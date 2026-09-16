package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Durable lifecycle-side receipt for one exact provider generation owned by a service run.
 *
 * The provider operation may complete before the coordinator coroutine resumes. Persisting the
 * requested identity first and the full acknowledgement immediately after the physical boundary
 * lets a replacement process distinguish an acknowledged retirement from an interrupted partial.
 */
@Entity(
	tableName = "source_run_retirement",
	primaryKeys = [
		"logical_tracking_id",
		"service_run_id",
		"source_kind",
		"source_instance_id",
		"registration_generation",
	],
)
data class SourceRunRetirementEntity(
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "action_id") val actionId: String,
	@ColumnInfo(name = "attempt_count") val attemptCount: Int,
	@ColumnInfo(name = "lease_generation") val leaseGeneration: Long,
	@ColumnInfo(name = "cutoff_elapsed_realtime_nanos") val cutoffElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "cutoff_wall_time_ms") val cutoffWallTimeMs: Long,
	@ColumnInfo(name = "state") val state: String,
	@ColumnInfo(name = "applied_revision") val appliedRevision: Long?,
	@ColumnInfo(name = "callback_entry_barrier_sequence") val callbackEntryBarrierSequence: Long?,
	@ColumnInfo(name = "last_source_sequence") val lastSourceSequence: Long?,
	@ColumnInfo(name = "last_admission_ordinal") val lastAdmissionOrdinal: Long?,
	@ColumnInfo(name = "failed_admission_count") val failedAdmissionCount: Long?,
	@ColumnInfo(name = "unresolved_sequence_start") val unresolvedSequenceStart: Long?,
	@ColumnInfo(name = "unresolved_sequence_end") val unresolvedSequenceEnd: Long?,
	@ColumnInfo(name = "registration_removal_outcome") val registrationRemovalOutcome: String?,
	@ColumnInfo(name = "provider_flush_outcome") val providerFlushOutcome: String?,
	@ColumnInfo(name = "provider_coverage") val providerCoverage: String?,
	@ColumnInfo(name = "app_drain_complete") val appDrainComplete: Boolean?,
	@ColumnInfo(name = "stop_status") val stopStatus: String?,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
	init {
		require(logicalTrackingId.isNotBlank())
		require(serviceRunId.isNotBlank())
		require(sourceKind > 0)
		require(sourceInstanceId.isNotBlank())
		require(registrationGeneration > 0L)
		require(actionId.isNotBlank())
		require(attemptCount > 0)
		require(leaseGeneration > 0L)
		require(cutoffElapsedRealtimeNanos >= 0L)
		require(cutoffWallTimeMs >= 0L)
		require(state in STATES)
		require(updatedAtMs >= 0L)
		val acknowledgement = listOf(
			callbackEntryBarrierSequence,
			failedAdmissionCount,
			registrationRemovalOutcome,
			providerFlushOutcome,
			providerCoverage,
			appDrainComplete,
			stopStatus,
		)
		if (state == STATE_REQUESTED) {
			require(acknowledgement.all { it == null })
		} else {
			require(acknowledgement.all { it != null })
		}
	}

	companion object {
		const val STATE_REQUESTED = "REQUESTED"
		const val STATE_ACKNOWLEDGED = "ACKNOWLEDGED"
		const val STATE_INTERRUPTED = "INTERRUPTED"
		val STATES = setOf(STATE_REQUESTED, STATE_ACKNOWLEDGED, STATE_INTERRUPTED)
	}
}
