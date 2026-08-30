package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Append-only semantic history for one Steps fact, owned by an explicit writer contract.
 *
 * An UPSERT is self-contained: the immutable interval and its effective semantic value remain
 * queryable after the legacy [StepInterval] or source-event row is pruned. A LOCAL_DELETE RETRACT
 * is deliberately redacted, retaining only opaque identity, lifecycle-fence, and audit fields. Its
 * higher semantic revision still suppresses older UPSERTs without retaining interval or session
 * data.
 */
@Entity(
	tableName = "step_fact_revision",
	primaryKeys = [
		"writer_projection_id",
		"writer_projection_version",
		"logical_fact_id",
		"semantic_revision",
	],
	indices = [
		Index(
			value = ["writer_projection_id", "writer_projection_version", "mutation_id"],
			unique = true,
			name = "idx_step_fact_revision_mutation",
		),
		Index(value = ["step_interval_id"], name = "idx_step_fact_revision_interval"),
		Index(
			value = [
				"writer_projection_id",
				"writer_projection_version",
				"source_admission_ordinal",
			],
			unique = true,
			name = "idx_step_fact_revision_writer_admission",
		),
		Index(
			value = ["origin_kind", "origin_identity"],
			name = "idx_step_fact_revision_origin",
		),
		Index(
			value = [
				"writer_projection_id",
				"writer_projection_version",
				"logical_tracking_id",
				"purpose",
				"logical_fact_id",
				"semantic_revision",
			],
			name = "idx_step_fact_revision_session_latest",
		),
		Index(
			value = [
				"writer_projection_id",
				"writer_projection_version",
				"service_run_id",
				"purpose",
				"logical_fact_id",
				"semantic_revision",
			],
			name = "idx_step_fact_revision_service_run_latest",
		),
	],
)
data class StepFactRevisionEntity(
	@ColumnInfo(name = "logical_fact_id") val logicalFactId: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	/** Stable within one writer contract; replaying it must not apply a second effect. */
	@ColumnInfo(name = "mutation_id") val mutationId: String,
	/** Optional compatibility link only; this row never depends on the legacy interval lifetime. */
	@ColumnInfo(name = "step_interval_id") val stepIntervalId: Long?,
	@ColumnInfo(name = "source_event_id") val sourceEventId: String?,
	@ColumnInfo(name = "source_admission_ordinal") val sourceAdmissionOrdinal: Long?,
	@ColumnInfo(name = "origin_kind") val originKind: String,
	@ColumnInfo(name = "origin_identity") val originIdentity: String,
	@ColumnInfo(name = "writer_projection_id") val writerProjectionId: String,
	@ColumnInfo(name = "writer_projection_version") val writerProjectionVersion: Int,
	@ColumnInfo(name = "writer_binding_generation") val writerBindingGeneration: Long,
	@ColumnInfo(name = "operation") val operation: String,
	@ColumnInfo(name = "interval_start_time_ms") val intervalStartTimeMs: Long?,
	@ColumnInfo(name = "interval_end_time_ms") val intervalEndTimeMs: Long?,
	@ColumnInfo(name = "interval_start_elapsed_realtime_nanos")
	val intervalStartElapsedRealtimeNanos: Long?,
	@ColumnInfo(name = "interval_end_elapsed_realtime_nanos")
	val intervalEndElapsedRealtimeNanos: Long?,
	@ColumnInfo(name = "clock_domain_id") val clockDomainId: String?,
	@ColumnInfo(name = "boot_clock_domain_id") val bootClockDomainId: String?,
	@ColumnInfo(name = "cumulative_step_count_start") val cumulativeStepCountStart: Long?,
	@ColumnInfo(name = "cumulative_step_count_end") val cumulativeStepCountEnd: Long?,
	@ColumnInfo(name = "wall_time_uncertainty_ms") val wallTimeUncertaintyMs: Long?,
	/** Boundary/coverage semantics are the sole truth; no redundant reset flag is persisted. */
	@ColumnInfo(name = "coverage_kind") val coverageKind: String?,
	/** Effective value after this revision. Null means a redacted tombstone, not verified zero. */
	@ColumnInfo(name = "effective_step_count") val effectiveStepCount: Long?,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String?,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String?,
	@ColumnInfo(name = "purpose") val purpose: String,
	@ColumnInfo(name = "manifest_revision") val manifestRevision: Long?,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long?,
	@ColumnInfo(name = "capture_consent_epoch") val captureConsentEpoch: Long?,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	/** Monotonic fence for deletion within this source/purpose scope. */
	@ColumnInfo(name = "scope_deletion_generation") val scopeDeletionGeneration: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
	@ColumnInfo(name = "applied_at_ms") val appliedAtMs: Long,
) {
	init {
		require(logicalFactId.isNotBlank())
		require(semanticRevision > 0L)
		require(mutationId.isNotBlank())
		require(stepIntervalId == null || stepIntervalId > 0L)
		require(originKind in ORIGIN_KINDS)
		require(originIdentity.isNotBlank())
		require(writerProjectionId.isNotBlank())
		require(writerProjectionVersion > 0)
		require(writerBindingGeneration > 0L)
		require(operation in OPERATIONS)
		require(purpose == PURPOSE_SESSION_CAPTURE)
		require(collectedDataEpoch >= 0L)
		require(scopeDeletionGeneration >= 0L)
		require(effectChecksum.isNotBlank())
		require(appliedAtMs >= 0L)
		when (operation) {
			OPERATION_UPSERT -> requireUpsertShape()
			OPERATION_RETRACT -> requireRedactedRetractionShape()
		}
	}

	private fun requireUpsertShape() {
		require(scopeDeletionGeneration == 0L)
		require(originKind == ORIGIN_LIVE_WAL || originKind == ORIGIN_PORTABLE_IMPORT)
		when (originKind) {
			ORIGIN_LIVE_WAL -> require(
				sourceEventId?.isNotBlank() == true &&
					sourceAdmissionOrdinal != null && sourceAdmissionOrdinal > 0L,
			)
			ORIGIN_PORTABLE_IMPORT -> require(sourceEventId == null && sourceAdmissionOrdinal == null)
		}
		require(intervalStartTimeMs != null && intervalStartTimeMs >= 0L)
		require(intervalEndTimeMs != null && intervalEndTimeMs >= intervalStartTimeMs)
		require(
			intervalStartElapsedRealtimeNanos != null && intervalStartElapsedRealtimeNanos >= 0L,
		)
		require(
			intervalEndElapsedRealtimeNanos != null &&
				intervalEndElapsedRealtimeNanos >= intervalStartElapsedRealtimeNanos,
		)
		require(clockDomainId?.isNotBlank() == true)
		require(bootClockDomainId?.isNotBlank() == true)
		require(cumulativeStepCountStart != null && cumulativeStepCountStart >= 0L)
		require(cumulativeStepCountEnd != null && cumulativeStepCountEnd >= 0L)
		require(wallTimeUncertaintyMs != null && wallTimeUncertaintyMs >= 0L)
		require(coverageKind in COVERAGE_KINDS)
		require(effectiveStepCount != null && effectiveStepCount >= 0L)
		if (coverageKind == COVERAGE_BASELINE || coverageKind == COVERAGE_RESET_GAP) {
			require(effectiveStepCount == 0L)
		}
		require(!logicalTrackingId.isNullOrBlank())
		require(!serviceRunId.isNullOrBlank())
		require(manifestRevision != null && manifestRevision > 0L)
		require(sourcePolicyRevision != null && sourcePolicyRevision > 0L)
		require(captureConsentEpoch != null && captureConsentEpoch >= 0L)
	}

	private fun requireRedactedRetractionShape() {
		require(originKind == ORIGIN_LOCAL_DELETE)
		require(scopeDeletionGeneration > 0L)
		require(stepIntervalId == null)
		require(sourceEventId == null)
		require(sourceAdmissionOrdinal == null)
		require(intervalStartTimeMs == null)
		require(intervalEndTimeMs == null)
		require(intervalStartElapsedRealtimeNanos == null)
		require(intervalEndElapsedRealtimeNanos == null)
		require(clockDomainId == null)
		require(bootClockDomainId == null)
		require(cumulativeStepCountStart == null)
		require(cumulativeStepCountEnd == null)
		require(wallTimeUncertaintyMs == null)
		require(coverageKind == null)
		require(effectiveStepCount == null)
		require(logicalTrackingId == null)
		require(serviceRunId == null)
		require(manifestRevision == null)
		require(sourcePolicyRevision == null)
		require(captureConsentEpoch == null)
	}

	companion object {
		const val ORIGIN_LIVE_WAL = "LIVE_WAL"
		const val ORIGIN_PORTABLE_IMPORT = "PORTABLE_IMPORT"
		const val ORIGIN_LOCAL_DELETE = "LOCAL_DELETE"
		const val OPERATION_UPSERT = "UPSERT"
		const val OPERATION_RETRACT = "RETRACT"
		const val COVERAGE_BASELINE = "BASELINE"
		const val COVERAGE_COVERED = "COVERED"
		const val COVERAGE_RESET_GAP = "RESET_GAP"
		const val COVERAGE_PARTIAL = "PARTIAL"
		const val PURPOSE_SESSION_CAPTURE = "SESSION_CAPTURE"

		private val ORIGIN_KINDS = setOf(
			ORIGIN_LIVE_WAL,
			ORIGIN_PORTABLE_IMPORT,
			ORIGIN_LOCAL_DELETE,
		)
		private val OPERATIONS = setOf(OPERATION_UPSERT, OPERATION_RETRACT)
		private val COVERAGE_KINDS = setOf(
			COVERAGE_BASELINE,
			COVERAGE_COVERED,
			COVERAGE_RESET_GAP,
			COVERAGE_PARTIAL,
		)
	}
}
