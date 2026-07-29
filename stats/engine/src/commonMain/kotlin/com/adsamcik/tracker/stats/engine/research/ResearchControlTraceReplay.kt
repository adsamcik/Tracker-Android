package com.adsamcik.tracker.stats.engine.research

import com.adsamcik.tracker.stats.api.research.ResearchAcquisitionRecord
import com.adsamcik.tracker.stats.api.research.ResearchClockDomain
import com.adsamcik.tracker.stats.api.research.ResearchControlDigestRecord
import com.adsamcik.tracker.stats.api.research.ResearchControlEventRecord
import com.adsamcik.tracker.stats.api.research.ResearchEvidenceEnvelope
import com.adsamcik.tracker.stats.api.research.ResearchEvidenceRecord
import com.adsamcik.tracker.stats.api.research.ResearchGapRecord
import com.adsamcik.tracker.stats.api.research.ResearchHorizontalEstimatorRecord
import com.adsamcik.tracker.stats.api.research.ResearchTrackingLifecycleRecord
import com.adsamcik.tracker.stats.api.research.ResearchTrackingLifecycleTransition
import com.adsamcik.tracker.stats.api.research.ResearchTrackingMode
import com.adsamcik.tracker.stats.api.research.ResearchTrackingStopCause
import com.adsamcik.tracker.stats.api.research.ResearchTraceIdentity
import com.adsamcik.tracker.stats.api.research.RESEARCH_EVIDENCE_SCHEMA_V2

/** The logical lifecycle state reconstructed solely from recorded V2 lifecycle evidence. */
enum class ResearchControlLifecycleState {
	INACTIVE,
	ACTIVE,
	PAUSED,
	STOP_CANDIDATE,
	STOPPED,
}

/** Per-logical-run replay state; it is diagnostic output, never a production controller state. */
data class ResearchControlReplayState(
	val logicalTrackingId: String,
	val lifecycleState: ResearchControlLifecycleState = ResearchControlLifecycleState.INACTIVE,
	val trackingMode: ResearchTrackingMode = ResearchTrackingMode.UNKNOWN,
	val lastSequence: Long? = null,
	val lastClockDomain: ResearchClockDomain? = null,
	val lastElapsedNanos: Long? = null,
	val observedGapCount: Long = 0L,
	val lastAcquisition: ResearchAcquisitionRecord? = null,
	val lastEstimator: ResearchHorizontalEstimatorRecord? = null,
)

/** A normalized V2 record together with the trace envelope that orders it. */
data class ResearchControlReplayEntry(
	val envelope: ResearchEvidenceEnvelope,
	val logicalTrackingId: String?,
	val eventEpochMs: Long?,
	val eventElapsedNanos: Long?,
	val record: ResearchEvidenceRecord,
)

sealed interface ResearchControlReplayDecision {
	data class Accepted(
		val entry: ResearchControlReplayEntry,
		val state: ResearchControlReplayState?,
	) : ResearchControlReplayDecision

	data class Rejected(
		val entry: ResearchControlReplayEntry,
		val reason: ResearchControlReplayRejection,
	) : ResearchControlReplayDecision
}

enum class ResearchControlReplayRejection {
	UNSUPPORTED_SCHEMA,
	CROSS_TRACE_IDENTITY,
	NON_MONOTONIC_ENVELOPE_SEQUENCE,
	OUT_OF_ORDER_ELAPSED_TIME,
	INVALID_LIFECYCLE_TRANSITION,
	AUTOMATIC_STOP_OF_USER_TRACKING,
}

data class ResearchControlTraceDigest(
	val algorithm: String,
	val value: String,
	val entryCount: Long,
	val firstSequence: Long?,
	val lastSequence: Long?,
)

