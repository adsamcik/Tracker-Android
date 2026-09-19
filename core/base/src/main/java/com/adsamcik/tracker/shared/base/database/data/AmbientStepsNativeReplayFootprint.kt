package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import java.security.MessageDigest

/** Value-free portable identity fence retained after native Ambient Steps payload is cleared. */
@Entity(
	tableName = "ambient_steps_native_replay_footprint",
	indices = [
		Index(
			value = ["owner_day_identity"],
			name = "idx_ambient_steps_native_footprint_owner",
		),
	],
)
data class AmbientStepsNativeReplayFootprintEntity(
	@androidx.room.PrimaryKey
	@ColumnInfo(name = "protected_identity") val protectedIdentity: String,
	@ColumnInfo(name = "identity_kind") val identityKind: String,
	@ColumnInfo(name = "owner_day_identity") val ownerDayIdentity: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "protected_at_ms") val protectedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(DIGEST.matches(protectedIdentity))
		require(identityKind in IDENTITY_KINDS)
		require(DIGEST.matches(ownerDayIdentity))
		require(collectedDataEpoch >= 0L)
		require(protectedAtMs >= 0L)
		require(effectChecksum == AmbientStepsNativeReplayFootprintIntegrity.checksum(this))
	}

	companion object {
		const val KIND_DAY = "DAY"
		const val KIND_FACT = "FACT"
		const val KIND_GAP = "GAP"
		private val IDENTITY_KINDS = setOf(KIND_DAY, KIND_FACT, KIND_GAP)
		private val DIGEST = Regex("[0-9a-f]{64}")
	}
}

object AmbientStepsNativeReplayFootprintIntegrity {
	fun create(
		protectedIdentity: String,
		identityKind: String,
		ownerDayIdentity: String,
		collectedDataEpoch: Long,
		protectedAtMs: Long,
	): AmbientStepsNativeReplayFootprintEntity = AmbientStepsNativeReplayFootprintEntity(
		protectedIdentity = protectedIdentity,
		identityKind = identityKind,
		ownerDayIdentity = ownerDayIdentity,
		collectedDataEpoch = collectedDataEpoch,
		protectedAtMs = protectedAtMs,
		effectChecksum = checksum(
			protectedIdentity,
			identityKind,
			ownerDayIdentity,
			collectedDataEpoch,
			protectedAtMs,
		),
	)

	fun checksum(value: AmbientStepsNativeReplayFootprintEntity): String = checksum(
		value.protectedIdentity,
		value.identityKind,
		value.ownerDayIdentity,
		value.collectedDataEpoch,
		value.protectedAtMs,
	)

	fun isAuthentic(value: AmbientStepsNativeReplayFootprintEntity): Boolean =
		value.effectChecksum == checksum(value)

	private fun checksum(vararg values: Any?): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value?.toString()
			if (text == null) "-1:" else "${text.length}:$text"
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(
				("ambient-steps-native-replay-footprint-v1\u001f$canonical")
					.toByteArray(Charsets.UTF_8),
			)
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}
}
