package com.adsamcik.tracker.tracker.source.model

data class SourceEvidenceCandidate<T : SourcePayload>(
	val providerDedupKey: String?,
	val logicalTrackingId: LogicalTrackingId?,
	val serviceRunId: ServiceRunId?,
	val source: SourceKind,
	val sourceInstanceId: SourceInstanceId,
	val registrationGeneration: Long,
	val sourceSequence: Long,
	val configRevision: Long?,
	val planAttribution: PlanAttribution,
	val clockDomainId: String,
	val observedElapsedRealtimeNanos: Long,
	val receivedElapsedRealtimeNanos: Long,
	val wallTimeMs: Long?,
	val wallTimeUncertaintyMs: Long?,
	val capturedCollectedDataEpoch: Long,
	val acquiredAtMs: Long,
	val quality: SourceQuality,
	val payloadVersion: Int,
	val payload: T,
) {
	init {
		require(payload.source == source) { "Payload source must match candidate source" }
		require(registrationGeneration >= 0L) { "Registration generation must not be negative" }
		require(sourceSequence >= 0L) { "Source sequence must not be negative" }
		require(configRevision == null || configRevision >= 0L) { "Config revision must not be negative" }
		require(clockDomainId.isNotBlank()) { "Clock domain ID must not be blank" }
		require(observedElapsedRealtimeNanos >= 0L) { "Observed monotonic time must not be negative" }
		require(receivedElapsedRealtimeNanos >= 0L) { "Received monotonic time must not be negative" }
		require(wallTimeUncertaintyMs == null || wallTimeUncertaintyMs >= 0L) {
			"Wall-time uncertainty must not be negative"
		}
		require(capturedCollectedDataEpoch >= 0L) { "Collected-data epoch must not be negative" }
		require(acquiredAtMs >= 0L) { "Acquisition time must not be negative" }
		require(payloadVersion > 0) { "Payload version must be positive" }
		require(planAttribution == PlanAttribution.RECEIVE_TIME_ONLY || configRevision != null) {
			"Causally attributed evidence requires a config revision"
		}
	}
}

enum class PlanAttribution {
	CAPTURED_REGISTRATION,
	LINKED_ATTEMPT,
	RECEIVE_TIME_ONLY,
}

data class AdmittedSourceEvent<T : SourcePayload>(
	val eventId: SourceEventId,
	val admissionOrdinal: Long,
	val evidence: SourceEvidenceCandidate<T>,
) {
	init {
		require(admissionOrdinal > 0L) { "Admission ordinal must be positive" }
	}
}