/** Deterministic diagnostics for the typed V2 control evidence stream. */
data class ResearchControlTraceReplayResult(
	val decisions: List<ResearchControlReplayDecision>,
	val states: Map<String, ResearchControlReplayState>,
	val declaredDigests: List<ResearchControlDigestRecord>,
	val replayDigest: ResearchControlTraceDigest,
) {
	val accepted: List<ResearchControlReplayDecision.Accepted>
		get() = decisions.filterIsInstance<ResearchControlReplayDecision.Accepted>()
	val rejected: List<ResearchControlReplayDecision.Rejected>
		get() = decisions.filterIsInstance<ResearchControlReplayDecision.Rejected>()
	val legacyV1ReplayDigests: List<ResearchControlDigestRecord>
		get() = declaredDigests.filter {
			it.kind == com.adsamcik.tracker.stats.api.research.ResearchControlDigestKind.LEGACY_V1_REPLAY
		}
}

/**
 * Replays recorded order without reading a host clock or calling a platform API. It only validates
 * control-trace invariants and reconstructs explicit lifecycle state; it intentionally does not
 * select live policy, acquisition, or estimator thresholds.
 */
object ResearchControlTraceReplay {
	fun replay(evidence: Iterable<ResearchEvidenceEnvelope>): ResearchControlTraceReplayResult {
		val decisions = mutableListOf<ResearchControlReplayDecision>()
		val states = linkedMapOf<String, ResearchControlReplayState>()
		val declaredDigests = mutableListOf<ResearchControlDigestRecord>()
		var expectedIdentity: ResearchTraceIdentity? = null
		var previousSequence: Long? = null

		for (envelope in evidence) {
			val entry = envelope.asControlReplayEntryOrNull() ?: continue
			val rejection = when {
				envelope.schemaVersion < RESEARCH_EVIDENCE_SCHEMA_V2 ->
					ResearchControlReplayRejection.UNSUPPORTED_SCHEMA
				expectedIdentity != null && envelope.identity != expectedIdentity ->
					ResearchControlReplayRejection.CROSS_TRACE_IDENTITY
				previousSequence?.let { envelope.sequence <= it } == true ->
					ResearchControlReplayRejection.NON_MONOTONIC_ENVELOPE_SEQUENCE
				else -> null
			}
			if (rejection != null) {
				decisions += ResearchControlReplayDecision.Rejected(entry, rejection)
				continue
			}
			if (expectedIdentity == null) expectedIdentity = envelope.identity
			previousSequence = envelope.sequence

			val logicalTrackingId = entry.logicalTrackingId
			val prior = logicalTrackingId?.let(states::get)
			val timeRejection = if (prior != null && prior.lastClockDomain == envelope.clockDomain &&
				prior.lastElapsedNanos != null && entry.eventElapsedNanos != null &&
				entry.eventElapsedNanos < prior.lastElapsedNanos
			) ResearchControlReplayRejection.OUT_OF_ORDER_ELAPSED_TIME else null
			if (timeRejection != null) {
				decisions += ResearchControlReplayDecision.Rejected(entry, timeRejection)
				continue
			}

			val update = apply(entry, prior)
			if (update is ReplayUpdate.Rejected) {
				decisions += ResearchControlReplayDecision.Rejected(entry, update.reason)
				continue
			}
			val state = (update as ReplayUpdate.Accepted).state
			if (logicalTrackingId != null && state != null) states[logicalTrackingId] = state
			if (entry.record is ResearchControlDigestRecord) declaredDigests += entry.record
			decisions += ResearchControlReplayDecision.Accepted(entry, state)
		}

		val digestEntries = decisions.filterIsInstance<ResearchControlReplayDecision.Accepted>()
			.map { it.entry }
			.filterNot { it.record is ResearchControlDigestRecord }
		return ResearchControlTraceReplayResult(
			decisions = decisions,
			states = states.toMap(),
			declaredDigests = declaredDigests.toList(),
			replayDigest = ResearchControlTraceDigester.digest(digestEntries),
		)
	}

