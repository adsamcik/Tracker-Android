package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
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
	indices = [
		Index(
			value = ["effective_boot_id", "effective_elapsed_realtime_nanos", "authority_revision"],
			name = "idx_ambient_wifi_authority_effective",
		),
	],
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
	@ColumnInfo(name = "rollout_revision") val rolloutRevision: Long,
	@ColumnInfo(name = "owner_cas_token") val ownerCasToken: String,
	@ColumnInfo(name = "reconciliation_attempt") val reconciliationAttempt: Long,
	@ColumnInfo(name = "demand_id") val demandId: String?,
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
		require(rolloutRevision >= 0L)
		require(ownerCasToken.isNotBlank())
		require(reconciliationAttempt > 0L)
		require(state != STATE_ACTIVE || demandId != null)
		require(demandId == null || demandId.isNotBlank())
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

/** Durable default-deny retention approval. Import scope is independent of live provider consent. */
@Entity(
	tableName = "ambient_wifi_retention_authority",
	primaryKeys = ["scope", "approval_revision"],
	indices = [
		Index(
			value = [
				"scope",
				"effective_boot_id",
				"effective_elapsed_realtime_nanos",
				"approval_revision",
			],
			name = "idx_ambient_wifi_retention_effective",
		),
	],
)
data class AmbientWifiRetentionAuthorityEntity(
	@ColumnInfo(name = "scope") val scope: String,
	@ColumnInfo(name = "approval_revision") val approvalRevision: Long,
	@ColumnInfo(name = "state") val state: String,
	@ColumnInfo(name = "opaque_policy_id") val opaquePolicyId: String,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long?,
	@ColumnInfo(name = "ambient_consent_epoch") val ambientConsentEpoch: Long?,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "effective_boot_id") val effectiveBootId: String,
	@ColumnInfo(name = "effective_elapsed_realtime_nanos") val effectiveElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "effective_wall_time_ms") val effectiveWallTimeMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(scope in SCOPES)
		require(approvalRevision > 0L)
		require(state in STATES)
		require(opaquePolicyId.isNotBlank() && opaquePolicyId.length <= 256)
		require(collectedDataEpoch >= 0L)
		require(effectiveBootId.isNotBlank())
		require(effectiveElapsedRealtimeNanos >= 0L && effectiveWallTimeMs >= 0L)
		if (scope == SCOPE_LIVE_AMBIENT) {
			require(sourcePolicyRevision != null && sourcePolicyRevision > 0L)
			require(ambientConsentEpoch != null && ambientConsentEpoch >= 0L)
		} else {
			require(sourcePolicyRevision == null && ambientConsentEpoch == null)
		}
		require(effectChecksum == AmbientWifiRetentionAuthorityIntegrity.checksum(this))
	}

	val isActive: Boolean get() = state == STATE_ACTIVE

	companion object {
		const val SCOPE_LIVE_AMBIENT = "LIVE_AMBIENT"
		const val SCOPE_PORTABLE_IMPORT = "PORTABLE_IMPORT"
		const val STATE_ACTIVE = "ACTIVE"
		const val STATE_REVOKED = "REVOKED"
		private val SCOPES = setOf(SCOPE_LIVE_AMBIENT, SCOPE_PORTABLE_IMPORT)
		private val STATES = setOf(STATE_ACTIVE, STATE_REVOKED)
	}
}

object AmbientWifiRetentionAuthorityIntegrity {
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
	): AmbientWifiRetentionAuthorityEntity = AmbientWifiRetentionAuthorityEntity(
		scope,
		approvalRevision,
		state,
		opaquePolicyId,
		sourcePolicyRevision,
		ambientConsentEpoch,
		collectedDataEpoch,
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
			effectiveBootId,
			effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs,
		),
	)

	fun checksum(value: AmbientWifiRetentionAuthorityEntity): String = checksum(
		value.scope,
		value.approvalRevision,
		value.state,
		value.opaquePolicyId,
		value.sourcePolicyRevision,
		value.ambientConsentEpoch,
		value.collectedDataEpoch,
		value.effectiveBootId,
		value.effectiveElapsedRealtimeNanos,
		value.effectiveWallTimeMs,
	)

	fun isAuthentic(value: AmbientWifiRetentionAuthorityEntity): Boolean =
		value.effectChecksum == checksum(value)

	private fun checksum(
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
	): String = AmbientWifiAuthorityIntegrity.digest(
		"ambient-wifi-retention-authority-v1",
		scope,
		approvalRevision,
		state,
		opaquePolicyId,
		sourcePolicyRevision,
		ambientConsentEpoch,
		collectedDataEpoch,
		effectiveBootId,
		effectiveElapsedRealtimeNanos,
		effectiveWallTimeMs,
	)
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
		rolloutRevision: Long,
		ownerCasToken: String,
		reconciliationAttempt: Long,
		demandId: String?,
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
			rolloutRevision = rolloutRevision,
			ownerCasToken = ownerCasToken,
			reconciliationAttempt = reconciliationAttempt,
			demandId = demandId,
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
				rolloutRevision,
				ownerCasToken,
				reconciliationAttempt,
				demandId,
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
		value.rolloutRevision,
		value.ownerCasToken,
		value.reconciliationAttempt,
		value.demandId,
	)

	fun isAuthentic(value: AmbientWifiAuthorityEntity): Boolean =
		value.effectChecksum == checksum(value)

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
		rolloutRevision: Long,
		ownerCasToken: String,
		reconciliationAttempt: Long,
		demandId: String?,
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
		rolloutRevision,
		ownerCasToken,
		reconciliationAttempt,
		demandId,
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
