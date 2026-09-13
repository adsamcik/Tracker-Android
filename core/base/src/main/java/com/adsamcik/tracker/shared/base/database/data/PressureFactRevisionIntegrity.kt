package com.adsamcik.tracker.shared.base.database.data

import java.security.MessageDigest

/** Frozen integrity contract shared by the canonical Pressure writer and exact readers. */
object PressureFactRevisionIntegrity {
	/** Distinct marker purpose: records retention loss without blocking later capture writes. */
	const val RETENTION_TRUNCATION_PURPOSE = "SESSION_CAPTURE_RETENTION_TRUNCATION"

	/** Creates the payload-free marker for an exact Pressure run affected by retention. */
	fun retentionTruncationFence(
		logicalTrackingId: String,
		serviceRunId: String,
		collectedDataEpoch: Long,
		markedAtMs: Long,
	): SourceDeletionFenceEntity = SourceDeletionFenceEntity.createLogicalServiceRun(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = RETENTION_TRUNCATION_PURPOSE,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		fenceGeneration = RETENTION_TRUNCATION_GENERATION,
		collectedDataEpoch = collectedDataEpoch,
		deletedAtMs = markedAtMs,
	)

	/** Stable opaque lookup identity for one exact Pressure run's retention marker. */
	fun retentionTruncationIdentity(
		logicalTrackingId: String,
		serviceRunId: String,
	): String = SourceDeletionFenceEntity.logicalServiceRunIdentity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = RETENTION_TRUNCATION_PURPOSE,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
	)

	/** Recomputes the complete marker, including its payload-free scope and effect checksum. */
	fun isRetentionTruncationFence(
		fence: SourceDeletionFenceEntity,
		logicalTrackingId: String,
		serviceRunId: String,
		collectedDataEpoch: Long,
	): Boolean = runCatching {
		fence == retentionTruncationFence(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			collectedDataEpoch = collectedDataEpoch,
			markedAtMs = fence.deletedAtMs,
		)
	}.getOrDefault(false)

	/** Computes the canonical effect digest, including immutable destination-writer authority. */
	fun effectChecksum(
		fact: PressureFactRevisionEntity,
		binding: SessionManifestSourceEntity,
	): String = digest(
		fact.logicalFactId,
		fact.semanticRevision,
		fact.mutationId,
		fact.sourceEventId,
		fact.sourceAdmissionOrdinal,
		fact.writerProjectionId,
		fact.writerProjectionVersion,
		fact.writerBindingGeneration,
		fact.payloadVersion,
		fact.intervalStartTimeMs,
		fact.intervalEndTimeMs,
		fact.windowStartElapsedRealtimeNanos,
		fact.windowEndElapsedRealtimeNanos,
		fact.clockDomainId,
		fact.wallTimeUncertaintyMs,
		fact.sampleCount,
		fact.meanHectopascals,
		fact.sumSquaredDeviations,
		fact.minimumHectopascals,
		fact.maximumHectopascals,
		fact.firstProviderSequence,
		fact.lastProviderSequence,
		fact.firstHectopascals,
		fact.lastHectopascals,
		fact.slopeHectopascalsPerSecond,
		fact.rSquared,
		fact.sensorAccuracy,
		fact.effectiveSamplePeriodMicros,
		fact.effectiveMaximumReportLatencyMicros,
		fact.targetWindowDurationNanos,
		fact.expectedSampleCount,
		fact.maximumInterSampleGapNanos,
		fact.closureKind,
		fact.qualification,
		fact.sourceQualityFlags,
		fact.sourceQualityConfidence,
		fact.logicalTrackingId,
		fact.serviceRunId,
		fact.purpose,
		fact.manifestRevision,
		fact.sourcePolicyRevision,
		fact.captureConsentEpoch,
		binding.outputDestination,
		binding.writerOwner,
		binding.writerOwnerGeneration,
		binding.writerProjectionId,
		binding.writerProjectionVersion,
		binding.writerBindingGeneration,
		fact.collectedDataEpoch,
	)

	/** True only when the complete retained Pressure effect matches its immutable writer binding. */
	fun hasValidEffectChecksum(
		fact: PressureFactRevisionEntity,
		binding: SessionManifestSourceEntity,
	): Boolean = fact.effectChecksum == effectChecksum(fact, binding)

	private fun digest(vararg values: Any?): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value?.toString()
			if (text == null) {
				"-1:"
			} else {
				"${text.length}:$text"
			}
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}

	private const val RETENTION_TRUNCATION_GENERATION = 1L
}
