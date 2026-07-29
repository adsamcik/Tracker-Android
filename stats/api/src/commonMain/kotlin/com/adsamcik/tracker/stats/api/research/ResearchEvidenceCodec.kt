package com.adsamcik.tracker.stats.api.research

/**
 * Typed, deterministic wire adapter for the research evidence contract.
 *
 * This is deliberately an in-memory schema boundary, not a file/database
 * writer or capture facility. A future consented encrypted transport may
 * serialize [ResearchEvidenceWireEnvelope], but must retain this versioned
 * mapping and its conformance tests.
 */
const val RESEARCH_EVIDENCE_SCHEMA_DOCUMENT = "tools/research-trace/RESEARCH_EVIDENCE_SCHEMA.md"

data class ResearchEvidenceWireEnvelope(
	val schemaVersion: Int,
	val identity: ResearchTraceIdentity,
	val sequence: Long,
	val clockDomain: ResearchClockDomain,
	val privacyClass: ResearchPrivacyClass,
	val algorithmVersions: Map<String, String>,
	val capabilities: ResearchEvidenceCapabilities,
	val lifecycleBoundary: ResearchLifecycleBoundary?,
	val lossRanges: List<ResearchLossRange>,
	val terminalIntegrity: ResearchTerminalIntegrityRecord?,
	val record: ResearchEvidenceWireRecord,
)

sealed interface ResearchEvidenceWireRecord

data class PressureSensorDescriptorWire(
	val sensorType: String,
	val reportedName: String?,
	val reportingMode: String?,
) : ResearchEvidenceWireRecord

data class RawPressureEventWire(
	val sourceSequence: Long,
	val sourceElapsedNanos: Long,
	val receiptEpochMs: Long?,
	val receiptElapsedNanos: Long?,
	val pressureHpa: Double?,
	val validity: PressureValidityDecision,
	val callbackStatus: PressureCallbackStatus,
) : ResearchEvidenceWireRecord

data class PressureAggregateWindowWire(
	val sourceFirstSequence: Long,
	val sourceLastSequence: Long,
	val windowStartElapsedNanos: Long,
	val windowEndElapsedNanos: Long,
	val effectiveSourceElapsedNanos: Long?,
	val sourceEventCount: Int,
	val acceptedEventCount: Int,
	val rejectedEventCount: Int,
	val gapBeforeNanos: Long?,
	val aggregatePressureHpa: Double?,
	val minimumPressureHpa: Double?,
	val maximumPressureHpa: Double?,
	val standardDeviationHpa: Double?,
	val aggregationVersion: String,
) : ResearchEvidenceWireRecord

data class AltitudeLocationObservationWire(
	val sourceSequence: Long,
	val eventEpochMs: Long?,
	val acquisitionElapsedNanos: Long?,
	val receiptEpochMs: Long?,
	val receiptElapsedNanos: Long?,
	val providerIdentity: String?,
	val ellipsoidAltitudeM: Double?,
	val verticalAccuracyM: Double?,
	val conversion: AltitudeConversionKind,
	val convertedMslAltitudeM: Double?,
	val conversionModelVersion: String?,
	val conversionPath: String?,
) : ResearchEvidenceWireRecord

data class AltitudeCalibrationWire(
	val decision: CalibrationDecision,
	val sourceFirstSequence: Long,
	val sourceLastSequence: Long,
	val calibrationVersion: String,
	val lineageId: String?,
	val priorLineageId: String?,
	val proposedBaselineM: Double?,
	val resultingBaselineM: Double?,
	val sourceClockDomainId: String?,
	val reason: String?,
) : ResearchEvidenceWireRecord

data class AltitudeFilterWire(
	val decision: AltitudeFilterDecision,
	val estimatorVersion: String,
	val eventEpochMs: Long?,
	val sourceElapsedNanos: Long?,
	val deltaNanos: Long?,
	val altitudeEstimateM: Double?,
	val verticalVelocityMps: Double?,
	val stateBefore: List<Double>,
	val stateAfter: List<Double>,
	val covarianceBefore: List<Double>,
	val covarianceAfter: List<Double>,
	val processNoise: List<Double>,
	val measurementNoise: List<Double>,
	val innovation: Double?,
	val innovationVariance: Double?,
	val sourceFirstSequence: Long?,
	val sourceLastSequence: Long?,
	val reason: String?,
) : ResearchEvidenceWireRecord

