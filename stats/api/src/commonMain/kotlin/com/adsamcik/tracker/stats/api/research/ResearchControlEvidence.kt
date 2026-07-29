package com.adsamcik.tracker.stats.api.research

/**
 * V2 control evidence is an audit contract, not a live controller. It deliberately records
 * declared decisions and their correlation metadata without selecting any platform behaviour.
 */
enum class ResearchControlEventKind {
	TRACKING_ENABLED,
	TRACKING_DISABLED,
	POLICY_INPUT,
	POLICY_STATE_CHANGED,
	MOTION_CHANGED,
	ACTIVITY_CHANGED,
	LOCATION_BATCH_RECEIVED,
	LOCATION_ACCEPTED,
	LOCATION_REJECTED,
	GAP_OPENED,
	GAP_CLOSED,
	STOP_CANDIDATE,
	STOP_CONFIRMED,
	SHADOW_DIVERGENCE,
	UNKNOWN,
}

/**
 * A generic, ordered control event. Ordering and clock-domain ownership live on the enclosing
 * [ResearchEvidenceEnvelope]; [payload] is a small, schema-neutral set of recorded inputs or
 * outputs. Payload keys are sorted by [ResearchEvidenceCodec] before a wire adapter sees them.
 */
data class ResearchControlEventRecord(
	val logicalTrackingId: String,
	val eventEpochMs: Long? = null,
	val eventElapsedNanos: Long? = null,
	val kind: ResearchControlEventKind,
	val reason: String? = null,
	val correlationId: String? = null,
	val payload: Map<String, String> = emptyMap(),
) : ResearchEvidenceRecord {
	init {
		require(logicalTrackingId.isNotBlank()) { "Logical tracking id must not be blank" }
		require(eventElapsedNanos == null || eventElapsedNanos >= 0L) {
			"Control event elapsed time must be non-negative"
		}
		require(correlationId?.isNotBlank() != false) { "Correlation id must not be blank when present" }
		require(payload.keys.none(String::isBlank)) { "Control payload key must not be blank" }
		require(payload.values.none(String::isBlank)) { "Control payload value must not be blank" }
	}
}

enum class ResearchTrackingMode {
	USER_INITIATED,
	AUTOMATIC,
	AMBIENT,
	RECOVERY,
	UNKNOWN,
}

enum class ResearchTrackingLifecycleTransition {
	STARTED,
	PAUSED,
	RESUMED,
	STOP_CANDIDATE,
	STOPPED,
	RECOVERED,
}

/** The authority/cause recorded only when a logical tracking run is stopped. */
enum class ResearchTrackingStopCause {
	USER_REQUEST,
	AUTOMATIC_ACTIVITY,
	AUTOMATIC_INACTIVITY,
	SYSTEM,
	RECOVERY,
	UNKNOWN,
}

/**
 * Logical lifecycle evidence. This is separate from a service process start/restart and lets
 * replay distinguish an explicit user run from an automatic/ambient candidate.
 */
data class ResearchTrackingLifecycleRecord(
	val logicalTrackingId: String,
	val transition: ResearchTrackingLifecycleTransition,
	val trackingMode: ResearchTrackingMode,
	val eventEpochMs: Long? = null,
	val eventElapsedNanos: Long? = null,
	val stopCause: ResearchTrackingStopCause? = null,
	val reason: String? = null,
	val correlationId: String? = null,
	val resumedFromLogicalTrackingId: String? = null,
) : ResearchEvidenceRecord {
	init {
		require(logicalTrackingId.isNotBlank()) { "Logical tracking id must not be blank" }
		require(eventElapsedNanos == null || eventElapsedNanos >= 0L) {
			"Lifecycle elapsed time must be non-negative"
		}
		require(stopCause == null || transition == ResearchTrackingLifecycleTransition.STOPPED) {
			"A stop cause may be attached only to a stopped lifecycle transition"
		}
		require(transition != ResearchTrackingLifecycleTransition.STOPPED || stopCause != null) {
			"A stopped lifecycle transition must declare its stop cause"
		}
		require(resumedFromLogicalTrackingId == null || transition == ResearchTrackingLifecycleTransition.RESUMED) {
			"A resumed logical id may be attached only to a resumed lifecycle transition"
		}
		require(resumedFromLogicalTrackingId?.isNotBlank() != false) {
			"Resumed logical tracking id must not be blank when present"
		}
		require(correlationId?.isNotBlank() != false) { "Correlation id must not be blank when present" }
	}
}

enum class ResearchAcquisitionMode {
	DISABLED,
	PASSIVE,
	LOW_POWER,
	BALANCED,
	HIGH_ACCURACY,
	PROBE,
}

