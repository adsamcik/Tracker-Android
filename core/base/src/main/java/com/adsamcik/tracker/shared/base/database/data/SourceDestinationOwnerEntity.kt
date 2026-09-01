package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Permanent one-writer fence for a source's logical product destination.
 *
 * Unlike product-lane/cursor state, this row survives collected-data deletion and owner rollback.
 * Every mutation command is admitted under an exact [owner] and [ownerGeneration], preventing an
 * old command from becoming valid again when ownership later returns to the same owner (ABA).
 * The row is only a fence; changing it is not sufficient to activate, cut over, or recover a writer.
 */
@Entity(
	tableName = "source_destination_owner",
	primaryKeys = ["source_kind", "destination"],
)
data class SourceDestinationOwnerEntity(
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "destination") val destination: String,
	@ColumnInfo(name = "owner") val owner: String,
	@ColumnInfo(name = "owner_generation") val ownerGeneration: Long,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
	init {
		require(sourceKind > 0)
		require(destination.isNotBlank())
		require(owner.isNotBlank())
		require(ownerGeneration > 0L)
		require(updatedAtMs >= 0L)
	}

	companion object {
		const val SOURCE_STEPS = 3
		const val DESTINATION_SESSION_STEPS = "SESSION_STEPS"
		const val OWNER_LEGACY_STEP_INTERVAL = "LEGACY_STEP_INTERVAL"
		const val OWNER_STEPS_SESSION_FACTS = "STEPS_SESSION_FACTS"
		const val INITIAL_LEGACY_GENERATION = 1L
		const val FIRST_CANDIDATE_GENERATION = 2L
		const val STEPS_FACT_PROJECTION_ID = "steps-session-facts"
		const val STEPS_FACT_PROJECTION_VERSION = 1
		/** Immutable manual-session binding retained for already attributed v28 facts. */
		const val STEPS_FACT_BINDING_GENERATION = 1L
		/** Adds automatic-session capture without changing generation 1 semantics. */
		const val STEPS_FACT_AUTOMATIC_BINDING_GENERATION = 2L
	}
}
