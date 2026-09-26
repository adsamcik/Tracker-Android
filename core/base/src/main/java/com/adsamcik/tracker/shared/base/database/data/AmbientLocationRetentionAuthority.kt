package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/** Durable default-deny retention approval for sessionless Location. */
@Entity(
	tableName = "ambient_location_retention_authority",
	primaryKeys = ["scope", "approval_revision"],
	indices = [
		Index(
			value = [
				"scope",
				"effective_boot_id",
				"effective_elapsed_realtime_nanos",
				"approval_revision",
			],
			name = "idx_ambient_location_retention_effective",
		),
	],
)
data class AmbientLocationRetentionAuthorityEntity(
	@ColumnInfo(name = "scope") val scope: String,
	@ColumnInfo(name = "approval_revision") val approvalRevision: Long,
	@ColumnInfo(name = "state") val state: String,
	@ColumnInfo(name = "opaque_policy_id") val opaquePolicyId: String,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long?,
	@ColumnInfo(name = "ambient_consent_epoch") val ambientConsentEpoch: Long?,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "retained_from_ms") val retainedFromMs: Long?,
	@ColumnInfo(name = "effective_boot_id") val effectiveBootId: String,
	@ColumnInfo(name = "effective_elapsed_realtime_nanos") val effectiveElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "effective_wall_time_ms") val effectiveWallTimeMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(scope == SCOPE_LIVE_AMBIENT)
		require(approvalRevision > 0L)
		require(state in STATES)
		require(opaquePolicyId.isNotBlank() && opaquePolicyId.length <= 256)
		require(collectedDataEpoch >= 0L)
		require(retainedFromMs == null || retainedFromMs >= 0L)
		require(effectiveBootId.isNotBlank())
		require(effectiveElapsedRealtimeNanos >= 0L && effectiveWallTimeMs >= 0L)
		if (scope == SCOPE_LIVE_AMBIENT) {
			require(sourcePolicyRevision != null && sourcePolicyRevision > 0L)
			require(ambientConsentEpoch != null && ambientConsentEpoch >= 0L)
		} else {
			require(sourcePolicyRevision == null && ambientConsentEpoch == null)
		}
		require(effectChecksum == AmbientLocationRetentionAuthorityIntegrity.checksum(this))
	}

	val isActive: Boolean get() = state == STATE_ACTIVE

	companion object {
		const val SCOPE_LIVE_AMBIENT = "LIVE_AMBIENT"
		const val STATE_ACTIVE = "ACTIVE"
		const val STATE_REVOKED = "REVOKED"
		private val STATES = setOf(STATE_ACTIVE, STATE_REVOKED)
	}
}

object AmbientLocationRetentionAuthorityIntegrity {
	fun create(
		scope: String,
		approvalRevision: Long,
		state: String,
		opaquePolicyId: String,
		sourcePolicyRevision: Long?,
		ambientConsentEpoch: Long?,
		collectedDataEpoch: Long,
		effectiveBootId: String,
		effectiveElapsedRealtimeNanos: Long,
		effectiveWallTimeMs: Long,
		retainedFromMs: Long? = null,
	): AmbientLocationRetentionAuthorityEntity = AmbientLocationRetentionAuthorityEntity(
		scope,
		approvalRevision,
		state,
		opaquePolicyId,
		sourcePolicyRevision,
		ambientConsentEpoch,
		collectedDataEpoch,
		retainedFromMs,
		effectiveBootId,
		effectiveElapsedRealtimeNanos,
		effectiveWallTimeMs,
		checksum(
			scope,
			approvalRevision,
			state,
			opaquePolicyId,
			sourcePolicyRevision,
			ambientConsentEpoch,
			collectedDataEpoch,
			retainedFromMs,
			effectiveBootId,
			effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs,
		),
	)

	fun checksum(value: AmbientLocationRetentionAuthorityEntity): String = checksum(
		value.scope,
		value.approvalRevision,
		value.state,
		value.opaquePolicyId,
		value.sourcePolicyRevision,
		value.ambientConsentEpoch,
		value.collectedDataEpoch,
		value.retainedFromMs,
		value.effectiveBootId,
		value.effectiveElapsedRealtimeNanos,
		value.effectiveWallTimeMs,
	)

	fun isAuthentic(value: AmbientLocationRetentionAuthorityEntity): Boolean =
		value.effectChecksum == checksum(value)

	private fun checksum(
		scope: String,
		approvalRevision: Long,
		state: String,
		opaquePolicyId: String,
		sourcePolicyRevision: Long?,
		ambientConsentEpoch: Long?,
		collectedDataEpoch: Long,
		retainedFromMs: Long?,
		effectiveBootId: String,
		effectiveElapsedRealtimeNanos: Long,
		effectiveWallTimeMs: Long,
	): String = AmbientWifiAuthorityIntegrity.digest(
		"ambient-location-retention-authority-v2",
		scope,
		approvalRevision,
		state,
		opaquePolicyId,
		sourcePolicyRevision,
		ambientConsentEpoch,
		collectedDataEpoch,
		retainedFromMs,
		effectiveBootId,
		effectiveElapsedRealtimeNanos,
		effectiveWallTimeMs,
	)
}

