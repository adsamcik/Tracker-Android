package com.adsamcik.tracker.stats.engine.research

import com.adsamcik.tracker.stats.api.SegmentEvent
import com.adsamcik.tracker.stats.api.SegmentSignal
import com.adsamcik.tracker.stats.api.research.AltitudeLocationObservationRecord
import com.adsamcik.tracker.stats.api.research.CanonicalLocationDecisionKind
import com.adsamcik.tracker.stats.api.research.CanonicalSegmentationObservation
import com.adsamcik.tracker.stats.api.research.CanonicalStepEvidence
import com.adsamcik.tracker.stats.api.research.ResearchClockDomain
import com.adsamcik.tracker.stats.api.research.ResearchEvidenceCapabilities
import com.adsamcik.tracker.stats.api.research.ResearchEvidenceEnvelope
import com.adsamcik.tracker.stats.api.research.ResearchEvidenceRecord
import com.adsamcik.tracker.stats.api.research.ResearchGapRecord
import com.adsamcik.tracker.stats.api.research.ResearchLifecycleBoundary
import com.adsamcik.tracker.stats.api.research.ResearchLossRange
import com.adsamcik.tracker.stats.api.research.ResearchPrivacyClass
import com.adsamcik.tracker.stats.api.research.ResearchTerminalIntegrityRecord
import com.adsamcik.tracker.stats.api.research.ResearchTraceIdentity
import com.adsamcik.tracker.stats.api.research.ResearchTraceLossRecord
import com.adsamcik.tracker.stats.api.research.PressureValidityDecision
import com.adsamcik.tracker.stats.engine.segment.SegmentDetectorConfig
import com.adsamcik.tracker.stats.engine.segment.SessionSegmentDetector

/** Immutable terminal accounting for a locally recorded research trace. */
data class ResearchTraceTerminalIntegrity(
	val envelopeCount: Long,
	val firstSequence: Long?,
	val lastSequence: Long?,
	val lossRangeCount: Long,
	val lostEventCount: Long,
	val complete: Boolean,
) {
	/** Compatibility alias for callers that only need the saturated lost-event total. */
	val lossCount: Long get() = lostEventCount

	fun asRecord(): ResearchTerminalIntegrityRecord = ResearchTerminalIntegrityRecord(
		envelopeCount = envelopeCount,
		firstSequence = firstSequence,
		lastSequence = lastSequence,
		lossRangeCount = lossRangeCount,
		lostEventCount = lostEventCount,
		complete = complete,
	)
}

/**
 * In-memory sink used by tests and offline adapters before any consented encrypted capture exists.
 * It intentionally has no file, database, telemetry, or Android dependency.
 */
class InMemoryResearchEvidenceRecorder(
	private val identity: ResearchTraceIdentity,
) {
	private val records = mutableListOf<ResearchEvidenceEnvelope>()
	private val accountedLossRanges = mutableListOf<ResearchLossRange>()
	private var closed = false

	fun append(envelope: ResearchEvidenceEnvelope) {
		check(!closed) { "Research recorder is already closed" }
		require(envelope.identity == identity) { "Evidence identity must remain trace-scoped" }
		val previous = records.lastOrNull()
		require(previous == null || envelope.sequence > previous.sequence) {
			"Evidence sequence must be strictly increasing"
		}
		val addedLossRanges = envelope.allLossRanges()
		require(addedLossRanges.areDisjoint()) { "Evidence loss ranges must not overlap" }
		require(accountedLossRanges.plus(addedLossRanges).areDisjoint()) {
			"Loss ranges must not overlap earlier trace accounting"
		}
		require(addedLossRanges.none { it.contains(envelope.sequence) || records.any { record -> it.contains(record.sequence) } }) {
			"A loss range may describe only absent evidence sequences"
		}
		val missingFirst = when {
			previous == null -> 0L
			previous.sequence == Long.MAX_VALUE -> null
			else -> previous.sequence + 1L
		}
		if (missingFirst != null && missingFirst < envelope.sequence) {
			require(accountedLossRanges.plus(addedLossRanges).covers(missingFirst, envelope.sequence - 1L)) {
				"Every skipped evidence sequence must have contiguous loss accounting"
			}
		}
		val suppliedTerminal = envelope.terminalRecordOrNull()
		if (suppliedTerminal != null) {
			require(suppliedTerminal.complete) { "A terminal evidence record must declare complete accounting" }
			val expected = integrityFor(
				envelopeCount = records.size.toLong() + 1L,
				firstSequence = records.firstOrNull()?.sequence ?: envelope.sequence,
				lastSequence = envelope.sequence,
				lossRanges = accountedLossRanges + addedLossRanges,
				complete = true,
			)
			require(suppliedTerminal == expected.asRecord()) {
				"Terminal evidence accounting does not match the recorded trace"
			}
		}
		records += envelope
		accountedLossRanges += addedLossRanges
		if (suppliedTerminal != null) closed = true
	}

	fun snapshot(): List<ResearchEvidenceEnvelope> = records.toList()

	fun close(): ResearchTraceTerminalIntegrity {
		closed = true
		return terminalIntegrity()
	}

	fun terminalIntegrity(): ResearchTraceTerminalIntegrity {
		return integrityFor(
			envelopeCount = records.size.toLong(),
			firstSequence = records.firstOrNull()?.sequence,
			lastSequence = records.lastOrNull()?.sequence,
			lossRanges = accountedLossRanges,
			complete = closed,
		)
	}

	fun terminalRecord(): ResearchTerminalIntegrityRecord = terminalIntegrity().asRecord()

	private fun integrityFor(
		envelopeCount: Long,
		firstSequence: Long?,
		lastSequence: Long?,
		lossRanges: List<ResearchLossRange>,
		complete: Boolean,
	): ResearchTraceTerminalIntegrity = ResearchTraceTerminalIntegrity(
		envelopeCount = envelopeCount,
		firstSequence = firstSequence,
		lastSequence = lastSequence,
		lossRangeCount = lossRanges.size.toLong(),
		lostEventCount = lossRanges.fold(0L) { total, range -> total.saturatingAdd(range.count) },
		complete = complete,
	)
}