/**
 * Platform-neutral request shape. The adapter may map this to fused, native, or no provider;
 * [providerIdentity] records that mapping without making it a correctness claim.
 */
data class ResearchAcquisitionConfiguration(
	val mode: ResearchAcquisitionMode,
	val intervalMs: Long? = null,
	val minUpdateDistanceM: Float? = null,
	val maxUpdateDelayMs: Long? = null,
	val requestGnssStatus: Boolean? = null,
	val providerIdentity: String? = null,
	val priority: String? = null,
) {
	init {
		require(intervalMs == null || intervalMs >= 0L) { "Acquisition interval must be non-negative" }
		require(maxUpdateDelayMs == null || maxUpdateDelayMs >= 0L) {
			"Acquisition max delay must be non-negative"
		}
		require(minUpdateDistanceM == null || minUpdateDistanceM.isFinite() && minUpdateDistanceM >= 0f) {
			"Acquisition minimum distance must be finite and non-negative"
		}
		require(providerIdentity?.isNotBlank() != false) { "Provider identity must not be blank when present" }
		require(priority?.isNotBlank() != false) { "Acquisition priority must not be blank when present" }
	}
}

enum class ResearchAcquisitionApplyOutcome {
	REQUESTED,
	APPLIED,
	NO_CHANGE,
	SUPPRESSED_BY_BUDGET,
	UNSUPPORTED,
	FAILED,
	UNKNOWN,
}

/** Records both the reducer's desired request and the adapter's actually applied request. */
data class ResearchAcquisitionRecord(
	val logicalTrackingId: String,
	val requestId: String,
	val eventEpochMs: Long? = null,
	val eventElapsedNanos: Long? = null,
	val desired: ResearchAcquisitionConfiguration,
	val applied: ResearchAcquisitionConfiguration? = null,
	val outcome: ResearchAcquisitionApplyOutcome = ResearchAcquisitionApplyOutcome.REQUESTED,
	val reason: String? = null,
	val correlationId: String? = null,
) : ResearchEvidenceRecord {
	init {
		require(logicalTrackingId.isNotBlank()) { "Logical tracking id must not be blank" }
		require(requestId.isNotBlank()) { "Acquisition request id must not be blank" }
		require(eventElapsedNanos == null || eventElapsedNanos >= 0L) {
			"Acquisition elapsed time must be non-negative"
		}
		require(outcome != ResearchAcquisitionApplyOutcome.APPLIED || applied != null) {
			"An applied acquisition outcome must describe the applied request"
		}
		require(correlationId?.isNotBlank() != false) { "Correlation id must not be blank when present" }
	}
}

enum class ResearchHorizontalEstimatorDecision {
	INITIALIZED,
	PREDICTED,
	UPDATED,
	REJECTED_MEASUREMENT,
	REORDERED,
	GAP_RESET,
	NUMERICAL_FAILURE,
}

/**
 * Coordinate-free diagnostic snapshot for a horizontal estimator. State values are local ENU
 * components (never lat/lon); this remains research-only evidence and does not change a location
 * row. Empty vectors mean the implementation deliberately did not expose that diagnostic.
 */
