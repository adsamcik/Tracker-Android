package com.adsamcik.tracker.shared.base.database.data

import java.security.MessageDigest

/** Frozen identity and retained-effect contract for sessionless Ambient Steps facts. */
object AmbientStepsFactIntegrity {
	/**
	 * Stable identity for one opaque source-instance continuity segment within one structural day.
	 * The read-through end is deliberately excluded so extensions and corrections revise this fact.
	 */
	fun logicalFactId(
		provider: String,
		registrationGeneration: Long,
		continuitySegmentGeneration: Long,
		sourceInstanceId: String,
		windowStartTimeMs: Long,
		structuralEpochDay: Long,
		storedZoneId: String,
		collectedDataEpoch: Long,
	): String = opaqueDigest(
		"ambient-steps-logical-fact-v3",
		provider,
		registrationGeneration,
		continuitySegmentGeneration,
		sourceInstanceId,
		windowStartTimeMs,
		structuralEpochDay,
		storedZoneId,
		collectedDataEpoch,
	)

	fun mutationId(logicalFactId: String, semanticRevision: Long, operation: String): String {
		require(isOpaque(logicalFactId))
		require(semanticRevision > 0L)
		return opaqueDigest(
			"ambient-steps-mutation-v1",
			logicalFactId,
			semanticRevision,
			operation,
		)
	}

	fun effectChecksum(fact: AmbientStepsFactRevisionEntity): String = digest(
		"ambient-steps-effect-v2",
		fact.logicalFactId,
		fact.semanticRevision,
		fact.mutationId,
		fact.writerId,
		fact.writerVersion,
		fact.writerOwnerGeneration,
		fact.operation,
		fact.originKind,
		fact.provider,
		fact.registrationGeneration,
		fact.continuitySegmentGeneration,
		fact.sourceInstanceId,
		fact.authorizationRevision,
		fact.authorizationFingerprint,
		fact.windowStartTimeMs,
		fact.windowEndTimeMs,
		fact.observedAtMs,
		fact.structuralEpochDay,
		fact.storedZoneId,
		fact.structuralDayStartTimeMs,
		fact.structuralDayEndTimeMs,
		fact.stepCount,
		fact.purpose,
		fact.sourcePolicyRevision,
		fact.ambientConsentEpoch,
		fact.collectedDataEpoch,
		fact.scopeDeletionGeneration,
	)

	fun hasValidEffectChecksum(fact: AmbientStepsFactRevisionEntity): Boolean =
		fact.effectChecksum == effectChecksum(fact)

	internal fun isOpaque(value: String?): Boolean =
		value != null && OPAQUE_IDENTITY.matches(value)

	internal fun isDigest(value: String?): Boolean = value != null && DIGEST.matches(value)

	private fun opaqueDigest(vararg values: Any?): String = "sha256:${digest(*values)}"

	private fun digest(vararg values: Any?): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value?.toString()
			if (text == null) "-1:" else "${text.length}:$text"
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}

	private val OPAQUE_IDENTITY = Regex("sha256:[0-9a-f]{64}")
	private val DIGEST = Regex("[0-9a-f]{64}")
}