/** A deterministic classification, rather than a host-clock-dependent replay side effect. */
sealed interface AltitudeReplayDecision {
	data class Accepted(
		val envelope: ResearchEvidenceEnvelope,
		val sourceTimeNanos: Long,
	) : AltitudeReplayDecision

	data class Rejected(
		val envelope: ResearchEvidenceEnvelope,
		val reason: AltitudeReplayRejection,
	) : AltitudeReplayDecision
}

enum class AltitudeReplayRejection {
	FUTURE_EVENT,
	DUPLICATE_SOURCE_SEQUENCE,
	OUT_OF_ORDER_SOURCE_TIME,
	CROSS_TRACE_IDENTITY,
	CROSS_CLOCK_DOMAIN,
	CROSS_REBOOT,
	REJECTED_BY_SOURCE,
	MISSING_SOURCE_TIME,
}

data class AltitudeReplayResult(
	val decisions: List<AltitudeReplayDecision>,
) {
	val accepted: List<AltitudeReplayDecision.Accepted>
		get() = decisions.filterIsInstance<AltitudeReplayDecision.Accepted>()
	val rejected: List<AltitudeReplayDecision.Rejected>
		get() = decisions.filterIsInstance<AltitudeReplayDecision.Rejected>()

	/** Deterministic execution order after recorded-order classification has been retained for audit. */
	val acceptedBySourceTime: List<AltitudeReplayDecision.Accepted>
		get() = accepted.sortedWith(
			compareBy<AltitudeReplayDecision.Accepted> { it.sourceTimeNanos }
				.thenBy { it.envelope.sequence },
		)
}

/** Test/replay diagnostics only; it does not alter the production estimator. */
data class AltitudeNumericalDiagnostic(
	val finiteState: Boolean,
	val symmetricCovariance: Boolean,
	val positiveSemidefiniteCovariance: Boolean,
)

/**
 * Small deterministic matrix checker used by replay tests. No numerical threshold is selected
 * here: callers get an exact structural diagnostic to compare against their own trace policy.
 */
object AltitudeNumericalDiagnostics {
	fun inspect(
		state: List<Double>,
		covariance: List<Double>,
		dimension: Int,
	): AltitudeNumericalDiagnostic {
		require(dimension >= 0) { "Covariance dimension must not be negative" }
		require(covariance.size == dimension * dimension) { "Covariance matrix has the wrong size" }
		val finite = state.all(Double::isFinite) && covariance.all(Double::isFinite)
		if (!finite) return AltitudeNumericalDiagnostic(false, false, false)
		val symmetric = (0 until dimension).all { row ->
			(0 until dimension).all { column ->
				covariance[row * dimension + column] == covariance[column * dimension + row]
			}
		}
		return AltitudeNumericalDiagnostic(
			finiteState = true,
			symmetricCovariance = symmetric,
			positiveSemidefiniteCovariance = symmetric && isPositiveSemidefinite(covariance, dimension),
		)
	}

