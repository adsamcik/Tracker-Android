package com.adsamcik.tracker.stats.api.research

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier

/**
 * Stable, replay-only evidence contract shared by altitude and segmentation work.
 *
 * These records are deliberately plain Kotlin values. They do not enable on-device capture,
 * persistence, upload, or analytics; callers must separately provide consent, encryption and a
 * bounded storage policy before any live recorder is wired to a product path.
 */
const val RESEARCH_EVIDENCE_SCHEMA_VERSION: Int = 1

/** Identifies one bounded trace and its optional run/session context. */
data class ResearchTraceIdentity(
	val traceId: String,
	val runId: String? = null,
	val sessionId: String? = null,
) {
	init {
		require(traceId.isNotBlank()) { "Trace id must not be blank" }
		require(runId?.isNotBlank() != false) { "Run id must not be blank when present" }
		require(sessionId?.isNotBlank() != false) { "Session id must not be blank when present" }
	}
}

/** A monotonic source belongs only to one domain; it is never compared across a boundary. */
data class ResearchClockDomain(
	val id: String,
	val kind: Kind,
	val bootId: String? = null,
) {
	enum class Kind {
		ANDROID_ELAPSED_REALTIME,
		EXTERNAL_MONOTONIC,
		UNKNOWN,
	}

	init {
		require(id.isNotBlank()) { "Clock-domain id must not be blank" }
	}
}

enum class ResearchPrivacyClass {
	/** No coordinate, sensor fingerprint, or raw source data is present. */
	NONE,
	/** Explicitly encrypted, consented research evidence; never ordinary telemetry. */
	ENCRYPTED_RESEARCH,
	/** The source's privacy posture is unknown, so replay completeness cannot be claimed. */
	UNKNOWN,
}

enum class ResearchLifecycleBoundary {
	START,
	PAUSE,
	RESUME,
	RESTART,
	REBOOT,
	STOP,
}

/** Declares exactly what a trace may contain, rather than inferring capabilities from missing data. */
data class ResearchEvidenceCapabilities(
	val rawPressureEvents: Boolean = false,
	val pressureAggregateWindows: Boolean = false,
	val altitudeConversionOutcomes: Boolean = false,
	val altitudeEstimatorDecisions: Boolean = false,
	val canonicalSegmentationObservations: Boolean = false,
	val segmentationReducerOutputs: Boolean = false,
	val truthMarkers: Boolean = false,
)

/** A contiguous sequence span lost before the recorder could preserve its contents. */
data class ResearchLossRange(
	val firstSequence: Long,
	val lastSequence: Long,
	val reason: Reason,
) {
	enum class Reason {
		BUFFER_OVERFLOW,
		SOURCE_UNAVAILABLE,
		PRIVACY_SUPPRESSED,
		UNKNOWN,
	}

	init {
		require(firstSequence >= 0L) { "Loss sequence must be non-negative" }
		require(lastSequence >= firstSequence) { "Loss range must be ordered" }
	}

	/** Saturates only for the mathematically unrepresentable full-Long sequence span. */
	val count: Long
		get() {
			val delta = lastSequence - firstSequence
			return if (delta == Long.MAX_VALUE) Long.MAX_VALUE else delta + 1L
		}
}

/**
 * One versioned research event. [sequence] is trace-scoped and monotonic; it is not a UUID.
 */
data class ResearchEvidenceEnvelope(
	val identity: ResearchTraceIdentity,
	val sequence: Long,
	val clockDomain: ResearchClockDomain,
	val privacyClass: ResearchPrivacyClass,
	val algorithmVersions: Map<String, String> = emptyMap(),
	val capabilities: ResearchEvidenceCapabilities = ResearchEvidenceCapabilities(),
	val lifecycleBoundary: ResearchLifecycleBoundary? = null,
	val lossRanges: List<ResearchLossRange> = emptyList(),
	/** Present only on a final envelope; ordinary historical exports must leave it absent. */
	val terminalIntegrity: ResearchTerminalIntegrityRecord? = null,
	val record: ResearchEvidenceRecord,
	val schemaVersion: Int = RESEARCH_EVIDENCE_SCHEMA_VERSION,
) {
	init {
		require(sequence >= 0L) { "Evidence sequence must be non-negative" }
		require(schemaVersion > 0) { "Evidence schema version must be positive" }
		require(algorithmVersions.keys.none(String::isBlank)) { "Algorithm version key must not be blank" }
		require(algorithmVersions.values.none(String::isBlank)) { "Algorithm version must not be blank" }
		require(lossRanges.areDisjoint()) { "Evidence loss ranges must not overlap" }
	}
}

