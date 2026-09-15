package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Process-independent exclusion fence between source demand mutation and destructive maintenance.
 *
 * A maintenance service acquires the row after policy/consent preflight. Every direct-demand
 * mutator must reject while the exact unexpired generation is ACTIVE. The runtime rechecks the
 * same row before provider retirement, and the erase transaction rechecks it before payload
 * deletion. Holding a Room transaction across callback drain is deliberately unnecessary.
 */
@Entity(
	tableName = "source_maintenance_authority",
	primaryKeys = ["source_kind", "purpose"],
)
data class SourceMaintenanceAuthorityEntity(
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "purpose") val purpose: String,
	@ColumnInfo(name = "owner_token") val ownerToken: String,
	@ColumnInfo(name = "generation") val generation: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "policy_revision") val policyRevision: Long,
	@ColumnInfo(name = "consent_epoch") val consentEpoch: Long,
	@ColumnInfo(name = "boot_id") val bootId: String,
	@ColumnInfo(name = "expires_elapsed_realtime_nanos") val expiresElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "state") val state: String,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
	init {
		require(sourceKind > 0)
		require(purpose.isNotBlank())
		require(ownerToken.isNotBlank())
		require(generation > 0L)
		require(collectedDataEpoch >= 0L)
		require(policyRevision > 0L)
		require(consentEpoch >= 0L)
		require(bootId.isNotBlank())
		require(expiresElapsedRealtimeNanos >= 0L)
		require(state in STATES)
		require(updatedAtMs >= 0L)
	}

	companion object {
		const val PURPOSE_SOURCE_ERASE = "SOURCE_ERASE"
		const val STATE_ACTIVE = "ACTIVE"
		const val STATE_RELEASED = "RELEASED"
		val STATES = setOf(STATE_ACTIVE, STATE_RELEASED)
	}
}