	private fun isPositiveSemidefinite(matrix: List<Double>, dimension: Int): Boolean {
		val work = matrix.toMutableList()
		for (pivotIndex in 0 until dimension) {
			val pivot = work[pivotIndex * dimension + pivotIndex]
			if (pivot < 0.0) return false
			for (row in pivotIndex + 1 until dimension) {
				val below = work[row * dimension + pivotIndex]
				if (pivot == 0.0) {
					if (below != 0.0) return false
					continue
				}
				val factor = below / pivot
				for (column in row until dimension) {
					val updated = work[row * dimension + column] -
						factor * work[pivotIndex * dimension + column]
					work[row * dimension + column] = updated
					work[column * dimension + row] = updated
				}
			}
		}
		return true
	}
}

/**
 * Orders altitude evidence by its recorded source time within a single declared clock domain.
 * [replayHorizonNanos] is recorded input supplied by the caller; the host clock is never read.
 */
object AltitudeEvidenceReplay {
	fun classify(
		evidence: Iterable<ResearchEvidenceEnvelope>,
		clockDomain: ResearchClockDomain,
		traceIdentity: ResearchTraceIdentity? = null,
		replayHorizonNanos: Long? = null,
	): AltitudeReplayResult {
		val materialized = evidence.toList()
		val expectedIdentity = traceIdentity ?: materialized.firstOrNull()?.identity
		val seenSourceSequences = mutableSetOf<AltitudeSourceSequence>()
		var lastSourceTime: Long? = null
		val decisions = mutableListOf<AltitudeReplayDecision>()

		for (envelope in materialized) {
			val record = envelope.record
			val sourceTime = record.altitudeSourceTimeNanos()
			val sourceSequence = record.altitudeSourceSequence()
			val previousSourceTime = lastSourceTime
			val rejection = when {
				expectedIdentity != null && envelope.identity != expectedIdentity -> AltitudeReplayRejection.CROSS_TRACE_IDENTITY
				envelope.clockDomain.id != clockDomain.id || envelope.clockDomain.kind != clockDomain.kind ->
					AltitudeReplayRejection.CROSS_CLOCK_DOMAIN
				envelope.clockDomain.bootId != clockDomain.bootId -> AltitudeReplayRejection.CROSS_REBOOT
				envelope.lifecycleBoundary == ResearchLifecycleBoundary.REBOOT -> AltitudeReplayRejection.CROSS_REBOOT
				record is com.adsamcik.tracker.stats.api.research.RawPressureEventRecord &&
					record.validity != PressureValidityDecision.ACCEPTED -> AltitudeReplayRejection.REJECTED_BY_SOURCE
				sourceTime == null -> AltitudeReplayRejection.MISSING_SOURCE_TIME
				replayHorizonNanos != null && sourceTime > replayHorizonNanos -> AltitudeReplayRejection.FUTURE_EVENT
				sourceSequence != null && !seenSourceSequences.add(sourceSequence) -> AltitudeReplayRejection.DUPLICATE_SOURCE_SEQUENCE
				previousSourceTime != null && sourceTime < previousSourceTime ->
					AltitudeReplayRejection.OUT_OF_ORDER_SOURCE_TIME
				else -> null
			}
			if (rejection != null) {
				decisions += AltitudeReplayDecision.Rejected(envelope, rejection)
			} else {
				val acceptedSourceTime = requireNotNull(sourceTime)
				lastSourceTime = acceptedSourceTime
				decisions += AltitudeReplayDecision.Accepted(envelope, acceptedSourceTime)
			}
		}
		return AltitudeReplayResult(decisions)
	}

	private fun ResearchEvidenceRecord.altitudeSourceTimeNanos(): Long? = when (this) {
		is AltitudeLocationObservationRecord -> acquisitionElapsedNanos
		is com.adsamcik.tracker.stats.api.research.RawPressureEventRecord -> sourceElapsedNanos
		is com.adsamcik.tracker.stats.api.research.PressureAggregateWindowRecord -> windowEndElapsedNanos
		else -> null
	}

	private fun ResearchEvidenceRecord.altitudeSourceSequence(): AltitudeSourceSequence? = when (this) {
		is AltitudeLocationObservationRecord -> AltitudeSourceSequence.Location(sourceSequence)
		is com.adsamcik.tracker.stats.api.research.RawPressureEventRecord -> AltitudeSourceSequence.RawPressure(sourceSequence)
		is com.adsamcik.tracker.stats.api.research.PressureAggregateWindowRecord -> AltitudeSourceSequence.PressureAggregate(sourceLastSequence)
		else -> null
	}
}

