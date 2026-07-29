package com.adsamcik.tracker.tracker.control

import com.adsamcik.tracker.stats.api.research.ResearchAcquisitionApplyOutcome
import com.adsamcik.tracker.stats.api.research.ResearchAcquisitionConfiguration
import com.adsamcik.tracker.stats.api.research.ResearchAcquisitionMode
import com.adsamcik.tracker.stats.api.research.ResearchAcquisitionRecord
import com.adsamcik.tracker.stats.api.research.ResearchClockDomain
import com.adsamcik.tracker.stats.api.research.ResearchControlEventKind
import com.adsamcik.tracker.stats.api.research.ResearchControlEventRecord
import com.adsamcik.tracker.stats.api.research.ResearchEvidenceCapabilities
import com.adsamcik.tracker.stats.api.research.ResearchEvidenceEnvelope
import com.adsamcik.tracker.stats.api.research.ResearchEvidenceRecord
import com.adsamcik.tracker.stats.api.research.ResearchGapRecord
import com.adsamcik.tracker.stats.api.research.ResearchHorizontalEstimatorDecision
import com.adsamcik.tracker.stats.api.research.ResearchHorizontalEstimatorRecord
import com.adsamcik.tracker.stats.api.research.ResearchLossRange
import com.adsamcik.tracker.stats.api.research.ResearchPrivacyClass
import com.adsamcik.tracker.stats.api.research.ResearchTraceIdentity
import com.adsamcik.tracker.stats.api.research.ResearchTrackingLifecycleRecord
import com.adsamcik.tracker.stats.api.research.ResearchTrackingLifecycleTransition
import com.adsamcik.tracker.stats.api.research.ResearchTrackingMode
import com.adsamcik.tracker.stats.api.research.ResearchTrackingStopCause
import com.adsamcik.tracker.tracker.control.HorizontalEstimatorDecision
import com.adsamcik.tracker.tracker.control.LocationAcquisitionMode as DecisionAcquisitionMode

/**
 * Opt-in, bounded in-memory recorder for V2 control evidence.
 *
 * It intentionally does not write a file, a database row, or a network request. A debug caller
 * must explicitly take [snapshot] and pass it through an authenticated encrypted exporter. When
 * the bounded buffer drops envelopes, the returned first envelope declares the exact lost sequence
 * span; absence of a terminal envelope therefore never claims a complete trace.
 */
