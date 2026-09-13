package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import java.security.MessageDigest

/** Append-only effective-state revisions for one immutable Ambient Steps gap declaration. */
@Entity(
	tableName = "ambient_steps_import_gap_effect_revision",
	primaryKeys = ["gap_id", "semantic_revision"],
	indices = [
		Index(value = ["mutation_id"], unique = true, name = "idx_ambient_steps_gap_effect_mutation"),
	],
)
data class AmbientStepsImportGapEffectRevisionEntity(
	@ColumnInfo(name = "gap_id") val gapId: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	@ColumnInfo(name = "mutation_id") val mutationId: String,
	@ColumnInfo(name = "operation") val operation: String,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
	@ColumnInfo(name = "recorded_at_ms") val recordedAtMs: Long,
) {
	init {
		require(AmbientStepsImportGapIntegrity.isOpaque(gapId))
		require(semanticRevision > 0L)
		require(operation == OPERATION_DECLARE || operation == OPERATION_RETRACT)
		require(mutationId == AmbientStepsImportGapEffectIntegrity.mutationId(gapId, semanticRevision, operation))
		require(
			effectChecksum == AmbientStepsImportGapEffectIntegrity.effectChecksum(
				gapId,
				semanticRevision,
				mutationId,
				operation,
				recordedAtMs,
			),
		)
		require(recordedAtMs >= 0L)
	}

	companion object {
		const val OPERATION_DECLARE = "DECLARE"
		const val OPERATION_RETRACT = "RETRACT"
	}
}

object AmbientStepsImportGapEffectIntegrity {
	fun mutationId(gapId: String, semanticRevision: Long, operation: String): String =
		"sha256:${digest("ambient-steps-gap-effect-mutation-v1", gapId, semanticRevision, operation)}"

	fun effectChecksum(
		gapId: String,
		semanticRevision: Long,
		mutationId: String,
		operation: String,
		recordedAtMs: Long,
	): String =
		digest(
			"ambient-steps-gap-effect-v1",
			gapId,
			semanticRevision,
			mutationId,
			operation,
			recordedAtMs,
		)

	private fun digest(vararg values: Any?): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value?.toString()
			if (text == null) "-1:" else "${text.length}:$text"
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}
}