private sealed interface AltitudeSourceSequence {
	val value: Long

	data class Location(override val value: Long) : AltitudeSourceSequence
	data class RawPressure(override val value: Long) : AltitudeSourceSequence
	data class PressureAggregate(override val value: Long) : AltitudeSourceSequence
}

/** The two legal replay orders are explicit so elapsed values never cross a domain boundary. */
enum class SegmentationReplayOrder {
	/** Preserve the order in which evidence was recorded. This is V1's required order. */
	RECORDED,
	/** Sort only contiguous same-domain stretches by event time, then source sequence. */
	EVENT_TIME_WITHIN_DOMAIN,
}

/** Lossy V1 input projection, isolated from the canonical evidence contract. */
object LegacyV1SegmentationProjection {
	fun project(observation: CanonicalSegmentationObservation): SegmentSignal? {
		val locationDecision = observation.locationDecision
		if (locationDecision != null && locationDecision.kind != CanonicalLocationDecisionKind.ACCEPTED) {
			return null
		}
		val location = observation.curatedLocation ?: return null
		val latitude = location.latitudeE7 ?: return null
		val longitude = location.longitudeE7 ?: return null
		return SegmentSignal(
			timestampMs = observation.eventEpochMs,
			latE7 = latitude,
			lonE7 = longitude,
			horizontalAccuracyM = location.horizontalAccuracyM,
			speedMps = location.speedMps,
			stepDelta = observation.stepEvidence?.delta ?: 0,
			activityType = observation.activityEvidence?.type,
			activityConfidence = observation.activityEvidence?.confidence,
		)
	}
}

/** Metadata retained beside V1's intentionally lossy legacy projection. */
data class V1RepresentationMetadata(
	val traceIdentity: ResearchTraceIdentity?,
	val envelopeSequence: Long?,
	val clockDomainId: String,
	val identityScopes: Set<String>,
	val policyTier: String?,
	val capabilityFlags: Set<String>,
	val envelopeCapabilities: ResearchEvidenceCapabilities?,
	val algorithmVersions: Map<String, String>,
	val privacyClass: ResearchPrivacyClass?,
)

/** Deterministic characterization data; it is not a new product segmentation result. */
data class V1SegmentationSensitivityDiagnostics(
	val eventSequence: List<String>,
	val stateDurationMs: Map<String, Long>,
	val distanceM: Float,
	val stepCount: Long,
	val boundaryDeltasMs: List<Long>,
	val inferredModes: List<String>,
	val unresolvedCount: Int,
	val lossRangeCount: Long,
	val lostEventCount: Long,
	val representationMetadata: List<V1RepresentationMetadata>,
)

data class V1SegmentationReplayResult(
	val events: List<SegmentEvent>,
	val inputCount: Int,
	val projectedCount: Int,
	val locationlessCount: Int,
	val clockDomainBoundaries: Int,
	val order: SegmentationReplayOrder,
	val traceIdentity: ResearchTraceIdentity?,
	val envelopeCount: Int,
	val lossRanges: List<ResearchLossRange>,
	val sensitivity: V1SegmentationSensitivityDiagnostics,
)

