package com.adsamcik.tracker.shared.base.database.data

import java.security.MessageDigest

/** Frozen identity and retained-effect contract for sessionless Ambient Steps facts. */
object AmbientStepsFactIntegrity {
	fun logicalFactId(
		provider: String,
		windowStartTimeMs: Long,
		windowEndTimeMs: Long,
		structuralEpochDay: Long,
		storedZoneId: String,
		collectedDataEpoch: Long,
	): String = opaqueDigest(
		"ambient-steps-logical-fact-v1",
		provider,
		windowStartTimeMs,
		windowEndTimeMs,
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
		"ambient-steps-effect-v1",
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