data class LifecycleWire(val boundary: ResearchLifecycleBoundary, val detail: String?) : ResearchEvidenceWireRecord
data class TraceLossWire(val ranges: List<ResearchLossRange>) : ResearchEvidenceWireRecord
data class GapWire(
	val startEpochMs: Long?,
	val endEpochMs: Long?,
	val clockDomainId: String,
	val reason: String?,
	val logicalTrackingId: String? = null,
	val startElapsedNanos: Long? = null,
	val endElapsedNanos: Long? = null,
) : ResearchEvidenceWireRecord

data class ControlEventWire(
	val logicalTrackingId: String,
	val eventEpochMs: Long?,
	val eventElapsedNanos: Long?,
	val kind: ResearchControlEventKind,
	val reason: String?,
	val correlationId: String?,
	val payload: Map<String, String>,
) : ResearchEvidenceWireRecord

data class TrackingLifecycleWire(
	val logicalTrackingId: String,
	val transition: ResearchTrackingLifecycleTransition,
	val trackingMode: ResearchTrackingMode,
	val eventEpochMs: Long?,
	val eventElapsedNanos: Long?,
	val stopCause: ResearchTrackingStopCause?,
	val reason: String?,
	val correlationId: String?,
	val resumedFromLogicalTrackingId: String?,
) : ResearchEvidenceWireRecord

data class AcquisitionWire(
	val logicalTrackingId: String,
	val requestId: String,
	val eventEpochMs: Long?,
	val eventElapsedNanos: Long?,
	val desired: ResearchAcquisitionConfiguration,
	val applied: ResearchAcquisitionConfiguration?,
	val outcome: ResearchAcquisitionApplyOutcome,
	val reason: String?,
	val correlationId: String?,
) : ResearchEvidenceWireRecord

data class HorizontalEstimatorWire(
	val logicalTrackingId: String,
	val estimatorVersion: String,
	val decision: ResearchHorizontalEstimatorDecision,
	val eventEpochMs: Long?,
	val sourceElapsedNanos: Long?,
	val deltaNanos: Long?,
	val sourceSequence: Long?,
	val stateDimension: Int,
	val stateBefore: List<Double>,
	val stateAfter: List<Double>,
	val covarianceBefore: List<Double>,
	val covarianceAfter: List<Double>,
	val processNoise: List<Double>,
	val measurementEastM: Double?,
	val measurementNorthM: Double?,
	val measurementCovariance: List<Double>,
	val innovation: List<Double>,
	val normalizedInnovationSquared: Double?,
	val accepted: Boolean?,
	val reason: String?,
	val correlationId: String?,
) : ResearchEvidenceWireRecord

data class ControlDigestWire(
	val kind: ResearchControlDigestKind,
	val digestAlgorithm: String,
	val digest: String,
	val logicalTrackingId: String?,
	val envelopeCount: Long,
	val firstSequence: Long?,
	val lastSequence: Long?,
) : ResearchEvidenceWireRecord

data class TruthMarkerWire(val markerId: String, val label: String, val eventEpochMs: Long?) : ResearchEvidenceWireRecord
data class ManualCorrectionWire(
	val correctionId: String,
	val boundaryStartEpochMs: Long?,
	val boundaryEndEpochMs: Long?,
	val mode: String?,
	val reason: String?,
) : ResearchEvidenceWireRecord

data class CanonicalSegmentationObservationWire(
	val eventEpochMs: Long,
	val acquisitionElapsedNanos: Long?,
	val receiptEpochMs: Long?,
	val receiptElapsedNanos: Long?,
	val sourceSequence: Long,
	val clockDomainId: String,
	val identityScopes: Set<String>,
	val policyTier: com.adsamcik.tracker.stats.api.PolicyTier?,
	val lifecycleBoundary: ResearchLifecycleBoundary?,
	val capabilityFlags: Set<String>,
	val rawLocation: CanonicalLocationEvidence?,
	val curatedLocation: CanonicalLocationEvidence?,
	val locationDecision: CanonicalLocationDecision?,
	val stepEvidence: CanonicalStepEvidence?,
	val activityEvidence: CanonicalActivityEvidence?,
) : ResearchEvidenceWireRecord