	private fun apply(
		entry: ResearchControlReplayEntry,
		prior: ResearchControlReplayState?,
	): ReplayUpdate {
		val record = entry.record
		val base = entry.logicalTrackingId?.let { logicalTrackingId ->
			(prior ?: ResearchControlReplayState(logicalTrackingId)).withObservation(entry)
		}
		return when (record) {
			is ResearchTrackingLifecycleRecord -> applyLifecycle(record, requireNotNull(base))
			is ResearchAcquisitionRecord -> ReplayUpdate.Accepted(requireNotNull(base).copy(lastAcquisition = record))
			is ResearchHorizontalEstimatorRecord -> ReplayUpdate.Accepted(requireNotNull(base).copy(lastEstimator = record))
			is ResearchGapRecord -> ReplayUpdate.Accepted(requireNotNull(base).copy(observedGapCount = base.observedGapCount + 1L))
			is ResearchControlEventRecord -> ReplayUpdate.Accepted(base)
			is ResearchControlDigestRecord -> ReplayUpdate.Accepted(base)
			else -> error("Only V2 control records can be replay entries")
		}
	}

	private fun applyLifecycle(
		record: ResearchTrackingLifecycleRecord,
		base: ResearchControlReplayState,
	): ReplayUpdate {
		val next = when (record.transition) {
			ResearchTrackingLifecycleTransition.STARTED -> when (base.lifecycleState) {
				ResearchControlLifecycleState.INACTIVE,
				ResearchControlLifecycleState.STOPPED -> base.copy(
					lifecycleState = ResearchControlLifecycleState.ACTIVE,
					trackingMode = record.trackingMode,
				)
				else -> return ReplayUpdate.Rejected(ResearchControlReplayRejection.INVALID_LIFECYCLE_TRANSITION)
			}
			ResearchTrackingLifecycleTransition.RECOVERED -> when (base.lifecycleState) {
				ResearchControlLifecycleState.INACTIVE,
				ResearchControlLifecycleState.STOPPED -> base.copy(
					lifecycleState = ResearchControlLifecycleState.ACTIVE,
					trackingMode = record.trackingMode,
				)
				else -> return ReplayUpdate.Rejected(ResearchControlReplayRejection.INVALID_LIFECYCLE_TRANSITION)
			}
			ResearchTrackingLifecycleTransition.PAUSED -> if (base.lifecycleState == ResearchControlLifecycleState.ACTIVE) {
				base.copy(lifecycleState = ResearchControlLifecycleState.PAUSED)
			} else {
				return ReplayUpdate.Rejected(ResearchControlReplayRejection.INVALID_LIFECYCLE_TRANSITION)
			}
			ResearchTrackingLifecycleTransition.RESUMED -> if (base.lifecycleState == ResearchControlLifecycleState.PAUSED) {
				base.copy(lifecycleState = ResearchControlLifecycleState.ACTIVE)
			} else {
				return ReplayUpdate.Rejected(ResearchControlReplayRejection.INVALID_LIFECYCLE_TRANSITION)
			}
			ResearchTrackingLifecycleTransition.STOP_CANDIDATE -> if (
				base.lifecycleState == ResearchControlLifecycleState.ACTIVE ||
					base.lifecycleState == ResearchControlLifecycleState.PAUSED
			) {
				base.copy(lifecycleState = ResearchControlLifecycleState.STOP_CANDIDATE)
			} else {
				return ReplayUpdate.Rejected(ResearchControlReplayRejection.INVALID_LIFECYCLE_TRANSITION)
			}
			ResearchTrackingLifecycleTransition.STOPPED -> {
				if (base.lifecycleState !in setOf(
					ResearchControlLifecycleState.ACTIVE,
					ResearchControlLifecycleState.PAUSED,
					ResearchControlLifecycleState.STOP_CANDIDATE,
				)) {
					return ReplayUpdate.Rejected(ResearchControlReplayRejection.INVALID_LIFECYCLE_TRANSITION)
				}
				val effectiveMode = if (base.trackingMode == ResearchTrackingMode.UNKNOWN) {
					record.trackingMode
				} else {
					base.trackingMode
				}
				if (effectiveMode == ResearchTrackingMode.USER_INITIATED &&
					record.stopCause != ResearchTrackingStopCause.USER_REQUEST
				) {
					return ReplayUpdate.Rejected(ResearchControlReplayRejection.AUTOMATIC_STOP_OF_USER_TRACKING)
				}
				base.copy(lifecycleState = ResearchControlLifecycleState.STOPPED)
			}
		}
		return ReplayUpdate.Accepted(next)
	}