internal class ResearchControlTraceRecorder(
	private val identity: ResearchTraceIdentity,
	private val algorithmVersions: Map<String, String> = mapOf(
		"trackingDecisionEngine" to "shadow-v1",
		"horizontalEstimator" to "enu-cv-kalman-v1",
	),
	private val maximumEnvelopes: Int = DEFAULT_MAXIMUM_ENVELOPES,
) : TrackingControlOutputSink, TrackingControlAcquisitionOutcomeSink {
	init {
		require(maximumEnvelopes > 0) { "Maximum envelope count must be positive" }
		require(algorithmVersions.keys.none(String::isBlank))
		require(algorithmVersions.values.none(String::isBlank))
	}

	private data class GapStart(
		val epochMs: Long,
		val elapsedNanos: Long,
		val clockDomainId: String,
	)

	private val retained = mutableListOf<ResearchEvidenceEnvelope>()
	private val lossRanges = mutableListOf<ResearchLossRange>()
	private val openGaps = mutableMapOf<String, GapStart>()
	private val recorderLock = Any()
	private var nextSequence = 0L

	override fun onDecision(output: ControlOutput) {
		synchronized(recorderLock) {
			val logicalTrackingId = output.snapshot.logicalTrackingId?.value ?: return@synchronized
			val records = buildRecords(logicalTrackingId, output)
			records.forEach { record -> append(record, output) }
		}
	}

	/**
	 * Records the result after the feature-gated Android adapter actually attempted a request. The
	 * stable request id joins this record to the reducer request; an adapter probe deadline is
	 * explicit rather than silently changing the last reducer shape.
	 */
	override fun onAcquisitionOutcome(result: ControlAcquisitionApplyResult) {
		synchronized(recorderLock) {
			val command = result.command
			append(
				ResearchAcquisitionRecord(
					logicalTrackingId = command.logicalTrackingId,
					requestId = command.requestId,
					eventEpochMs = result.eventEpochMs,
					eventElapsedNanos = result.eventElapsedNanos,
					desired = command.request.toResearchConfiguration(
						providerIdentity = if (command.origin == ControlAcquisitionCommandOrigin.DECISION) {
							"shadow"
						} else {
							"controller-adapter"
						},
					),
					applied = result.applied?.toResearchConfiguration(providerIdentity = "tracker-controller"),
					outcome = result.outcome.toResearchOutcome(),
					reason = "${command.origin.name}:${result.reason}",
					correlationId = command.correlationId(),
				),
				clockDomainId = result.clockDomainId,
			)
		}
	}

	/**
	 * A stable copy for a debug/export caller. The first retained envelope carries any buffer-loss
	 * declaration; callers must preserve it when serializing or encrypting the trace.
	 */
	fun snapshot(): List<ResearchEvidenceEnvelope> = synchronized(recorderLock) {
		retained.mapIndexed { index, envelope ->
			if (index == 0 && lossRanges.isNotEmpty()) envelope.copy(lossRanges = lossRanges.toList()) else envelope
		}
	}

	fun clear() = synchronized(recorderLock) {
		retained.clear()
		lossRanges.clear()
		openGaps.clear()
		nextSequence = 0L
	}

	private fun buildRecords(
		logicalTrackingId: String,
		output: ControlOutput,
	): List<ResearchEvidenceRecord> = buildList {
		add(genericInputRecord(logicalTrackingId, output))
		output.transitions.forEach { transition ->
			add(
				ResearchControlEventRecord(
					logicalTrackingId = logicalTrackingId,
					eventEpochMs = output.input.wallTimeMs,
					eventElapsedNanos = output.input.elapsedRealtimeNanos,
					kind = transition.toResearchEventKind(),
					reason = transition.reason,
					correlationId = "decision:${output.ledgerSequence}",
					payload = mapOf(
						"transitionKind" to transition.kind.name,
						"from" to (transition.from ?: "NONE"),
						"to" to (transition.to ?: "NONE"),
					),
				),
			)
			transition.toLifecycleRecord(logicalTrackingId, output)?.let(::add)
			transition.toAcquisitionRecord(logicalTrackingId, output)?.let(::add)
			gapRecord(logicalTrackingId, transition, output)?.let(::add)
		}
		output.estimator?.let { estimator ->
			add(
				ResearchHorizontalEstimatorRecord(
					logicalTrackingId = logicalTrackingId,
					estimatorVersion = "enu-cv-kalman-v1",
					decision = estimator.decision.toResearchDecision(),
					eventEpochMs = output.input.wallTimeMs,
					sourceElapsedNanos = output.input.elapsedRealtimeNanos,
					sourceSequence = output.ledgerSequence,
					normalizedInnovationSquared = estimator.normalizedInnovationSquared,
					accepted = estimator.decision == HorizontalEstimatorDecision.INITIALIZED ||
						estimator.decision == HorizontalEstimatorDecision.ACCEPTED ||
						estimator.decision == HorizontalEstimatorDecision.GAP_RESET,
					reason = estimator.reason,
					correlationId = estimator.sourceEventId,
				),
			)
		}
	}

	private fun genericInputRecord(
		logicalTrackingId: String,
		output: ControlOutput,
	): ResearchControlEventRecord = ResearchControlEventRecord(
		logicalTrackingId = logicalTrackingId,
		eventEpochMs = output.input.wallTimeMs,
		eventElapsedNanos = output.input.elapsedRealtimeNanos,
		kind = output.input.payload.toResearchEventKind(),
		reason = output.estimator?.reason,
		correlationId = "decision:${output.ledgerSequence}",
		payload = mapOf(
			"input" to output.input.payload.inputName(),
			"lifecycle" to output.snapshot.lifecycle.name,
			"motion" to output.snapshot.motion.name,
			"observability" to output.snapshot.observability.name,
			"acquisition" to output.snapshot.acquisition.mode.name,
			"continuity" to output.snapshot.continuity.name,
		),
	)

	private fun gapRecord(
		logicalTrackingId: String,
		transition: ControlTransition,
		output: ControlOutput,
	): ResearchGapRecord? {
		if (transition.kind != ControlTransitionKind.CONTINUITY) return null
		val now = GapStart(
			epochMs = output.input.wallTimeMs,
			elapsedNanos = output.input.elapsedRealtimeNanos,
			clockDomainId = output.input.clockDomainId,
		)
		return when (transition.to) {
			TrackingContinuity.GAP_OPEN.name -> {
				openGaps[logicalTrackingId] = now
				ResearchGapRecord(
					startEpochMs = now.epochMs,
					endEpochMs = null,
					clockDomainId = now.clockDomainId,
					reason = transition.reason,
					logicalTrackingId = logicalTrackingId,
					startElapsedNanos = now.elapsedNanos,
				)
			}
			TrackingContinuity.CONTINUOUS.name -> openGaps.remove(logicalTrackingId)?.let { start ->
				if (start.clockDomainId == now.clockDomainId) {
					ResearchGapRecord(
						startEpochMs = start.epochMs,
						endEpochMs = now.epochMs,
						clockDomainId = now.clockDomainId,
						reason = transition.reason,
						logicalTrackingId = logicalTrackingId,
						startElapsedNanos = start.elapsedNanos,
						endElapsedNanos = now.elapsedNanos,
					)
				} else {
					// A boot/domain boundary deliberately cannot be bridged by an elapsed-time range.
					ResearchGapRecord(
						startEpochMs = now.epochMs,
						endEpochMs = null,
						clockDomainId = now.clockDomainId,
						reason = "CROSS_CLOCK_DOMAIN:${transition.reason}",
						logicalTrackingId = logicalTrackingId,
						startElapsedNanos = now.elapsedNanos,
					)
				}
			}
			else -> null
		}
	}

	private fun ControlTransition.toLifecycleRecord(
		logicalTrackingId: String,
		output: ControlOutput,
	): ResearchTrackingLifecycleRecord? {
		if (kind != ControlTransitionKind.LIFECYCLE) return null
		val transition = when (to) {
			LogicalLifecycleState.ACTIVE.name -> when (from) {
				LogicalLifecycleState.PAUSED.name -> ResearchTrackingLifecycleTransition.RESUMED
				LogicalLifecycleState.IDLE.name,
				LogicalLifecycleState.FINISHED.name,
				-> ResearchTrackingLifecycleTransition.STARTED
				else -> return null // cancellation of a stop candidate is represented by the generic event
			}
			LogicalLifecycleState.PAUSED.name -> ResearchTrackingLifecycleTransition.PAUSED
			LogicalLifecycleState.STOP_CANDIDATE.name -> ResearchTrackingLifecycleTransition.STOP_CANDIDATE
			LogicalLifecycleState.FINISHED.name -> ResearchTrackingLifecycleTransition.STOPPED
			else -> return null
		}
		return ResearchTrackingLifecycleRecord(
			logicalTrackingId = logicalTrackingId,
			transition = transition,
			trackingMode = output.snapshot.sessionOrigin.toResearchTrackingMode(),
			eventEpochMs = output.input.wallTimeMs,
			eventElapsedNanos = output.input.elapsedRealtimeNanos,
			stopCause = if (transition == ResearchTrackingLifecycleTransition.STOPPED) {
				stopCauseFor(output, reason)
			} else {
				null
			},
			reason = reason,
			correlationId = "decision:${output.ledgerSequence}",
		)
	}

	private fun ControlTransition.toAcquisitionRecord(
		logicalTrackingId: String,
		output: ControlOutput,
	): ResearchAcquisitionRecord? {
		if (kind != ControlTransitionKind.ACQUISITION) return null
		val request = output.snapshot.acquisition
		return ResearchAcquisitionRecord(
			logicalTrackingId = logicalTrackingId,
			requestId = controlAcquisitionRequestId(logicalTrackingId, output.ledgerSequence),
			eventEpochMs = output.input.wallTimeMs,
			eventElapsedNanos = output.input.elapsedRealtimeNanos,
			desired = request.toResearchConfiguration(),
			// The legacy adapter remains authoritative while in shadow mode.
			applied = null,
			outcome = ResearchAcquisitionApplyOutcome.REQUESTED,
			reason = request.reason,
			correlationId = "decision:${output.ledgerSequence}",
		)
	}

	private fun append(record: ResearchEvidenceRecord, output: ControlOutput) =
		append(record, output.input.clockDomainId)

	private fun append(record: ResearchEvidenceRecord, clockDomainId: String) {
		val envelope = ResearchEvidenceEnvelope(
			identity = identity,
			sequence = nextSequence++,
			clockDomain = ResearchClockDomain(
				id = clockDomainId,
				kind = ResearchClockDomain.Kind.ANDROID_ELAPSED_REALTIME,
			),
			privacyClass = ResearchPrivacyClass.ENCRYPTED_RESEARCH,
			algorithmVersions = algorithmVersions,
			capabilities = CAPABILITIES,
			record = record,
		)
		if (retained.size == maximumEnvelopes) {
			val dropped = retained.removeAt(0)
			recordLoss(dropped.sequence)
		}
		retained += envelope
	}

	private fun recordLoss(sequence: Long) {
		val last = lossRanges.lastOrNull()
		if (last != null && last.lastSequence != Long.MAX_VALUE && last.lastSequence + 1L == sequence) {
			lossRanges[lossRanges.lastIndex] = last.copy(lastSequence = sequence)
		} else {
			lossRanges += ResearchLossRange(
				firstSequence = sequence,
				lastSequence = sequence,
				reason = ResearchLossRange.Reason.BUFFER_OVERFLOW,
			)
		}
	}

	private fun ControlEvidence.toResearchEventKind(): ResearchControlEventKind = when (this) {
		is ControlEvidence.SessionStarted -> ResearchControlEventKind.TRACKING_ENABLED
		is ControlEvidence.FinishRequested -> ResearchControlEventKind.TRACKING_DISABLED
		is ControlEvidence.PolicyIntent -> ResearchControlEventKind.POLICY_INPUT
		is ControlEvidence.ActivityEvidence -> ResearchControlEventKind.ACTIVITY_CHANGED
		is ControlEvidence.LocationObservation -> ResearchControlEventKind.LOCATION_BATCH_RECEIVED
		is ControlEvidence.CuratedLocationDecision -> if (accepted) {
			ResearchControlEventKind.LOCATION_ACCEPTED
		} else {
			ResearchControlEventKind.LOCATION_REJECTED
		}
		is ControlEvidence.AutomaticStopEvidence -> ResearchControlEventKind.STOP_CANDIDATE
		else -> ResearchControlEventKind.UNKNOWN
	}

	private fun ControlEvidence.inputName(): String = when (this) {
		is ControlEvidence.SessionStarted -> "SESSION_STARTED"
		is ControlEvidence.PauseRequested -> "PAUSE_REQUESTED"
		is ControlEvidence.ResumeRequested -> "RESUME_REQUESTED"
		is ControlEvidence.FinishRequested -> "FINISH_REQUESTED"
		is ControlEvidence.AutomaticStopEvidence -> "AUTOMATIC_STOP_EVIDENCE"
		is ControlEvidence.ActivityEvidence -> "ACTIVITY_EVIDENCE"
		is ControlEvidence.StepEvidence -> "STEP_EVIDENCE"
		is ControlEvidence.LocationObservation -> "LOCATION_OBSERVATION"
		is ControlEvidence.CuratedLocationDecision -> "CURATED_LOCATION_DECISION"
		is ControlEvidence.PolicyIntent -> "POLICY_INTENT"
		is ControlEvidence.ProviderAvailability -> "PROVIDER_AVAILABILITY"
		is ControlEvidence.ProbeResult -> "PROBE_RESULT"
		is ControlEvidence.Tick -> "TICK"
	}

	private fun ControlTransition.toResearchEventKind(): ResearchControlEventKind = when (kind) {
		ControlTransitionKind.MOTION -> ResearchControlEventKind.MOTION_CHANGED
		ControlTransitionKind.CONTINUITY -> if (to == TrackingContinuity.GAP_OPEN.name) {
			ResearchControlEventKind.GAP_OPENED
		} else {
			ResearchControlEventKind.GAP_CLOSED
		}
		ControlTransitionKind.LIFECYCLE -> when (to) {
			LogicalLifecycleState.STOP_CANDIDATE.name -> ResearchControlEventKind.STOP_CANDIDATE
			LogicalLifecycleState.FINISHED.name -> ResearchControlEventKind.STOP_CONFIRMED
			else -> ResearchControlEventKind.POLICY_STATE_CHANGED
		}
		ControlTransitionKind.LATE_INPUT_REJECTED,
		ControlTransitionKind.DUPLICATE_INPUT_REJECTED,
		-> ResearchControlEventKind.LOCATION_REJECTED
		ControlTransitionKind.ACQUISITION,
		ControlTransitionKind.OBSERVABILITY,
		ControlTransitionKind.ESTIMATOR,
		-> ResearchControlEventKind.POLICY_STATE_CHANGED
	}

	private fun DecisionAcquisitionMode.toResearchMode(): ResearchAcquisitionMode = when (this) {
		DecisionAcquisitionMode.DISABLED -> ResearchAcquisitionMode.DISABLED
		DecisionAcquisitionMode.PASSIVE -> ResearchAcquisitionMode.PASSIVE
		DecisionAcquisitionMode.LOW_POWER -> ResearchAcquisitionMode.LOW_POWER
		DecisionAcquisitionMode.BALANCED -> ResearchAcquisitionMode.BALANCED
		DecisionAcquisitionMode.HIGH_ACCURACY -> ResearchAcquisitionMode.HIGH_ACCURACY
		DecisionAcquisitionMode.PROBE -> ResearchAcquisitionMode.PROBE
	}

	private fun com.adsamcik.tracker.tracker.control.AcquisitionRequest.toResearchConfiguration(
		providerIdentity: String = "shadow",
	):
		ResearchAcquisitionConfiguration = ResearchAcquisitionConfiguration(
			mode = mode.toResearchMode(),
			intervalMs = intervalMs,
			minUpdateDistanceM = minDistanceMeters.toFloat(),
			requestGnssStatus = mode == DecisionAcquisitionMode.HIGH_ACCURACY || mode == DecisionAcquisitionMode.PROBE,
			providerIdentity = providerIdentity,
			priority = mode.name,
		)

	private fun HorizontalEstimatorDecision.toResearchDecision(): ResearchHorizontalEstimatorDecision = when (this) {
		HorizontalEstimatorDecision.INITIALIZED -> ResearchHorizontalEstimatorDecision.INITIALIZED
		HorizontalEstimatorDecision.ACCEPTED -> ResearchHorizontalEstimatorDecision.UPDATED
		HorizontalEstimatorDecision.REJECTED_OUTLIER -> ResearchHorizontalEstimatorDecision.REJECTED_MEASUREMENT
		HorizontalEstimatorDecision.REJECTED_OUT_OF_ORDER -> ResearchHorizontalEstimatorDecision.REORDERED
		HorizontalEstimatorDecision.GAP_RESET -> ResearchHorizontalEstimatorDecision.GAP_RESET
		HorizontalEstimatorDecision.IGNORED -> ResearchHorizontalEstimatorDecision.PREDICTED
	}

	private fun TrackingSessionOrigin?.toResearchTrackingMode(): ResearchTrackingMode = when (this) {
		TrackingSessionOrigin.USER -> ResearchTrackingMode.USER_INITIATED
		TrackingSessionOrigin.AUTOMATIC -> ResearchTrackingMode.AUTOMATIC
		null -> ResearchTrackingMode.UNKNOWN
	}

	private fun ControlAcquisitionApplyOutcome.toResearchOutcome(): ResearchAcquisitionApplyOutcome = when (this) {
		ControlAcquisitionApplyOutcome.APPLIED -> ResearchAcquisitionApplyOutcome.APPLIED
		ControlAcquisitionApplyOutcome.NO_CHANGE -> ResearchAcquisitionApplyOutcome.NO_CHANGE
		ControlAcquisitionApplyOutcome.FAILED -> ResearchAcquisitionApplyOutcome.FAILED
	}

	private fun ControlAcquisitionCommand.correlationId(): String = when (origin) {
		ControlAcquisitionCommandOrigin.DECISION -> "decision:$decisionLedgerSequence"
		ControlAcquisitionCommandOrigin.ADAPTER_PROBE_DEADLINE -> requestId
	}

	private fun stopCauseFor(output: ControlOutput, reason: String): ResearchTrackingStopCause = when {
		output.snapshot.sessionOrigin == TrackingSessionOrigin.USER -> ResearchTrackingStopCause.USER_REQUEST
		reason.contains("AUTOMATIC", ignoreCase = true) -> ResearchTrackingStopCause.AUTOMATIC_ACTIVITY
		reason.contains("INACTIVITY", ignoreCase = true) -> ResearchTrackingStopCause.AUTOMATIC_INACTIVITY
		else -> ResearchTrackingStopCause.SYSTEM
	}

	private companion object {
		const val DEFAULT_MAXIMUM_ENVELOPES = 10_000

		val CAPABILITIES = ResearchEvidenceCapabilities(
			controlTraceEvents = true,
			logicalTrackingLifecycle = true,
			acquisitionDecisions = true,
			horizontalEstimatorDecisions = true,
			replayDigests = true,
		)
	}
}