/** Offline deterministic runner for the existing V1 state machine, not a new product reducer. */
class V1SegmentationReplay(
	private val config: SegmentDetectorConfig = SegmentDetectorConfig(),
) {
	fun replay(
		observations: Iterable<CanonicalSegmentationObservation>,
		order: SegmentationReplayOrder = SegmentationReplayOrder.RECORDED,
	): V1SegmentationReplayResult = replayInputs(
		input = observations.map { ReplayCanonicalObservation(it, null) },
		order = order,
		traceIdentity = null,
		envelopeCount = 0,
		lossRanges = emptyList(),
		initialUnresolvedCount = 0,
	)

	/** Replays a declared transformation without dropping its loss/provenance accounting. */
	fun replay(
		transformation: SegmentationTransformationResult,
		order: SegmentationReplayOrder = SegmentationReplayOrder.RECORDED,
	): V1SegmentationReplayResult = replayInputs(
		input = transformation.observations.map { ReplayCanonicalObservation(it, null) },
		order = order,
		traceIdentity = null,
		envelopeCount = 0,
		lossRanges = transformation.lossRanges,
		initialUnresolvedCount = 0,
	)

	/**
	 * Replays canonical Workstream-2 envelopes without discarding trace identity, capability, loss,
	 * privacy, or algorithm metadata. Non-canonical records remain represented in [envelopeCount] and
	 * loss accounting but are not projected into the legacy location-required V1 reducer.
	 */
	fun replayEvidence(
		evidence: Iterable<ResearchEvidenceEnvelope>,
		order: SegmentationReplayOrder = SegmentationReplayOrder.RECORDED,
	): V1SegmentationReplayResult {
		val envelopes = evidence.toList()
		val lossRanges = envelopes.flatMap { it.allLossRanges() }
		val canonical = mutableListOf<ReplayCanonicalObservation>()
		var expectedIdentity: ResearchTraceIdentity? = null
		var unresolved = 0
		for (envelope in envelopes) {
			val observation = envelope.record as? CanonicalSegmentationObservation ?: continue
			if (expectedIdentity == null) expectedIdentity = envelope.identity
			if (envelope.identity != expectedIdentity || observation.clockDomainId != envelope.clockDomain.id) {
				unresolved++
				continue
			}
			canonical += ReplayCanonicalObservation(observation, envelope)
		}
		return replayInputs(
			input = canonical,
			order = order,
			traceIdentity = expectedIdentity,
			envelopeCount = envelopes.size,
			lossRanges = lossRanges,
			initialUnresolvedCount = unresolved,
		)
	}

	private fun replayInputs(
		input: List<ReplayCanonicalObservation>,
		order: SegmentationReplayOrder,
		traceIdentity: ResearchTraceIdentity?,
		envelopeCount: Int,
		lossRanges: List<ResearchLossRange>,
		initialUnresolvedCount: Int,
	): V1SegmentationReplayResult {
		val ordered = ordered(input, order)
		val detector = SessionSegmentDetector(config)
		val events = mutableListOf<SegmentEvent>()
		var projected = 0
		var locationless = 0
		var boundaries = 0
		var previousDomain: String? = null

		for (inputObservation in ordered) {
			val observation = inputObservation.observation
			if (previousDomain != null && previousDomain != observation.clockDomainId) {
				boundaries++
				// A domain boundary is a reset boundary for offline V1 replay: never compare epochs across it.
				detector.reset()
			}
			previousDomain = observation.clockDomainId
			val signal = LegacyV1SegmentationProjection.project(observation)
			if (signal == null) {
				locationless++
				continue
			}
			projected++
			detector.onSignal(signal)?.let(events::add)
		}

		val representationMetadata = ordered.map { it.representationMetadata() }
		val unresolvedCount = initialUnresolvedCount + locationless
		return V1SegmentationReplayResult(
			events = events,
			inputCount = ordered.size,
			projectedCount = projected,
			locationlessCount = locationless,
			clockDomainBoundaries = boundaries,
			order = order,
			traceIdentity = traceIdentity,
			envelopeCount = envelopeCount,
			lossRanges = lossRanges,
			sensitivity = diagnosticsFor(events, unresolvedCount, lossRanges, representationMetadata),
		)
	}

	private fun ordered(
		input: List<ReplayCanonicalObservation>,
		order: SegmentationReplayOrder,
	): List<ReplayCanonicalObservation> = when (order) {
		SegmentationReplayOrder.RECORDED -> input
		SegmentationReplayOrder.EVENT_TIME_WITHIN_DOMAIN -> input.sortWithinContiguousClockDomains()
	}

	private fun diagnosticsFor(
		events: List<SegmentEvent>,
		unresolvedCount: Int,
		lossRanges: List<ResearchLossRange>,
		representationMetadata: List<V1RepresentationMetadata>,
	): V1SegmentationSensitivityDiagnostics {
		val times = events.map { it.eventTimeMs() }
		val stateDurations = linkedMapOf<String, Long>()
		events.filterIsInstance<SegmentEvent.TripEnded>().forEach { event ->
			stateDurations["IN_TRIP_OR_STOP_PENDING"] =
				stateDurations.getOrDefault("IN_TRIP_OR_STOP_PENDING", 0L).saturatingAdd(event.endTimeMs - event.startTimeMs)
		}
		val lastMetrics = events.asReversed().firstNotNullOfOrNull { event -> when (event) {
			is SegmentEvent.TripEnded -> event.totalDistanceM to event.totalSteps.toLong()
			is SegmentEvent.TripUpdated -> event.accumulatedDistanceM to event.accumulatedSteps.toLong()
			else -> null
		} } ?: (0f to 0L)
		return V1SegmentationSensitivityDiagnostics(
			eventSequence = events.map { it.eventName() },
			stateDurationMs = stateDurations,
			distanceM = lastMetrics.first,
			stepCount = lastMetrics.second,
			boundaryDeltasMs = times.zipWithNext { first, second -> second - first },
			inferredModes = events.filterIsInstance<SegmentEvent.TripEnded>().map { it.inferredTransportMode.name },
			unresolvedCount = unresolvedCount,
			lossRangeCount = lossRanges.size.toLong(),
			lostEventCount = lossRanges.fold(0L) { total, range -> total.saturatingAdd(range.count) },
			representationMetadata = representationMetadata,
		)
	}
}