data class SegmentationReducerOutputWire(
	val boundaryStartEpochMs: Long?,
	val boundaryEndEpochMs: Long?,
	val decisionEpochMs: Long,
	val stateOrMode: String,
	val uncertainty: String?,
	val coverage: String?,
	val reason: String?,
	val provenance: String?,
	val algorithmVersion: String,
	val configurationVersion: String,
) : ResearchEvidenceWireRecord

data class TerminalIntegrityWire(
	val envelopeCount: Long,
	val firstSequence: Long?,
	val lastSequence: Long?,
	val lossRangeCount: Long,
	val lostEventCount: Long,
	val complete: Boolean,
) : ResearchEvidenceWireRecord

/** The supported domain <-> wire conversion for V1 historical and V2 control evidence. */
object ResearchEvidenceCodec {
	fun encode(envelope: ResearchEvidenceEnvelope): ResearchEvidenceWireEnvelope {
		require(isResearchEvidenceSchemaSupported(envelope.schemaVersion)) {
			"Unsupported research evidence schema ${envelope.schemaVersion}"
		}
		return ResearchEvidenceWireEnvelope(
			schemaVersion = envelope.schemaVersion,
			identity = envelope.identity,
			sequence = envelope.sequence,
			clockDomain = envelope.clockDomain,
			privacyClass = envelope.privacyClass,
			algorithmVersions = envelope.algorithmVersions.toSortedMap(),
			capabilities = envelope.capabilities,
			lifecycleBoundary = envelope.lifecycleBoundary,
			lossRanges = envelope.lossRanges,
			terminalIntegrity = envelope.terminalIntegrity,
			record = envelope.record.toWire(),
		)
	}

	fun decode(wire: ResearchEvidenceWireEnvelope): ResearchEvidenceEnvelope {
		require(isResearchEvidenceSchemaSupported(wire.schemaVersion)) {
			"Unsupported research evidence schema ${wire.schemaVersion}"
		}
		return ResearchEvidenceEnvelope(
			identity = wire.identity,
			sequence = wire.sequence,
			clockDomain = wire.clockDomain,
			privacyClass = wire.privacyClass,
			algorithmVersions = wire.algorithmVersions,
			capabilities = wire.capabilities,
			lifecycleBoundary = wire.lifecycleBoundary,
			lossRanges = wire.lossRanges,
			terminalIntegrity = wire.terminalIntegrity,
			record = wire.record.toDomain(),
			schemaVersion = wire.schemaVersion,
		)
	}

