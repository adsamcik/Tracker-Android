package com.adsamcik.tracker.shared.base.database.data

import java.security.MessageDigest

/** Canonical integrity contract shared by manifest writers, history, and product readers. */
object SessionManifestIntegrity {
	private const val FORMAT = "session-manifest-v2"
	fun compute(
		manifest: SessionManifestVersionEntity,
		sources: Collection<SessionManifestSourceEntity>,
	): String {
		require(sources.all { source ->
			source.logicalTrackingId == manifest.logicalTrackingId &&
				source.manifestRevision == manifest.manifestRevision
		}) { "Manifest sources must belong to the exact manifest version" }
		val canonical = encode(
			listOf(
				FORMAT,
				manifest.logicalTrackingId,
				manifest.manifestRevision,
				manifest.serviceRunId,
				manifest.sessionMode,
				manifest.sourcePolicyRevision,
				manifest.acquisitionPlanRevision,
				manifest.rolloutRevision,
				manifest.startOrigin,
				manifest.effectiveBootId,
				manifest.effectiveElapsedRealtimeNanos,
				manifest.effectiveWallTimeMs,
				manifest.zoneId,
				manifest.automationEpoch,
				manifest.changeReason,
				sources.sortedBy(::sourceIdentity).map { source ->
					listOf(
						source.purpose,
						source.sourceKind,
						source.consentEpoch,
						source.persistenceEligible,
						source.qosCode,
						source.outputDestination,
						source.writerOwner,
						source.writerOwnerGeneration,
						source.writerProjectionId,
						source.writerProjectionVersion,
						source.writerBindingGeneration,
					)
				},
			),
		)
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}

	fun verify(
		manifest: SessionManifestVersionEntity,
		sources: Collection<SessionManifestSourceEntity>,
	): Boolean = runCatching { compute(manifest, sources) }
		.getOrNull() == manifest.manifestChecksum

	private fun sourceIdentity(source: SessionManifestSourceEntity): String = encode(
		listOf(
			source.logicalTrackingId,
			source.manifestRevision,
			source.purpose,
			source.sourceKind,
			source.consentEpoch,
			source.persistenceEligible,
			source.qosCode,
			source.outputDestination,
			source.writerOwner,
			source.writerOwnerGeneration,
			source.writerProjectionId,
			source.writerProjectionVersion,
			source.writerBindingGeneration,
		),
	)

	private fun encode(value: Any?): String {
		val tagged = when (value) {
			null -> "N"
			is Iterable<*> -> "L${value.count()}:" + value.joinToString(separator = "") { item -> encode(item) }
			is Array<*> -> "L${value.size}:" + value.joinToString(separator = "") { item -> encode(item) }
			else -> "S${value}"
		}
		return "${tagged.length}:$tagged"
	}
}
