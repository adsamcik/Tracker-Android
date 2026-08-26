package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Append-only semantic history for one durable [StepInterval].
 *
 * The interval remains the immutable observation. This sidecar is both the durable mutation receipt
 * and the auditable semantic revision. Corrections and retractions append a new row and the latest
 * effective value is authoritative; no second additive balance is stored. Writer ownership and
 * cursor progress remain authoritative in the source-product projection lane.
 */
@Entity(
	tableName = "step_fact_revision",
	primaryKeys = ["logical_fact_id", "semantic_revision"],
	foreignKeys = [
		ForeignKey(
			entity = StepInterval::class,
			parentColumns = ["id"],
			childColumns = ["step_interval_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(value = ["mutation_id"], unique = true, name = "idx_step_fact_revision_mutation"),
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
			value = ["logical_tracking_id", "purpose", "logical_fact_id", "semantic_revision"],
			name = "idx_step_fact_revision_session_latest",
		),
	],
)
data class StepFactRevisionEntity(
	@ColumnInfo(name = "logical_fact_id") val logicalFactId: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	/** Stable identity for one logical mutation; replaying it must not apply a second effect. */
	@ColumnInfo(name = "mutation_id") val mutationId: String,
	@ColumnInfo(name = "step_interval_id") val stepIntervalId: Long,
	/** Source-event identity is absent for portable facts that did not originate in this WAL. */
	@ColumnInfo(name = "source_event_id") val sourceEventId: String?,
	/**
	 * Local WAL ordinal and one-delivery receipt. A live correction must be a distinct durable WAL
	 * mutation with a new ordinal. Portable imports use null and therefore do not collide in
	 * SQLite's unique nullable index.
	 */
	@ColumnInfo(name = "source_admission_ordinal") val sourceAdmissionOrdinal: Long?,
	@ColumnInfo(name = "origin_kind") val originKind: String,
	@ColumnInfo(name = "origin_identity") val originIdentity: String,
	@ColumnInfo(name = "writer_projection_id") val writerProjectionId: String,
	@ColumnInfo(name = "writer_projection_version") val writerProjectionVersion: Int,
	@ColumnInfo(name = "writer_binding_generation") val writerBindingGeneration: Long,
	@ColumnInfo(name = "operation") val operation: String,
	@ColumnInfo(name = "coverage_kind") val coverageKind: String,
	/** Effective value after this revision. It is Long so materialization never clamps Int input. */
	@ColumnInfo(name = "effective_step_count") val effectiveStepCount: Long,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String?,
	@ColumnInfo(name = "purpose") val purpose: String,
	@ColumnInfo(name = "manifest_revision") val manifestRevision: Long?,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long,
	@ColumnInfo(name = "capture_consent_epoch") val captureConsentEpoch: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
	@ColumnInfo(name = "applied_at_ms") val appliedAtMs: Long,
) {
	init {
		require(logicalFactId.isNotBlank())
		require(semanticRevision > 0L)
		require(mutationId.isNotBlank())
		require(stepIntervalId > 0L)
		require(originKind in ORIGIN_KINDS)
		require(originIdentity.isNotBlank())
		require(writerProjectionId.isNotBlank())
		require(writerProjectionVersion > 0)
		require(writerBindingGeneration > 0L)
		require(operation in OPERATIONS)
		require(coverageKind in COVERAGE_KINDS)
		require(effectiveStepCount >= 0L)
		require(purpose == PURPOSE_SESSION_CAPTURE)
		require(manifestRevision == null || manifestRevision > 0L)
		require(sourcePolicyRevision > 0L)
		require(captureConsentEpoch >= 0L)
		require(collectedDataEpoch >= 0L)
		require(effectChecksum.isNotBlank())
		require(appliedAtMs >= 0L)
		require(sourceAdmissionOrdinal == null || sourceAdmissionOrdinal > 0L)
		when (originKind) {
			ORIGIN_LIVE_WAL -> require(sourceEventId?.isNotBlank() == true && sourceAdmissionOrdinal != null)
			ORIGIN_PORTABLE_IMPORT -> require(sourceEventId == null && sourceAdmissionOrdinal == null)
		}
		if (operation == OPERATION_RETRACT) require(effectiveStepCount == 0L)
		if (coverageKind == COVERAGE_BASELINE || coverageKind == COVERAGE_RESET_GAP) {
			require(effectiveStepCount == 0L)
		}
		require(!logicalTrackingId.isNullOrBlank())
		require(manifestRevision != null)
	}

	companion object {
		const val ORIGIN_LIVE_WAL = "LIVE_WAL"
		const val ORIGIN_PORTABLE_IMPORT = "PORTABLE_IMPORT"
		const val OPERATION_UPSERT = "UPSERT"
		const val OPERATION_RETRACT = "RETRACT"
		const val COVERAGE_BASELINE = "BASELINE"
		const val COVERAGE_COVERED = "COVERED"
		const val COVERAGE_RESET_GAP = "RESET_GAP"
		const val COVERAGE_PARTIAL = "PARTIAL"
		const val PURPOSE_SESSION_CAPTURE = "SESSION_CAPTURE"

		private val ORIGIN_KINDS = setOf(ORIGIN_LIVE_WAL, ORIGIN_PORTABLE_IMPORT)
		private val OPERATIONS = setOf(OPERATION_UPSERT, OPERATION_RETRACT)
		private val COVERAGE_KINDS = setOf(
			COVERAGE_BASELINE,
			COVERAGE_COVERED,
			COVERAGE_RESET_GAP,
			COVERAGE_PARTIAL,
		)
	}
}