	private fun ResearchEvidenceRecord.toWire(): ResearchEvidenceWireRecord = when (this) {
		is PressureSensorDescriptorRecord -> PressureSensorDescriptorWire(sensorType, reportedName, reportingMode)
		is RawPressureEventRecord -> RawPressureEventWire(
			sourceSequence, sourceElapsedNanos, receiptEpochMs, receiptElapsedNanos, pressureHpa, validity, callbackStatus,
		)
		is PressureAggregateWindowRecord -> PressureAggregateWindowWire(
			sourceFirstSequence, sourceLastSequence, windowStartElapsedNanos, windowEndElapsedNanos,
			effectiveSourceElapsedNanos, sourceEventCount, acceptedEventCount, rejectedEventCount, gapBeforeNanos,
			aggregatePressureHpa, minimumPressureHpa, maximumPressureHpa, standardDeviationHpa, aggregationVersion,
		)
		is AltitudeLocationObservationRecord -> AltitudeLocationObservationWire(
			sourceSequence, eventEpochMs, acquisitionElapsedNanos, receiptEpochMs, receiptElapsedNanos,
			providerIdentity, ellipsoidAltitudeM, verticalAccuracyM, conversion, convertedMslAltitudeM,
			conversionModelVersion, conversionPath,
		)
		is AltitudeCalibrationRecord -> AltitudeCalibrationWire(
			decision, sourceFirstSequence, sourceLastSequence, calibrationVersion, lineageId, priorLineageId,
			proposedBaselineM, resultingBaselineM, sourceClockDomainId, reason,
		)
		is AltitudeFilterRecord -> AltitudeFilterWire(
			decision, estimatorVersion, eventEpochMs, sourceElapsedNanos, deltaNanos, altitudeEstimateM,
			verticalVelocityMps, stateBefore, stateAfter, covarianceBefore, covarianceAfter, processNoise,
			measurementNoise, innovation, innovationVariance, sourceFirstSequence, sourceLastSequence, reason,
		)
		is ResearchLifecycleRecord -> LifecycleWire(boundary, detail)
		is ResearchTraceLossRecord -> TraceLossWire(ranges)
		is ResearchGapRecord -> GapWire(
			startEpochMs, endEpochMs, clockDomainId, reason, logicalTrackingId, startElapsedNanos, endElapsedNanos,
		)
		is ResearchControlEventRecord -> ControlEventWire(
			logicalTrackingId, eventEpochMs, eventElapsedNanos, kind, reason, correlationId, payload.toSortedMap(),
		)
		is ResearchTrackingLifecycleRecord -> TrackingLifecycleWire(
			logicalTrackingId, transition, trackingMode, eventEpochMs, eventElapsedNanos, stopCause, reason,
			correlationId, resumedFromLogicalTrackingId,
		)
		is ResearchAcquisitionRecord -> AcquisitionWire(
			logicalTrackingId, requestId, eventEpochMs, eventElapsedNanos, desired, applied, outcome, reason,
			correlationId,
		)
		is ResearchHorizontalEstimatorRecord -> HorizontalEstimatorWire(
			logicalTrackingId, estimatorVersion, decision, eventEpochMs, sourceElapsedNanos, deltaNanos,
			sourceSequence, stateDimension, stateBefore, stateAfter, covarianceBefore, covarianceAfter,
			processNoise, measurementEastM, measurementNorthM, measurementCovariance, innovation,
			normalizedInnovationSquared, accepted, reason, correlationId,
		)
		is ResearchControlDigestRecord -> ControlDigestWire(
			kind, digestAlgorithm, digest, logicalTrackingId, envelopeCount, firstSequence, lastSequence,
		)
		is ResearchTruthMarkerRecord -> TruthMarkerWire(markerId, label, eventEpochMs)
		is ResearchManualCorrectionRecord -> ManualCorrectionWire(
			correctionId, boundaryStartEpochMs, boundaryEndEpochMs, mode, reason,
		)
		is CanonicalSegmentationObservation -> CanonicalSegmentationObservationWire(
			eventEpochMs, acquisitionElapsedNanos, receiptEpochMs, receiptElapsedNanos, sourceSequence,
			clockDomainId, identityScopes, policyTier, lifecycleBoundary, capabilityFlags, rawLocation,
			curatedLocation, locationDecision, stepEvidence, activityEvidence,
		)
		is SegmentationReducerOutputRecord -> SegmentationReducerOutputWire(
			boundaryStartEpochMs, boundaryEndEpochMs, decisionEpochMs, stateOrMode, uncertainty, coverage,
			reason, provenance, algorithmVersion, configurationVersion,
		)
		is ResearchTerminalIntegrityRecord -> TerminalIntegrityWire(
			envelopeCount, firstSequence, lastSequence, lossRangeCount, lostEventCount, complete,
		)
	}