	private fun ResearchControlReplayState.withObservation(
		entry: ResearchControlReplayEntry,
	): ResearchControlReplayState = copy(
		lastSequence = entry.envelope.sequence,
		lastClockDomain = entry.envelope.clockDomain,
		lastElapsedNanos = entry.eventElapsedNanos ?: lastElapsedNanos,
	)

	private sealed interface ReplayUpdate {
		data class Accepted(val state: ResearchControlReplayState?) : ReplayUpdate
		data class Rejected(val reason: ResearchControlReplayRejection) : ReplayUpdate
	}
}

/** Stable, non-cryptographic replay digest for test gates and capture comparison. */
object ResearchControlTraceDigester {
	const val ALGORITHM: String = "fnv1a64-control-v1"

	fun digest(entries: Iterable<ResearchControlReplayEntry>): ResearchControlTraceDigest {
		val materialized = entries.toList()
		var hash = -3_750_763_034_362_895_579L // unsigned FNV-1a 64-bit offset basis
		for (entry in materialized) {
			val canonical = entry.canonicalForm()
			for (character in canonical) {
				hash = (hash xor character.code.toLong()) * 1_099_511_628_211L
			}
		}
		return ResearchControlTraceDigest(
			algorithm = ALGORITHM,
			value = hash.asUnsignedHex(),
			entryCount = materialized.size.toLong(),
			firstSequence = materialized.firstOrNull()?.envelope?.sequence,
			lastSequence = materialized.lastOrNull()?.envelope?.sequence,
		)
	}
}

private fun ResearchEvidenceEnvelope.asControlReplayEntryOrNull(): ResearchControlReplayEntry? {
	val record = this.record
	return when (record) {
		is ResearchControlEventRecord -> ResearchControlReplayEntry(this, record.logicalTrackingId, record.eventEpochMs, record.eventElapsedNanos, record)
		is ResearchTrackingLifecycleRecord -> ResearchControlReplayEntry(this, record.logicalTrackingId, record.eventEpochMs, record.eventElapsedNanos, record)
		is ResearchAcquisitionRecord -> ResearchControlReplayEntry(this, record.logicalTrackingId, record.eventEpochMs, record.eventElapsedNanos, record)
		is ResearchHorizontalEstimatorRecord -> ResearchControlReplayEntry(this, record.logicalTrackingId, record.eventEpochMs, record.sourceElapsedNanos, record)
		is ResearchGapRecord -> record.logicalTrackingId?.let {
			ResearchControlReplayEntry(this, it, record.startEpochMs, record.startElapsedNanos, record)
		}
		is ResearchControlDigestRecord -> ResearchControlReplayEntry(this, record.logicalTrackingId, null, null, record)
		else -> null
	}
}