data class ResearchHorizontalEstimatorRecord(
	val logicalTrackingId: String,
	val estimatorVersion: String,
	val decision: ResearchHorizontalEstimatorDecision,
	val eventEpochMs: Long? = null,
	val sourceElapsedNanos: Long? = null,
	val deltaNanos: Long? = null,
	val sourceSequence: Long? = null,
	val stateDimension: Int = 4,
	val stateBefore: List<Double> = emptyList(),
	val stateAfter: List<Double> = emptyList(),
	val covarianceBefore: List<Double> = emptyList(),
	val covarianceAfter: List<Double> = emptyList(),
	val processNoise: List<Double> = emptyList(),
	val measurementEastM: Double? = null,
	val measurementNorthM: Double? = null,
	val measurementCovariance: List<Double> = emptyList(),
	val innovation: List<Double> = emptyList(),
	val normalizedInnovationSquared: Double? = null,
	val accepted: Boolean? = null,
	val reason: String? = null,
	val correlationId: String? = null,
) : ResearchEvidenceRecord {
	init {
		require(logicalTrackingId.isNotBlank()) { "Logical tracking id must not be blank" }
		require(estimatorVersion.isNotBlank()) { "Horizontal estimator version must not be blank" }
		require(sourceElapsedNanos == null || sourceElapsedNanos >= 0L) {
			"Estimator source elapsed time must be non-negative"
		}
		require(deltaNanos == null || deltaNanos >= 0L) { "Estimator delta must be non-negative" }
		require(sourceSequence == null || sourceSequence >= 0L) { "Estimator source sequence must be non-negative" }
		require(stateDimension >= 0) { "Estimator state dimension must be non-negative" }
		require(stateBefore.isEmpty() || stateBefore.size == stateDimension) {
			"Estimator state-before vector has the wrong dimension"
		}
		require(stateAfter.isEmpty() || stateAfter.size == stateDimension) {
			"Estimator state-after vector has the wrong dimension"
		}
		require(covarianceBefore.isEmpty() || covarianceBefore.size == stateDimension * stateDimension) {
			"Estimator covariance-before matrix has the wrong dimension"
		}
		require(covarianceAfter.isEmpty() || covarianceAfter.size == stateDimension * stateDimension) {
			"Estimator covariance-after matrix has the wrong dimension"
		}
		require(processNoise.isEmpty() || processNoise.size == stateDimension * stateDimension) {
			"Estimator process-noise matrix has the wrong dimension"
		}
		require((measurementEastM == null) == (measurementNorthM == null)) {
			"Estimator measurement ENU coordinates are atomic"
		}
		require(measurementEastM == null || measurementEastM.isFinite() && measurementNorthM!!.isFinite()) {
			"Estimator measurement must be finite"
		}
		require(measurementCovariance.isEmpty() || measurementCovariance.size == 4) {
			"Estimator measurement covariance must be a 2 by 2 matrix"
		}
		require(stateBefore.all(Double::isFinite) && stateAfter.all(Double::isFinite)) {
			"Estimator state must be finite"
		}
		require(covarianceBefore.all(Double::isFinite) && covarianceAfter.all(Double::isFinite) &&
			processNoise.all(Double::isFinite) && measurementCovariance.all(Double::isFinite) &&
			innovation.all(Double::isFinite)) {
			"Estimator matrices and innovation must be finite"
		}
		require(normalizedInnovationSquared == null ||
			normalizedInnovationSquared.isFinite() && normalizedInnovationSquared >= 0.0) {
			"Normalized innovation squared must be finite and non-negative"
		}
		require(correlationId?.isNotBlank() != false) { "Correlation id must not be blank when present" }
	}
}

enum class ResearchControlDigestKind {
	/** A hash over the legacy V1 segmentation replay output retained as a regression oracle. */
	LEGACY_V1_REPLAY,
	CONTROL_TRACE_REPLAY,
	HORIZONTAL_ESTIMATOR_REPLAY,
}

/**
 * Versioned digest captured beside a replay result. The contract carries a caller-supplied digest;
 * it never silently chooses a cryptographic algorithm or claims end-to-end transport integrity.
 */
data class ResearchControlDigestRecord(
	val kind: ResearchControlDigestKind,
	val digestAlgorithm: String,
	val digest: String,
	val logicalTrackingId: String? = null,
	val envelopeCount: Long,
	val firstSequence: Long? = null,
	val lastSequence: Long? = null,
) : ResearchEvidenceRecord {
	init {
		require(digestAlgorithm.isNotBlank()) { "Digest algorithm must not be blank" }
		require(digest.isNotBlank()) { "Digest value must not be blank" }
		require(logicalTrackingId?.isNotBlank() != false) { "Logical tracking id must not be blank when present" }
		require(envelopeCount >= 0L) { "Digest envelope count must be non-negative" }
		require((firstSequence == null) == (lastSequence == null)) {
			"Digest sequence bounds must both be present or absent"
		}
		require(lastSequence == null || firstSequence!! >= 0L && lastSequence >= firstSequence) {
			"Digest sequence bounds must be non-negative and ordered"
		}
		require((envelopeCount == 0L) == (firstSequence == null)) {
			"Digest sequence bounds must be absent exactly when the evidence set is empty"
		}
	}
}

/** A single ordered V2 control event projected from a generic control evidence envelope. */
data class ResearchOrderedControlEvent(
	val traceIdentity: ResearchTraceIdentity,
	val sequence: Long,
	val clockDomain: ResearchClockDomain,
	val privacyClass: ResearchPrivacyClass,
	val schemaVersion: Int,
	val event: ResearchControlEventRecord,
)

/** Convenience projection for control recorders/replayers that need sequence and clock together. */
fun ResearchEvidenceEnvelope.asOrderedControlEventOrNull(): ResearchOrderedControlEvent? =
	(record as? ResearchControlEventRecord)?.let { event ->
		ResearchOrderedControlEvent(identity, sequence, clockDomain, privacyClass, schemaVersion, event)
	}