	private fun ResearchEvidenceWireRecord.toDomain(): ResearchEvidenceRecord = when (this) {
		is PressureSensorDescriptorWire -> PressureSensorDescriptorRecord(sensorType, reportedName, reportingMode)
		is RawPressureEventWire -> RawPressureEventRecord(
			sourceSequence, sourceElapsedNanos, receiptEpochMs, receiptElapsedNanos, pressureHpa, validity, callbackStatus,
		)
		is PressureAggregateWindowWire -> PressureAggregateWindowRecord(
			sourceFirstSequence, sourceLastSequence, windowStartElapsedNanos, windowEndElapsedNanos,
			effectiveSourceElapsedNanos, sourceEventCount, acceptedEventCount, rejectedEventCount, gapBeforeNanos,
			aggregatePressureHpa, minimumPressureHpa, maximumPressureHpa, standardDeviationHpa, aggregationVersion,
		)
		is AltitudeLocationObservationWire -> AltitudeLocationObservationRecord(
			sourceSequence, eventEpochMs, acquisitionElapsedNanos, receiptEpochMs, receiptElapsedNanos,
			providerIdentity, ellipsoidAltitudeM, verticalAccuracyM, conversion, convertedMslAltitudeM,
			conversionModelVersion, conversionPath,
		)
		is AltitudeCalibrationWire -> AltitudeCalibrationRecord(
			decision, sourceFirstSequence, sourceLastSequence, calibrationVersion, lineageId, priorLineageId,
			proposedBaselineM, resultingBaselineM, sourceClockDomainId, reason,
		)
		is AltitudeFilterWire -> AltitudeFilterRecord(
			decision, estimatorVersion, eventEpochMs, sourceElapsedNanos, deltaNanos, altitudeEstimateM,
			verticalVelocityMps, stateBefore, stateAfter, covarianceBefore, covarianceAfter, processNoise,
			measurementNoise, innovation, innovationVariance, sourceFirstSequence, sourceLastSequence, reason,
		)
		is LifecycleWire -> ResearchLifecycleRecord(boundary, detail)
		is TraceLossWire -> ResearchTraceLossRecord(ranges)
		is GapWire -> ResearchGapRecord(
			startEpochMs, endEpochMs, clockDomainId, reason, logicalTrackingId, startElapsedNanos, endElapsedNanos,
		)
		is ControlEventWire -> ResearchControlEventRecord(
			logicalTrackingId, eventEpochMs, eventElapsedNanos, kind, reason, correlationId, payload,
		)
		is TrackingLifecycleWire -> ResearchTrackingLifecycleRecord(
			logicalTrackingId, transition, trackingMode, eventEpochMs, eventElapsedNanos, stopCause, reason,
			correlationId, resumedFromLogicalTrackingId,
		)
		is AcquisitionWire -> ResearchAcquisitionRecord(
			logicalTrackingId, requestId, eventEpochMs, eventElapsedNanos, desired, applied, outcome, reason,
			correlationId,
		)
		is HorizontalEstimatorWire -> ResearchHorizontalEstimatorRecord(
			logicalTrackingId, estimatorVersion, decision, eventEpochMs, sourceElapsedNanos, deltaNanos,
			sourceSequence, stateDimension, stateBefore, stateAfter, covarianceBefore, covarianceAfter,
			processNoise, measurementEastM, measurementNorthM, measurementCovariance, innovation,
			normalizedInnovationSquared, accepted, reason, correlationId,
		)
		is ControlDigestWire -> ResearchControlDigestRecord(
			kind, digestAlgorithm, digest, logicalTrackingId, envelopeCount, firstSequence, lastSequence,
		)
		is TruthMarkerWire -> ResearchTruthMarkerRecord(markerId, label, eventEpochMs)
		is ManualCorrectionWire -> ResearchManualCorrectionRecord(
			correctionId, boundaryStartEpochMs, boundaryEndEpochMs, mode, reason,
		)
		is CanonicalSegmentationObservationWire -> CanonicalSegmentationObservation(
			eventEpochMs, acquisitionElapsedNanos, receiptEpochMs, receiptElapsedNanos, sourceSequence,
			clockDomainId, identityScopes, policyTier, lifecycleBoundary, capabilityFlags, rawLocation,
			curatedLocation, locationDecision, stepEvidence, activityEvidence,
		)
		is SegmentationReducerOutputWire -> SegmentationReducerOutputRecord(
			boundaryStartEpochMs, boundaryEndEpochMs, decisionEpochMs, stateOrMode, uncertainty, coverage,
			reason, provenance, algorithmVersion, configurationVersion,
		)
		is TerminalIntegrityWire -> ResearchTerminalIntegrityRecord(
			envelopeCount, firstSequence, lastSequence, lossRangeCount, lostEventCount, complete,
		)
	}
}