private data class ReplayCanonicalObservation(
	val observation: CanonicalSegmentationObservation,
	val envelope: ResearchEvidenceEnvelope?,
) {
	fun representationMetadata(): V1RepresentationMetadata = V1RepresentationMetadata(
		traceIdentity = envelope?.identity,
		envelopeSequence = envelope?.sequence,
		clockDomainId = observation.clockDomainId,
		identityScopes = observation.identityScopes,
		policyTier = observation.policyTier?.name,
		capabilityFlags = observation.capabilityFlags,
		envelopeCapabilities = envelope?.capabilities,
		algorithmVersions = envelope?.algorithmVersions.orEmpty(),
		privacyClass = envelope?.privacyClass,
	)
}

private fun List<ReplayCanonicalObservation>.sortWithinContiguousClockDomains(): List<ReplayCanonicalObservation> {
	if (size < 2) return this
	val result = ArrayList<ReplayCanonicalObservation>(size)
	var start = 0
	while (start < size) {
		val domain = this[start].observation.clockDomainId
		var end = start + 1
		while (end < size && this[end].observation.clockDomainId == domain) end++
		result += subList(start, end).sortedWith(
			compareBy<ReplayCanonicalObservation> { it.observation.eventEpochMs }
				.thenBy { it.observation.sourceSequence }
				.thenBy { it.envelope?.sequence ?: Long.MIN_VALUE },
		)
		start = end
	}
	return result
}

private fun SegmentEvent.eventName(): String = when (this) {
	is SegmentEvent.TripStarted -> "TripStarted"
	is SegmentEvent.TripUpdated -> "TripUpdated:${currentState.name}"
	is SegmentEvent.TripEnded -> "TripEnded:${inferredTransportMode.name}"
	is SegmentEvent.DepartureCancelled -> "DepartureCancelled"
}

private fun SegmentEvent.eventTimeMs(): Long = when (this) {
	is SegmentEvent.TripStarted -> startTimeMs
	is SegmentEvent.TripUpdated -> currentTimeMs
	is SegmentEvent.TripEnded -> endTimeMs
	is SegmentEvent.DepartureCancelled -> timestampMs
}

private fun ResearchEvidenceEnvelope.allLossRanges(): List<ResearchLossRange> =
	lossRanges + ((record as? ResearchTraceLossRecord)?.ranges.orEmpty())

private fun ResearchEvidenceEnvelope.terminalRecordOrNull(): ResearchTerminalIntegrityRecord? {
	val terminalRecord = record as? ResearchTerminalIntegrityRecord
	require(terminalIntegrity == null || terminalRecord == null || terminalIntegrity == terminalRecord) {
		"Terminal envelope and terminal record must agree"
	}
	return terminalIntegrity ?: terminalRecord
}

private fun ResearchLossRange.contains(sequence: Long): Boolean = sequence in firstSequence..lastSequence

private fun List<ResearchLossRange>.areDisjoint(): Boolean {
	if (size < 2) return true
	val ordered = sortedWith(compareBy<ResearchLossRange> { it.firstSequence }.thenBy { it.lastSequence })
	return ordered.zipWithNext().all { (left, right) -> left.lastSequence < right.firstSequence }
}

private fun List<ResearchLossRange>.covers(first: Long, last: Long): Boolean {
	if (first > last) return true
	var expected = first
	for (range in sortedWith(compareBy<ResearchLossRange> { it.firstSequence }.thenBy { it.lastSequence })) {
		if (range.lastSequence < expected) continue
		if (range.firstSequence > expected) return false
		if (range.lastSequence >= last) return true
		if (range.lastSequence == Long.MAX_VALUE) return true
		expected = range.lastSequence + 1L
	}
	return false
}

private fun Long.saturatingAdd(other: Long): Long = when {
	this == Long.MAX_VALUE || other == Long.MAX_VALUE -> Long.MAX_VALUE
	Long.MAX_VALUE - this < other -> Long.MAX_VALUE
	else -> this + other
}

