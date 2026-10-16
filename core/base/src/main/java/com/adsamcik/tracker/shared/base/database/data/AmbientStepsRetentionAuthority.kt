package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import java.security.MessageDigest

/** Durable default-deny retention approval for Ambient Steps and its portable origin. */
@Entity(
	tableName = "ambient_steps_retention_authority",
	primaryKeys = ["scope", "approval_revision"],
	indices = [
		Index(
			value = [
				"scope",
				"effective_boot_id",
				"effective_elapsed_realtime_nanos",
				"approval_revision",
			],
			name = "idx_ambient_steps_retention_effective",
		),
		Index(value = ["scope"], name = "idx_ambient_steps_retention_scope"),
	],
)
data class AmbientStepsRetentionAuthorityEntity(
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
		require(scope in SCOPES)
		require(approvalRevision > 0L)
		require(state in STATES)
		require(opaquePolicyId.isNotBlank() && opaquePolicyId.length <= MAX_POLICY_ID_LENGTH)
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
		require(effectChecksum == AmbientStepsRetentionAuthorityIntegrity.checksum(this))
	}

	val isActive: Boolean get() = state == STATE_ACTIVE

	/**
	 * Stable persisted codes, including `SCOPE_LIVE_AMBIENT`, `SCOPE_PORTABLE_IMPORT`,
	 * `STATE_ACTIVE`, and any identity-bound record factories.
	 */
	companion object {
		const val SCOPE_LIVE_AMBIENT = "LIVE_AMBIENT"
		const val SCOPE_PORTABLE_IMPORT = "PORTABLE_IMPORT"
		const val STATE_ACTIVE = "ACTIVE"
		const val STATE_REVOKED = "REVOKED"
		private val SCOPES = setOf(SCOPE_LIVE_AMBIENT, SCOPE_PORTABLE_IMPORT)
		private val STATES = setOf(STATE_ACTIVE, STATE_REVOKED)
		private const val MAX_POLICY_ID_LENGTH = 256
	}
}

/**
 * Deterministic saved Steps retention-policy digests and stored-value checks.
 */
object AmbientStepsRetentionAuthorityIntegrity {
	/**
	 * Builds the Steps retention-policy record and digest from its approval, optional consent
	 * binding, data epoch and effective boot/UTC boundary.
	 */
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
	): AmbientStepsRetentionAuthorityEntity = AmbientStepsRetentionAuthorityEntity(
		scope = scope,
		approvalRevision = approvalRevision,
		state = state,
		opaquePolicyId = opaquePolicyId,
		sourcePolicyRevision = sourcePolicyRevision,
		ambientConsentEpoch = ambientConsentEpoch,
		collectedDataEpoch = collectedDataEpoch,
		retainedFromMs = retainedFromMs,
		effectiveBootId = effectiveBootId,
		effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
		effectiveWallTimeMs = effectiveWallTimeMs,
		effectChecksum = checksum(
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

	/**
	 * Computes the deterministic stored digest from the supplied identity, revision and value fields.
	 */
	fun checksum(value: AmbientStepsRetentionAuthorityEntity): String = checksum(
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

	/**
	 * Checks the supplied record's stored checksum against its canonical fields.
	 */
	fun isAuthentic(value: AmbientStepsRetentionAuthorityEntity): Boolean =
		value.effectChecksum == checksum(value)

	private fun checksum(vararg values: Any?): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value?.toString()
			if (text == null) {
				"-1:"
			} else {
				"${text.length}:$text"
			}
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(
				listOf("ambient-steps-retention-authority-v2", canonical)
					.joinToString(separator = "\u001f")
					.toByteArray(Charsets.UTF_8),
			)
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}
}
