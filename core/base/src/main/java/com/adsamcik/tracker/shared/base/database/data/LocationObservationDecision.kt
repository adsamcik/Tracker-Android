package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Immutable terminal curated-pipeline decision for one raw provider observation.
 *
 * [observationSourceEventId] is the durable provider identity. It is deliberately not derived
 * from coordinates or timestamps, so duplicate/same-time fixes remain independently auditable.
 * One terminal row is appended for each source event; replay is idempotent through
 * [sourceSignalId].
 */
@Entity(
	tableName = "location_observation_decision",
	indices = [
		Index(
			value = ["observation_source_event_id"],
			unique = true,
			name = "idx_location_observation_decision_event",
		),
		Index(
			value = ["source_signal_id"],
			unique = true,
			name = "idx_location_observation_decision_signal",
		),
		Index(value = ["decision", "decided_at_ms"], name = "idx_location_observation_decision_time"),
	],
)
data class LocationObservationDecision(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,
	@ColumnInfo(name = "observation_source_event_id")
	val observationSourceEventId: String,
	/** `ACCEPTED` or `REJECTED`; use strings so future outcomes remain forward-compatible. */
	val decision: String,
	val reason: String? = null,
	@ColumnInfo(name = "decision_version")
	val decisionVersion: Int = CURRENT_DECISION_VERSION,
	/** Immutable destination-writer provenance captured in the canonical commit transaction. */
	@ColumnInfo(name = "writer_projection_id")
	val writerProjectionId: String? = null,
	@ColumnInfo(name = "writer_projection_version")
	val writerProjectionVersion: Int? = null,
	@ColumnInfo(name = "writer_binding_generation")
	val writerBindingGeneration: Long? = null,
	@ColumnInfo(name = "writer_owner_generation")
	val writerOwnerGeneration: Long? = null,
	/** WAL identity of the accepted `location_sample`, if one was written. */
	@ColumnInfo(name = "accepted_sample_source_signal_id")
	val acceptedSampleSourceSignalId: String? = null,
	/** WAL identity for this append-only decision event. */
	@ColumnInfo(name = "source_signal_id")
	val sourceSignalId: String,
	@ColumnInfo(name = "clock_domain_id")
	val clockDomainId: String? = null,
	@ColumnInfo(name = "decided_at_ms")
	val decidedAtMs: Long,
	@ColumnInfo(name = "source_revision", defaultValue = "0")
	val sourceRevision: Long = 0L,
) {
	init {
		val writerProvenance = listOf(
			writerProjectionId,
			writerProjectionVersion,
			writerBindingGeneration,
			writerOwnerGeneration,
		)
		require(writerProvenance.all { it == null } || writerProvenance.all { it != null })
		require(writerProjectionId == null || writerProjectionId.isNotBlank())
		require(writerProjectionVersion == null || writerProjectionVersion > 0)
		require(writerBindingGeneration == null || writerBindingGeneration > 0L)
		require(writerOwnerGeneration == null || writerOwnerGeneration > 0L)
	}

	companion object {
		const val CURRENT_DECISION_VERSION = 1
		const val ACCEPTED = "ACCEPTED"
		const val REJECTED = "REJECTED"
	}
}