sealed interface ResearchEvidenceRecord

/**
 * Final trace accounting. Counts are explicit so a decoder never infers
 * completeness from a missing event type or an absent capability flag.
 */
data class ResearchTerminalIntegrityRecord(
	val envelopeCount: Long,
	val firstSequence: Long?,
	val lastSequence: Long?,
	val lossRangeCount: Long,
	val lostEventCount: Long,
	val complete: Boolean,
) : ResearchEvidenceRecord {
	init {
		require(envelopeCount >= 0L) { "Terminal envelope count must be non-negative" }
		require(lossRangeCount >= 0L) { "Terminal loss-range count must be non-negative" }
		require(lostEventCount >= 0L) { "Terminal loss count must be non-negative" }
		require((firstSequence == null) == (lastSequence == null)) {
			"Terminal sequence bounds must both be present or absent"
		}
		require(lastSequence == null || lastSequence >= firstSequence!!) {
			"Terminal sequence bounds must be ordered"
		}
		require((envelopeCount == 0L) == (firstSequence == null)) {
			"Terminal sequence bounds must be absent exactly when the trace is empty"
		}
	}
}

/** The hardware identity relevant to a pressure trace, without an automatically collected fingerprint. */
data class PressureSensorDescriptorRecord(
	val sensorType: String,
	val reportedName: String? = null,
	val reportingMode: String? = null,
) : ResearchEvidenceRecord {
	init {
		require(sensorType.isNotBlank()) { "Pressure sensor type must not be blank" }
	}
}

enum class PressureValidityDecision {
	ACCEPTED,
	REJECTED_NON_FINITE,
	REJECTED_OUT_OF_ORDER,
	REJECTED_SOURCE_RESET,
	REJECTED_UNKNOWN,
}

enum class PressureCallbackStatus {
	RECEIVED,
	DROPPED_BEFORE_CALLBACK,
	DROPPED_AFTER_CALLBACK,
	SOURCE_RESET,
	UNKNOWN,
}

data class RawPressureEventRecord(
	val sourceSequence: Long,
	val sourceElapsedNanos: Long,
	val receiptEpochMs: Long? = null,
	val receiptElapsedNanos: Long? = null,
	val pressureHpa: Double?,
	val validity: PressureValidityDecision,
	val callbackStatus: PressureCallbackStatus = PressureCallbackStatus.RECEIVED,
) : ResearchEvidenceRecord {
	init {
		require(sourceSequence >= 0L) { "Pressure sequence must be non-negative" }
		require(sourceElapsedNanos >= 0L) { "Pressure elapsed time must be non-negative" }
		require(receiptElapsedNanos == null || receiptElapsedNanos >= 0L) {
			"Pressure receipt elapsed time must be non-negative"
		}
	}
}

/** One cycle aggregate and the contiguous raw-event span from which it was computed. */
data class PressureAggregateWindowRecord(
	val sourceFirstSequence: Long,
	val sourceLastSequence: Long,
	val windowStartElapsedNanos: Long,
	val windowEndElapsedNanos: Long,
	val effectiveSourceElapsedNanos: Long? = null,
	val sourceEventCount: Int = 0,
	val acceptedEventCount: Int = 0,
	val rejectedEventCount: Int = 0,
	val gapBeforeNanos: Long? = null,
	val aggregatePressureHpa: Double?,
	val minimumPressureHpa: Double? = null,
	val maximumPressureHpa: Double? = null,
	val standardDeviationHpa: Double? = null,
	val aggregationVersion: String = "unknown",
) : ResearchEvidenceRecord {
	init {
		require(sourceFirstSequence >= 0L) { "Aggregate source sequence must be non-negative" }
		require(sourceLastSequence >= sourceFirstSequence) { "Aggregate source range must be ordered" }
		require(windowStartElapsedNanos >= 0L) { "Aggregate start must be non-negative" }
		require(windowEndElapsedNanos >= windowStartElapsedNanos) { "Aggregate window must be ordered" }
		require(effectiveSourceElapsedNanos == null || effectiveSourceElapsedNanos >= 0L) {
			"Aggregate effective source time must be non-negative"
		}
		require(sourceEventCount >= 0 && acceptedEventCount >= 0 && rejectedEventCount >= 0) {
			"Aggregate event counts must be non-negative"
		}
		require(acceptedEventCount + rejectedEventCount <= sourceEventCount) {
			"Aggregate accepted/rejected counts exceed source event count"
		}
		require(gapBeforeNanos == null || gapBeforeNanos >= 0L) { "Aggregate gap must be non-negative" }
		require(aggregationVersion.isNotBlank()) { "Aggregate version must not be blank" }
	}
}