private fun ResearchControlReplayEntry.canonicalForm(): String = buildString {
	appendCanonical(envelope.identity.traceId)
	appendCanonical(envelope.identity.runId)
	appendCanonical(envelope.identity.sessionId)
	appendCanonical(envelope.sequence.toString())
	appendCanonical(envelope.clockDomain.id)
	appendCanonical(envelope.clockDomain.kind.name)
	appendCanonical(envelope.clockDomain.bootId)
	appendCanonical(envelope.privacyClass.name)
	appendCanonical(envelope.schemaVersion.toString())
	when (val value = record) {
		is ResearchControlEventRecord -> {
			appendCanonical("control")
			appendCanonical(value.logicalTrackingId)
			appendCanonical(value.eventEpochMs?.toString())
			appendCanonical(value.eventElapsedNanos?.toString())
			appendCanonical(value.kind.name)
			appendCanonical(value.reason)
			appendCanonical(value.correlationId)
			value.payload.toSortedMap().forEach { (key, payloadValue) ->
				appendCanonical(key)
				appendCanonical(payloadValue)
			}
		}
		is ResearchTrackingLifecycleRecord -> {
			appendCanonical("lifecycle")
			appendCanonical(value.logicalTrackingId)
			appendCanonical(value.transition.name)
			appendCanonical(value.trackingMode.name)
			appendCanonical(value.eventEpochMs?.toString())
			appendCanonical(value.eventElapsedNanos?.toString())
			appendCanonical(value.stopCause?.name)
			appendCanonical(value.reason)
			appendCanonical(value.correlationId)
			appendCanonical(value.resumedFromLogicalTrackingId)
		}
		is ResearchAcquisitionRecord -> {
			appendCanonical("acquisition")
			appendCanonical(value.logicalTrackingId)
			appendCanonical(value.requestId)
			appendCanonical(value.eventEpochMs?.toString())
			appendCanonical(value.eventElapsedNanos?.toString())
			appendAcquisition(value.desired)
			appendAcquisition(value.applied)
			appendCanonical(value.outcome.name)
			appendCanonical(value.reason)
			appendCanonical(value.correlationId)
		}
		is ResearchHorizontalEstimatorRecord -> {
			appendCanonical("estimator")
			appendCanonical(value.logicalTrackingId)
			appendCanonical(value.estimatorVersion)
			appendCanonical(value.decision.name)
			appendCanonical(value.eventEpochMs?.toString())
			appendCanonical(value.sourceElapsedNanos?.toString())
			appendCanonical(value.deltaNanos?.toString())
			appendCanonical(value.sourceSequence?.toString())
			appendCanonical(value.stateDimension.toString())
			appendCanonical(value.stateBefore.joinToString(","))
			appendCanonical(value.stateAfter.joinToString(","))
			appendCanonical(value.covarianceBefore.joinToString(","))
			appendCanonical(value.covarianceAfter.joinToString(","))
			appendCanonical(value.processNoise.joinToString(","))
			appendCanonical(value.measurementEastM?.toString())
			appendCanonical(value.measurementNorthM?.toString())
			appendCanonical(value.measurementCovariance.joinToString(","))
			appendCanonical(value.innovation.joinToString(","))
			appendCanonical(value.normalizedInnovationSquared?.toString())
			appendCanonical(value.accepted?.toString())
			appendCanonical(value.reason)
			appendCanonical(value.correlationId)
		}
		is ResearchGapRecord -> {
			appendCanonical("gap")
			appendCanonical(value.logicalTrackingId)
			appendCanonical(value.startEpochMs?.toString())
			appendCanonical(value.endEpochMs?.toString())
			appendCanonical(value.clockDomainId)
			appendCanonical(value.startElapsedNanos?.toString())
			appendCanonical(value.endElapsedNanos?.toString())
			appendCanonical(value.reason)
		}
		is ResearchControlDigestRecord -> Unit // Digests never cover themselves.
		else -> error("Only V2 control records can be canonicalized")
	}
}

private fun StringBuilder.appendAcquisition(
	value: com.adsamcik.tracker.stats.api.research.ResearchAcquisitionConfiguration?,
) {
	appendCanonical(value?.mode?.name)
	appendCanonical(value?.intervalMs?.toString())
	appendCanonical(value?.minUpdateDistanceM?.toString())
	appendCanonical(value?.maxUpdateDelayMs?.toString())
	appendCanonical(value?.requestGnssStatus?.toString())
	appendCanonical(value?.providerIdentity)
	appendCanonical(value?.priority)
}

private fun StringBuilder.appendCanonical(value: String?) {
	if (value == null) {
		append("-1:")
	} else {
		append(value.length)
		append(':')
		append(value)
	}
}

private fun Long.asUnsignedHex(): String {
	val digits = "0123456789abcdef"
	val output = CharArray(16)
	var value = this
	for (index in output.lastIndex downTo 0) {
		output[index] = digits[(value and 0xFL).toInt()]
		value = value ushr 4
	}
	return output.concatToString()
}