/** A declared, testable transform applied only to offline canonical evidence. */
data class SegmentationTransformationResult(
	val observations: List<CanonicalSegmentationObservation>,
	val lossRanges: List<ResearchLossRange> = emptyList(),
	val gaps: List<ResearchGapRecord> = emptyList(),
	val provenance: String,
)

/**
 * Transform helpers deliberately require callers to supply all bounds and mappings. They characterize
 * V1 sensitivity; they do not select live acceptance windows, reorder bounds, or gap policy.
 */
object SegmentationEvidenceTransformations {
	fun densifyNonInformative(
		input: List<CanonicalSegmentationObservation>,
		insertsAfterSequence: Map<Long, List<CanonicalSegmentationObservation>>,
		provenance: String = "non-informative-densification",
	): SegmentationTransformationResult {
		val inputSequences = input.mapTo(mutableSetOf()) { it.sourceSequence }
		require(insertsAfterSequence.keys.all(inputSequences::contains)) {
			"Non-informative densification may insert only after an existing source sequence"
		}
		val output = ArrayList<CanonicalSegmentationObservation>(input.size + insertsAfterSequence.values.sumOf { it.size })
		for (observation in input) {
			output += observation
			for (insert in insertsAfterSequence[observation.sourceSequence].orEmpty()) {
				require(insert.rawLocation == null && insert.curatedLocation == null &&
					insert.locationDecision == null && insert.stepEvidence == null && insert.activityEvidence == null &&
					insert.lifecycleBoundary == null && insert.identityScopes.isEmpty() && insert.policyTier == null &&
					insert.capabilityFlags.isEmpty()) {
					"Non-informative densification may not fabricate evidence, decisions, lifecycle, or capability metadata"
				}
				output += insert
			}
		}
		return SegmentationTransformationResult(output, provenance = provenance)
	}

	fun thin(
		input: List<CanonicalSegmentationObservation>,
		keep: (CanonicalSegmentationObservation) -> Boolean,
		provenance: String = "thinning",
	): SegmentationTransformationResult {
		val kept = input.filter(keep)
		val removed = input.filterNot(keep)
		return SegmentationTransformationResult(
			observations = kept,
			lossRanges = contiguousLossRanges(removed),
			provenance = provenance,
		)
	}

	fun jitterEpoch(
		input: List<CanonicalSegmentationObservation>,
		offsetMs: (CanonicalSegmentationObservation) -> Long,
		provenance: String = "timestamp-jitter",
	): SegmentationTransformationResult = SegmentationTransformationResult(
		observations = input.map { it.copy(eventEpochMs = checkedAdd(it.eventEpochMs, offsetMs(it))) },
		provenance = provenance,
	)

	fun changeWallClock(
		input: List<CanonicalSegmentationObservation>,
		offsetMs: (CanonicalSegmentationObservation) -> Long,
	): SegmentationTransformationResult = jitterEpoch(
		input = input,
		offsetMs = offsetMs,
		provenance = "wall-clock-change",
	)

	fun addLongGap(
		input: List<CanonicalSegmentationObservation>,
		startEpochMs: Long?,
		endEpochMs: Long?,
		clockDomainId: String,
		reason: String? = null,
		provenance: String = "long-gap",
	): SegmentationTransformationResult = SegmentationTransformationResult(
		observations = input,
		gaps = listOf(ResearchGapRecord(startEpochMs, endEpochMs, clockDomainId, reason)),
		provenance = provenance,
	)

	fun regroupBatches(
		input: List<CanonicalSegmentationObservation>,
		batch: (CanonicalSegmentationObservation) -> Pair<Int?, Int?>,
		provenance: String = "batch-regrouping",
	): SegmentationTransformationResult = SegmentationTransformationResult(
		observations = input.map { observation ->
			val (index, size) = batch(observation)
			observation.copy(
				rawLocation = observation.rawLocation?.copy(batchIndex = index, batchSize = size),
				curatedLocation = observation.curatedLocation?.copy(batchIndex = index, batchSize = size),
			)
		},
		provenance = provenance,
	)

	fun permuteWithinBatches(
		input: List<CanonicalSegmentationObservation>,
		permutation: (List<CanonicalSegmentationObservation>) -> List<CanonicalSegmentationObservation>,
		provenance: String = "cross-batch-permutation",
	): SegmentationTransformationResult = SegmentationTransformationResult(
		observations = permutation(input).also { output ->
			require(output.size == input.size && output.multiset() == input.multiset()) {
				"A batch permutation must neither fabricate nor discard evidence"
			}
		},
		provenance = provenance,
	)