enum class AltitudeConversionKind {
	SUCCESS_MSL,
	NO_ALTITUDE,
	NO_MSL_OUTPUT,
	INVALID_INPUT,
	IO_FAILURE,
	UNEXPECTED_FAILURE,
}

/** Provider ellipsoid evidence and its separately classified MSL conversion outcome. */
data class AltitudeLocationObservationRecord(
	val sourceSequence: Long,
	val eventEpochMs: Long?,
	val acquisitionElapsedNanos: Long?,
	val receiptEpochMs: Long? = null,
	val receiptElapsedNanos: Long? = null,
	val providerIdentity: String? = null,
	val ellipsoidAltitudeM: Double?,
	val verticalAccuracyM: Double? = null,
	val conversion: AltitudeConversionKind,
	val convertedMslAltitudeM: Double? = null,
	val conversionModelVersion: String? = null,
	val conversionPath: String? = null,
) : ResearchEvidenceRecord {
	init {
		require(sourceSequence >= 0L) { "Altitude source sequence must be non-negative" }
		require(acquisitionElapsedNanos == null || acquisitionElapsedNanos >= 0L) {
			"Altitude elapsed time must be non-negative"
		}
		require(receiptElapsedNanos == null || receiptElapsedNanos >= 0L) {
			"Altitude receipt elapsed time must be non-negative"
		}
		require((conversion == AltitudeConversionKind.SUCCESS_MSL) == (convertedMslAltitudeM != null)) {
			"Only a successful conversion may carry MSL altitude"
		}
	}
}

enum class CalibrationDecision {
	PROPOSED,
	ACCEPTED,
	REJECTED,
	RESET,
}

data class AltitudeCalibrationRecord(
	val decision: CalibrationDecision,
	val sourceFirstSequence: Long,
	val sourceLastSequence: Long,
	val calibrationVersion: String,
	val lineageId: String? = null,
	val priorLineageId: String? = null,
	val proposedBaselineM: Double? = null,
	val resultingBaselineM: Double? = null,
	val sourceClockDomainId: String? = null,
	val reason: String? = null,
) : ResearchEvidenceRecord {
	init {
		require(sourceFirstSequence >= 0L) { "Calibration source sequence must be non-negative" }
		require(sourceLastSequence >= sourceFirstSequence) { "Calibration source range must be ordered" }
		require(calibrationVersion.isNotBlank()) { "Calibration version must not be blank" }
	}
}

enum class AltitudeFilterDecision {
	PREDICTION,
	GPS_UPDATE,
	BAROMETER_UPDATE,
	REJECTED_UPDATE,
	RESET,
}

/** Diagnostic state stays in research-only evidence rather than a production location row. */
data class AltitudeFilterRecord(
	val decision: AltitudeFilterDecision,
	val estimatorVersion: String,
	val eventEpochMs: Long? = null,
	val sourceElapsedNanos: Long? = null,
	val deltaNanos: Long? = null,
	val altitudeEstimateM: Double?,
	val verticalVelocityMps: Double?,
	val stateBefore: List<Double> = emptyList(),
	val stateAfter: List<Double> = emptyList(),
	val covarianceBefore: List<Double> = emptyList(),
	val covarianceAfter: List<Double> = emptyList(),
	val processNoise: List<Double> = emptyList(),
	val measurementNoise: List<Double> = emptyList(),
	val innovation: Double? = null,
	val innovationVariance: Double? = null,
	val sourceFirstSequence: Long? = null,
	val sourceLastSequence: Long? = null,
	val reason: String? = null,
) : ResearchEvidenceRecord {
	init {
		require(estimatorVersion.isNotBlank()) { "Estimator version must not be blank" }
		require(sourceElapsedNanos == null || sourceElapsedNanos >= 0L) {
			"Filter source elapsed time must be non-negative"
		}
		require(deltaNanos == null || deltaNanos >= 0L) { "Filter delta must be non-negative" }
		require(sourceFirstSequence == null || sourceFirstSequence >= 0L) { "Filter source sequence must be non-negative" }
		require(sourceLastSequence == null || sourceFirstSequence != null && sourceLastSequence >= sourceFirstSequence) {
			"Filter source range must be complete and ordered"
		}
	}
}

