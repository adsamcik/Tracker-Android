package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import java.security.MessageDigest

/**
 * Append-only authority for the default-off, sessionless Wi-Fi product.
 *
 * The opaque retention policy is approved outside the tracking engine. This row binds that
 * approval to one policy/consent/deletion epoch before a provider demand may be activated.
 */
@Entity(
	tableName = "ambient_wifi_authority",
	primaryKeys = ["authority_revision"],
)
data class AmbientWifiAuthorityEntity(
	@ColumnInfo(name = "authority_revision") val authorityRevision: Long,
	@ColumnInfo(name = "state") val state: String,
	@ColumnInfo(name = "writer_id") val writerId: String,
	@ColumnInfo(name = "writer_version") val writerVersion: Int,
	@ColumnInfo(name = "writer_owner_generation") val writerOwnerGeneration: Long,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long,
	@ColumnInfo(name = "ambient_consent_epoch") val ambientConsentEpoch: Long,
	@ColumnInfo(name = "retention_policy_id") val retentionPolicyId: String,
	@ColumnInfo(name = "retention_approval_revision") val retentionApprovalRevision: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "scope_deletion_generation") val scopeDeletionGeneration: Long,
	@ColumnInfo(name = "effective_boot_id") val effectiveBootId: String,
	@ColumnInfo(name = "effective_elapsed_realtime_nanos") val effectiveElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "effective_wall_time_ms") val effectiveWallTimeMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(authorityRevision > 0L)
		require(state in STATES)
		require(writerId == AmbientWifiFactRevisionEntity.WRITER_ID)
		require(writerVersion == AmbientWifiFactRevisionEntity.WRITER_VERSION)
		require(writerOwnerGeneration == FIRST_WRITER_OWNER_GENERATION)
		require(sourcePolicyRevision > 0L)
		require(ambientConsentEpoch >= 0L)
		require(retentionPolicyId.isNotBlank() && retentionPolicyId.length <= MAX_POLICY_ID_LENGTH)
		require(retentionApprovalRevision > 0L)
		require(collectedDataEpoch >= 0L && scopeDeletionGeneration >= 0L)
		require(effectiveBootId.isNotBlank())
		require(effectiveElapsedRealtimeNanos >= 0L && effectiveWallTimeMs >= 0L)
		require(effectChecksum == AmbientWifiAuthorityIntegrity.checksum(this))
	}

	val isActive: Boolean get() = state == STATE_ACTIVE

	companion object {
		const val STATE_ACTIVE = "ACTIVE"
		const val STATE_REVOKED = "REVOKED"
		const val FIRST_WRITER_OWNER_GENERATION = 1L
		private val STATES = setOf(STATE_ACTIVE, STATE_REVOKED)
		private const val MAX_POLICY_ID_LENGTH = 256
	}
}

object AmbientWifiAuthorityIntegrity {
	fun create(
		authorityRevision: Long,
		state: String,
		sourcePolicyRevision: Long,
		ambientConsentEpoch: Long,
		retentionPolicyId: String,
		retentionApprovalRevision: Long,
		collectedDataEpoch: Long,
		scopeDeletionGeneration: Long,
		effectiveBootId: String,
		effectiveElapsedRealtimeNanos: Long,
		effectiveWallTimeMs: Long,
	): AmbientWifiAuthorityEntity = AmbientWifiAuthorityEntity(
			authorityRevision = authorityRevision,
			state = state,
			writerId = AmbientWifiFactRevisionEntity.WRITER_ID,
			writerVersion = AmbientWifiFactRevisionEntity.WRITER_VERSION,
			writerOwnerGeneration = AmbientWifiAuthorityEntity.FIRST_WRITER_OWNER_GENERATION,
			sourcePolicyRevision = sourcePolicyRevision,
			ambientConsentEpoch = ambientConsentEpoch,
			retentionPolicyId = retentionPolicyId,
			retentionApprovalRevision = retentionApprovalRevision,
			collectedDataEpoch = collectedDataEpoch,
			scopeDeletionGeneration = scopeDeletionGeneration,
			effectiveBootId = effectiveBootId,
			effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs = effectiveWallTimeMs,
			effectChecksum = checksum(
				authorityRevision,
				state,
				sourcePolicyRevision,
				ambientConsentEpoch,
				retentionPolicyId,
				retentionApprovalRevision,
				collectedDataEpoch,
				scopeDeletionGeneration,
				effectiveBootId,
				effectiveElapsedRealtimeNanos,
				effectiveWallTimeMs,
			),
		)

	fun checksum(value: AmbientWifiAuthorityEntity): String = checksum(
		value.authorityRevision,
		value.state,
		value.sourcePolicyRevision,
		value.ambientConsentEpoch,
		value.retentionPolicyId,
		value.retentionApprovalRevision,
		value.collectedDataEpoch,
		value.scopeDeletionGeneration,
		value.effectiveBootId,
		value.effectiveElapsedRealtimeNanos,
		value.effectiveWallTimeMs,
	)

	private fun checksum(
		authorityRevision: Long,
		state: String,
		sourcePolicyRevision: Long,
		ambientConsentEpoch: Long,
		retentionPolicyId: String,
		retentionApprovalRevision: Long,
		collectedDataEpoch: Long,
		scopeDeletionGeneration: Long,
		effectiveBootId: String,
		effectiveElapsedRealtimeNanos: Long,
		effectiveWallTimeMs: Long,
	): String = digest(
		"ambient-wifi-authority-v1",
		authorityRevision,
		state,
		AmbientWifiFactRevisionEntity.WRITER_ID,
		AmbientWifiFactRevisionEntity.WRITER_VERSION,
		AmbientWifiAuthorityEntity.FIRST_WRITER_OWNER_GENERATION,
		sourcePolicyRevision,
		ambientConsentEpoch,
		retentionPolicyId,
		retentionApprovalRevision,
		collectedDataEpoch,
		scopeDeletionGeneration,
		effectiveBootId,
		effectiveElapsedRealtimeNanos,
		effectiveWallTimeMs,
	)

	fun digest(namespace: String, vararg values: Any?): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value?.toString()
			if (text == null) "-1:" else "${text.length}:$text"
		}
		return MessageDigest.getInstance("SHA-256")
			.digest("$namespace\u001f$canonical".toByteArray())
			.joinToString("") { byte -> "%02x".format(byte) }
	}

	internal fun isDigest(value: String): Boolean = DIGEST.matches(value)

	private val DIGEST = Regex("[0-9a-f]{64}")
}