	fun duplicateExact(
		input: List<CanonicalSegmentationObservation>,
		indices: Iterable<Int>,
		provenance: String = "exact-known-duplicates",
	): SegmentationTransformationResult {
		val duplicateIndices = indices.toSet()
		return SegmentationTransformationResult(
			observations = input.flatMapIndexed { index, observation ->
				if (index in duplicateIndices) listOf(observation, observation) else listOf(observation)
			},
			provenance = provenance,
		)
	}

	fun clearChannels(
		input: List<CanonicalSegmentationObservation>,
		clearLocation: Boolean = false,
		clearSteps: Boolean = false,
		clearActivity: Boolean = false,
		provenance: String = "missing-channels",
	): SegmentationTransformationResult = SegmentationTransformationResult(
		observations = input.map {
			it.copy(
				rawLocation = if (clearLocation) null else it.rawLocation,
				curatedLocation = if (clearLocation) null else it.curatedLocation,
				locationDecision = if (clearLocation) null else it.locationDecision,
				stepEvidence = if (clearSteps) null else it.stepEvidence,
				activityEvidence = if (clearActivity) null else it.activityEvidence,
			)
		},
		provenance = provenance,
	)

	fun degradeAccuracy(
		input: List<CanonicalSegmentationObservation>,
		accuracy: (Float?) -> Float?,
		provenance: String = "degraded-accuracy",
	): SegmentationTransformationResult = SegmentationTransformationResult(
		observations = input.map { observation ->
			val raw = observation.rawLocation
			val curated = observation.curatedLocation
			observation.copy(
				rawLocation = raw?.copy(horizontalAccuracyM = accuracy(raw.horizontalAccuracyM)),
				curatedLocation = curated?.copy(horizontalAccuracyM = accuracy(curated.horizontalAccuracyM)),
			)
		},
		provenance = provenance,
	)

	fun introduceJumps(
		input: List<CanonicalSegmentationObservation>,
		location: (CanonicalSegmentationObservation) -> Pair<Int, Int>?,
		provenance: String = "location-jumps",
	): SegmentationTransformationResult = SegmentationTransformationResult(
		observations = input.map { observation ->
			val jump = location(observation) ?: return@map observation
			observation.copy(
				rawLocation = observation.rawLocation?.copy(latitudeE7 = jump.first, longitudeE7 = jump.second),
				curatedLocation = observation.curatedLocation?.copy(latitudeE7 = jump.first, longitudeE7 = jump.second),
			)
		},
		provenance = provenance,
	)

	fun resetSteps(
		input: List<CanonicalSegmentationObservation>,
		atOrAfterSequence: Long,
		provenance: String = "step-reset",
	): SegmentationTransformationResult = SegmentationTransformationResult(
		observations = input.map {
			if (it.sourceSequence < atOrAfterSequence) it else it.copy(
				stepEvidence = it.stepEvidence?.withReset(),
			)
		},
		provenance = provenance,
	)

	fun remapClockDomain(
		input: List<CanonicalSegmentationObservation>,
		newDomain: (CanonicalSegmentationObservation) -> String,
		provenance: String = "clock-domain-boundary",
	): SegmentationTransformationResult = SegmentationTransformationResult(
		observations = input.map { it.copy(clockDomainId = newDomain(it)) },
		provenance = provenance,
	)

	private fun CanonicalStepEvidence.withReset(): CanonicalStepEvidence = copy(reset = true)

	private fun List<CanonicalSegmentationObservation>.multiset(): Map<CanonicalSegmentationObservation, Int> =
		groupingBy { it }.eachCount()

	private fun contiguousLossRanges(
		removed: List<CanonicalSegmentationObservation>,
	): List<ResearchLossRange> {
		if (removed.isEmpty()) return emptyList()
		val sorted = removed.sortedBy { it.sourceSequence }
		val ranges = mutableListOf<ResearchLossRange>()
		var start = sorted.first().sourceSequence
		var end = start
		for (observation in sorted.drop(1)) {
			if (end != Long.MAX_VALUE && observation.sourceSequence == end + 1L) {
				end = observation.sourceSequence
			} else {
				ranges += ResearchLossRange(start, end, ResearchLossRange.Reason.UNKNOWN)
				start = observation.sourceSequence
				end = start
			}
		}
		ranges += ResearchLossRange(start, end, ResearchLossRange.Reason.UNKNOWN)
		return ranges
	}

	private fun checkedAdd(left: Long, right: Long): Long {
		val result = left + right
		val overflowBits = (left xor result) and (right xor result)
		require(overflowBits >= 0L) { "Timestamp jitter overflows Long" }
		return result
	}
}