data class ResearchLifecycleRecord(
	val boundary: ResearchLifecycleBoundary,
	val detail: String? = null,
) : ResearchEvidenceRecord

data class ResearchTraceLossRecord(
	val ranges: List<ResearchLossRange>,
) : ResearchEvidenceRecord {
	init {
		require(ranges.isNotEmpty()) { "Trace-loss record must describe at least one range" }
		require(ranges.areDisjoint()) { "Trace-loss ranges must not overlap" }
	}
}

/** An observed time gap; it is not silently converted into stillness or movement evidence. */
data class ResearchGapRecord(
	val startEpochMs: Long?,
	val endEpochMs: Long?,
	val clockDomainId: String,
	val reason: String? = null,
) : ResearchEvidenceRecord {
	init {
		require(clockDomainId.isNotBlank()) { "Gap clock-domain id must not be blank" }
		require(endEpochMs == null || startEpochMs != null && endEpochMs >= startEpochMs) {
			"Gap bounds must be complete and ordered"
		}
	}
}

data class ResearchTruthMarkerRecord(
	val markerId: String,
	val label: String,
	val eventEpochMs: Long?,
) : ResearchEvidenceRecord {
	init {
		require(markerId.isNotBlank()) { "Truth marker id must not be blank" }
		require(label.isNotBlank()) { "Truth marker label must not be blank" }
	}
}

/** A declared human correction belongs in evidence, not as an inferred classifier output. */
data class ResearchManualCorrectionRecord(
	val correctionId: String,
	val boundaryStartEpochMs: Long?,
	val boundaryEndEpochMs: Long?,
	val mode: String? = null,
	val reason: String? = null,
) : ResearchEvidenceRecord {
	init {
		require(correctionId.isNotBlank()) { "Manual correction id must not be blank" }
		require(boundaryEndEpochMs == null || boundaryStartEpochMs != null && boundaryEndEpochMs >= boundaryStartEpochMs) {
			"Manual correction boundary must be complete and ordered"
		}
	}
}

/**
 * Lossless canonical segmentation input. Nullable fields mean "not observed", never negative
 * evidence. The legacy V1 adapter is the only layer allowed to drop locationless observations.
 */
data class CanonicalSegmentationObservation(
	val eventEpochMs: Long,
	val acquisitionElapsedNanos: Long?,
	val receiptEpochMs: Long?,
	val receiptElapsedNanos: Long?,
	val sourceSequence: Long,
	val clockDomainId: String,
	val identityScopes: Set<String> = emptySet(),
	val policyTier: PolicyTier? = null,
	val lifecycleBoundary: ResearchLifecycleBoundary? = null,
	val capabilityFlags: Set<String> = emptySet(),
	val rawLocation: CanonicalLocationEvidence? = null,
	val curatedLocation: CanonicalLocationEvidence? = null,
	val locationDecision: CanonicalLocationDecision? = null,
	val stepEvidence: CanonicalStepEvidence? = null,
	val activityEvidence: CanonicalActivityEvidence? = null,
) : ResearchEvidenceRecord {
	init {
		require(sourceSequence >= 0L) { "Canonical source sequence must be non-negative" }
		require(clockDomainId.isNotBlank()) { "Canonical clock-domain id must not be blank" }
		require(acquisitionElapsedNanos == null || acquisitionElapsedNanos >= 0L) {
			"Canonical acquisition elapsed time must be non-negative"
		}
		require(receiptElapsedNanos == null || receiptElapsedNanos >= 0L) {
			"Canonical receipt elapsed time must be non-negative"
		}
	}
}

data class CanonicalLocationEvidence(
	val latitudeE7: Int?,
	val longitudeE7: Int?,
	val horizontalAccuracyM: Float?,
	val speedMps: Float?,
	val speedAccuracyMps: Float? = null,
	val verticalAccuracyM: Float? = null,
	val sourceIdentity: String? = null,
	val batchIndex: Int? = null,
	val batchSize: Int? = null,
	val acquisitionMode: String? = null,
	val requestPriority: String? = null,
	val permissionPrecision: String? = null,
) {
	init {
		require((latitudeE7 == null) == (longitudeE7 == null)) { "Canonical location coordinates are atomic" }
		require(latitudeE7 == null || latitudeE7 in -900_000_000..900_000_000) {
			"Canonical latitude must be within Earth bounds"
		}
		require(longitudeE7 == null || longitudeE7 in -1_800_000_000..1_800_000_000) {
			"Canonical longitude must be within Earth bounds"
		}
		require(horizontalAccuracyM == null || horizontalAccuracyM.isFinite() && horizontalAccuracyM >= 0f) {
			"Canonical horizontal accuracy must be finite and non-negative"
		}
		require(verticalAccuracyM == null || verticalAccuracyM.isFinite() && verticalAccuracyM >= 0f) {
			"Canonical vertical accuracy must be finite and non-negative"
		}
		require(speedMps == null || speedMps.isFinite() && speedMps >= 0f) {
			"Canonical speed must be finite and non-negative"
		}
		require(speedAccuracyMps == null || speedAccuracyMps.isFinite() && speedAccuracyMps >= 0f) {
			"Canonical speed accuracy must be finite and non-negative"
		}
		require(batchIndex == null || batchIndex >= 0) { "Batch index must be non-negative" }
		require(batchSize == null || batchSize > 0) { "Batch size must be positive" }
		require(batchIndex == null || batchSize == null || batchIndex < batchSize) {
			"Batch index must be less than batch size"
		}
	}
}

enum class CanonicalLocationDecisionKind {
	ACCEPTED,
	REJECTED,
	MISSING,
}

data class CanonicalLocationDecision(
	val kind: CanonicalLocationDecisionKind,
	val reason: String? = null,
	val sourceEventId: String? = null,
	val decisionEpochMs: Long? = null,
	val algorithmVersion: String? = null,
)

data class CanonicalStepEvidence(
	val delta: Int?,
	val sourceFirstSequence: Long? = null,
	val sourceLastSequence: Long? = null,
	val totalSinceReset: Long? = null,
	val reset: Boolean = false,
) {
	init {
		require(sourceFirstSequence == null || sourceFirstSequence >= 0L) { "Step source sequence must be non-negative" }
		require(sourceLastSequence == null || sourceFirstSequence != null && sourceLastSequence >= sourceFirstSequence) {
			"Step source range must be complete and ordered"
		}
	}
}

data class CanonicalActivityEvidence(
	val type: DetectedActivityType?,
	val confidence: Int? = null,
	val sourceEpochMs: Long? = null,
	val fresh: Boolean? = null,
	val sourceTimeCapability: SourceTimeCapability = SourceTimeCapability.UNKNOWN,
)

enum class SourceTimeCapability {
	PRESENT,
	DISCARDED_UPSTREAM,
	UNAVAILABLE,
	UNKNOWN,
}

data class SegmentationReducerOutputRecord(
	val boundaryStartEpochMs: Long?,
	val boundaryEndEpochMs: Long?,
	val decisionEpochMs: Long,
	val stateOrMode: String = "UNKNOWN",
	val uncertainty: String? = null,
	val coverage: String? = null,
	val reason: String? = null,
	val provenance: String? = null,
	val algorithmVersion: String,
	val configurationVersion: String,
) : ResearchEvidenceRecord {
	init {
		require(boundaryEndEpochMs == null || boundaryStartEpochMs != null && boundaryEndEpochMs >= boundaryStartEpochMs) {
			"Reducer boundary must be complete and ordered"
		}
		require(algorithmVersion.isNotBlank()) { "Reducer algorithm version must not be blank" }
		require(configurationVersion.isNotBlank()) { "Reducer configuration version must not be blank" }
	}
}

private fun List<ResearchLossRange>.areDisjoint(): Boolean {
	if (size < 2) return true
	val ordered = sortedWith(compareBy<ResearchLossRange> { it.firstSequence }.thenBy { it.lastSequence })
	return ordered.zipWithNext().all { (left, right) -> left.lastSequence < right.firstSequence }
}
