package com.adsamcik.tracker.tracker.control

import kotlin.math.abs

/**
 * Android-free decision owner for the uncertainty-aware tracking architecture.
 *
 * The engine is intentionally single-threaded: callers serialize calls through the existing
 * tracker orchestrator mutex. Inputs are ordered through [EvidenceLedger], reducers are
 * independent, and the result is a named transition stream suitable for replay. In the first
 * rollout this engine is run in shadow mode; applying [AcquisitionRequest] is an adapter concern.
 */
class TrackingDecisionEngine(
	private val config: TrackingDecisionConfig = TrackingDecisionConfig(),
) {
	private var ledger = newLedger()
	private val reducer = TrackingDecisionReducer(config)
	private var estimator = newEstimator()
	private var activeClockDomainId: String? = null
	private var nextDecisionSequence = 1L
	private var clockDomainChangedBeforeNextInput = false

	/**
	 * Offers evidence and returns every decision that became safe to process. The first few inputs
	 * may remain buffered for [TrackingDecisionConfig.maxReorderNanos]; call [flush] at a shutdown or
	 * replay boundary.
	 */
	fun accept(input: ControlInput): List<ControlOutput> {
		val outputs = mutableListOf<ControlOutput>()
		var startedNewSession = false
		if (input.payload is ControlEvidence.SessionStarted) {
			outputs += ledger.flush().map { (_, buffered) -> decide(buffered) }
			if (reducer.canStartSession()) {
				ledger = newLedger()
				estimator = newEstimator()
				activeClockDomainId = input.clockDomainId
				clockDomainChangedBeforeNextInput = false
				startedNewSession = true
			}
		}
		if (!startedNewSession && activeClockDomainId != null && activeClockDomainId != input.clockDomainId) {
			outputs += ledger.flush().map { (_, buffered) -> decide(buffered) }
			ledger = newLedger()
			estimator = newEstimator()
			clockDomainChangedBeforeNextInput = true
		}
		activeClockDomainId = input.clockDomainId

		when (val offered = ledger.offer(input)) {
			is EvidenceLedger.OfferResult.Buffered -> {
				outputs += ledger.drainReady().map { (_, ready) -> decide(ready) }
			}

			is EvidenceLedger.OfferResult.Late -> {
				outputs += lateOutput(offered.lateInput)
			}

			is EvidenceLedger.OfferResult.Duplicate -> {
				outputs += duplicateOutput(offered.duplicateInput)
			}
		}
		return outputs
	}

	/** Emits buffered tail events in timestamp order. */
	fun flush(): List<ControlOutput> = ledger.flush().map { (_, input) -> decide(input) }

	fun pendingEvidenceCount(): Int = ledger.pendingCount()

	fun snapshot(): TrackingDecisionSnapshot = reducer.snapshot()

	private fun decide(input: ControlInput): ControlOutput {
		val transitions = mutableListOf<ControlTransition>()
		if (clockDomainChangedBeforeNextInput) {
			reducer.onClockDomainChanged(input.elapsedRealtimeNanos, transitions)
			clockDomainChangedBeforeNextInput = false
		}
		val estimatorResult = (input.payload as? ControlEvidence.LocationObservation)?.let {
			estimator.observe(it, input.elapsedRealtimeNanos)
		}
		reducer.reduce(input, estimatorResult, transitions)
		return ControlOutput(
			ledgerSequence = nextDecisionSequence++,
			input = input,
			transitions = transitions,
			snapshot = reducer.snapshot(),
			estimator = estimatorResult,
		)
	}

	private fun lateOutput(late: LateControlInput): ControlOutput = ControlOutput(
		ledgerSequence = nextDecisionSequence++,
		input = late.input,
		transitions = listOf(
			ControlTransition(
				kind = ControlTransitionKind.LATE_INPUT_REJECTED,
				from = null,
				to = null,
				reason = "OLDER_THAN_REORDER_WINDOW:${late.latestSeenElapsedRealtimeNanos}:${late.allowedReorderNanos}",
			),
		),
		snapshot = reducer.snapshot(),
	)

	private fun duplicateOutput(duplicate: DuplicateControlInput): ControlOutput = ControlOutput(
		ledgerSequence = nextDecisionSequence++,
		input = duplicate.input,
		transitions = listOf(
			ControlTransition(
				kind = ControlTransitionKind.DUPLICATE_INPUT_REJECTED,
				from = duplicate.originalLedgerSequence.toString(),
				to = null,
				reason = "DUPLICATE_LOCATION_SOURCE_EVENT_ID",
			),
		),
		snapshot = reducer.snapshot(),
	)

	private fun newEstimator(): HorizontalKalmanEstimator = HorizontalKalmanEstimator(
		gapNanos = config.estimatorGapNanos,
		nisThreshold = config.estimatorNisThreshold,
	)

	private fun newLedger(): EvidenceLedger = EvidenceLedger(
		maxReorderNanos = config.maxReorderNanos,
		maxDuplicateLocationSourceIds = config.maxDuplicateLocationSourceIds,
	)
}

private class TrackingDecisionReducer(
	private val config: TrackingDecisionConfig,
) {
	private var logicalTrackingId: LogicalTrackingId? = null
	private var sessionOrigin: TrackingSessionOrigin? = null
	private var lifecycle = LogicalLifecycleState.IDLE
	private var motion = MotionState.UNKNOWN
	private var observability = LocationObservabilityState.UNKNOWN
	private var continuity = TrackingContinuity.CONTINUOUS
	private var acquisition = disabledRequest("INITIAL")

	private var startedAtNanos: Long? = null
	private var stopCandidateAtNanos: Long? = null
	private var lastMovingEvidenceNanos: Long? = null
	private var lastStationaryEvidenceNanos: Long? = null
	private var lastReliableLocationNanos: Long? = null
	private var lastAcceptedLocationNanos: Long? = null
	private var preferredMode = LocationAcquisitionMode.BALANCED
	private var locationEnabled = true
	private var probeStartedAtNanos: Long? = null
	private var lastProbeFinishedAtNanos: Long? = null
	private var acquisitionChangedAtNanos = Long.MIN_VALUE
	private val dutyBudget = RollingDutyBudget(config.dutyWindowNanos)

	fun canStartSession(): Boolean =
		lifecycle == LogicalLifecycleState.IDLE || lifecycle == LogicalLifecycleState.FINISHED

	fun onClockDomainChanged(
		nowNanos: Long,
		transitions: MutableList<ControlTransition>,
	) {
		if (lifecycle == LogicalLifecycleState.IDLE || lifecycle == LogicalLifecycleState.FINISHED) return
		transitionContinuity(TrackingContinuity.GAP_OPEN, "CLOCK_DOMAIN_CHANGED", transitions)
		lastMovingEvidenceNanos = null
		lastStationaryEvidenceNanos = null
		lastReliableLocationNanos = null
		lastAcceptedLocationNanos = null
		stopCandidateAtNanos = null
		probeStartedAtNanos = null
		lastProbeFinishedAtNanos = nowNanos
		dutyBudget.clear()
		acquisitionChangedAtNanos = Long.MIN_VALUE
		transitionMotion(MotionState.UNKNOWN, "CLOCK_DOMAIN_CHANGED", transitions)
		transitionObservability(LocationObservabilityState.UNKNOWN, "CLOCK_DOMAIN_CHANGED", transitions)
		applyAcquisition(disabledRequest("CLOCK_DOMAIN_CHANGED"), nowNanos, transitions, force = true)
	}

	fun reduce(
		input: ControlInput,
		estimator: HorizontalEstimatorResult?,
		transitions: MutableList<ControlTransition>,
	) {
		val now = input.elapsedRealtimeNanos
		expireTimeBoundedState(now, transitions)

		when (val evidence = input.payload) {
			is ControlEvidence.SessionStarted -> onSessionStarted(evidence, now, transitions)
			is ControlEvidence.PauseRequested -> onPauseRequested(evidence, transitions)
			is ControlEvidence.ResumeRequested -> onResumeRequested(evidence, transitions)
			is ControlEvidence.FinishRequested -> onFinishRequested(evidence, transitions)
			is ControlEvidence.AutomaticStopEvidence -> onAutomaticStopEvidence(evidence, now, transitions)
			is ControlEvidence.ActivityEvidence -> onActivityEvidence(evidence, now)
			is ControlEvidence.StepEvidence -> onStepEvidence(evidence, now)
			is ControlEvidence.LocationObservation -> onLocationObservation(evidence, now, estimator, transitions)
			is ControlEvidence.CuratedLocationDecision -> Unit
			is ControlEvidence.PolicyIntent -> onPolicyIntent(evidence)
			is ControlEvidence.ProviderAvailability -> onProviderAvailability(evidence, transitions)
			is ControlEvidence.ProbeResult -> onProbeResult(evidence, now, transitions)
			is ControlEvidence.Tick -> Unit
		}

		resolveAutomaticStop(now, transitions)
		recomputeMotion(now, transitions)
		recomputeObservability(now, transitions)
		recomputeAcquisition(now, transitions)
	}

	fun snapshot(): TrackingDecisionSnapshot = TrackingDecisionSnapshot(
		logicalTrackingId = logicalTrackingId,
		sessionOrigin = sessionOrigin,
		lifecycle = lifecycle,
		motion = motion,
		observability = observability,
		acquisition = acquisition,
		continuity = continuity,
	)

	private fun onSessionStarted(
		evidence: ControlEvidence.SessionStarted,
		now: Long,
		transitions: MutableList<ControlTransition>,
	) {
		if (!canStartSession()) return
		logicalTrackingId = evidence.logicalTrackingId
		sessionOrigin = evidence.origin
		motion = MotionState.UNKNOWN
		observability = LocationObservabilityState.UNKNOWN
		continuity = TrackingContinuity.CONTINUOUS
		acquisition = disabledRequest("SESSION_RESET")
		startedAtNanos = now
		stopCandidateAtNanos = null
		lastMovingEvidenceNanos = null
		lastStationaryEvidenceNanos = null
		lastReliableLocationNanos = null
		lastAcceptedLocationNanos = null
		preferredMode = LocationAcquisitionMode.BALANCED
		locationEnabled = true
		probeStartedAtNanos = null
		lastProbeFinishedAtNanos = null
		acquisitionChangedAtNanos = Long.MIN_VALUE
		dutyBudget.clear()
		transitionLifecycle(LogicalLifecycleState.ACTIVE, "SESSION_STARTED", transitions)
	}

	private fun onPauseRequested(
		evidence: ControlEvidence.PauseRequested,
		transitions: MutableList<ControlTransition>,
	) {
		if (lifecycle == LogicalLifecycleState.ACTIVE || lifecycle == LogicalLifecycleState.STOP_CANDIDATE) {
			stopCandidateAtNanos = null
			transitionLifecycle(LogicalLifecycleState.PAUSED, "PAUSE:${evidence.reason}", transitions)
		}
	}

	private fun onResumeRequested(
		evidence: ControlEvidence.ResumeRequested,
		transitions: MutableList<ControlTransition>,
	) {
		if (lifecycle == LogicalLifecycleState.PAUSED) {
			transitionLifecycle(LogicalLifecycleState.ACTIVE, "RESUME:${evidence.reason}", transitions)
		}
	}

	private fun onFinishRequested(
		evidence: ControlEvidence.FinishRequested,
		transitions: MutableList<ControlTransition>,
	) {
		if (lifecycle != LogicalLifecycleState.IDLE && lifecycle != LogicalLifecycleState.FINISHED) {
			stopCandidateAtNanos = null
			transitionLifecycle(LogicalLifecycleState.FINISHED, "FINISH:${evidence.reason}", transitions)
		}
	}

	private fun onAutomaticStopEvidence(
		evidence: ControlEvidence.AutomaticStopEvidence,
		now: Long,
		transitions: MutableList<ControlTransition>,
	) {
		if (sessionOrigin != TrackingSessionOrigin.AUTOMATIC || lifecycle != LogicalLifecycleState.ACTIVE) return
		if (evidence.confidence < MINIMUM_AUTOMATIC_STOP_CONFIDENCE) return
		stopCandidateAtNanos = now
		transitionLifecycle(
			LogicalLifecycleState.STOP_CANDIDATE,
			"AUTOMATIC_STOP_CANDIDATE:${evidence.reason}:${evidence.confidence}",
			transitions,
		)
	}

	private fun onActivityEvidence(evidence: ControlEvidence.ActivityEvidence, now: Long) {
		if (evidence.confidence < MINIMUM_MOTION_CONFIDENCE) return
		when (evidence.category) {
			ActivityCategory.MOVING -> lastMovingEvidenceNanos = now
			ActivityCategory.STATIONARY -> lastStationaryEvidenceNanos = now
			ActivityCategory.UNKNOWN -> Unit
		}
	}

	private fun onStepEvidence(evidence: ControlEvidence.StepEvidence, now: Long) {
		if (evidence.delta > 0) lastMovingEvidenceNanos = now
	}

	private fun onLocationObservation(
		evidence: ControlEvidence.LocationObservation,
		now: Long,
		estimator: HorizontalEstimatorResult?,
		transitions: MutableList<ControlTransition>,
	) {
		if (!evidence.ingressAccepted || evidence.position == null) {
			transitionObservability(LocationObservabilityState.DEGRADED, "LOCATION_INGRESS_REJECTED", transitions)
			return
		}
		if (estimator?.gapOpened == true || lastAcceptedLocationNanos?.let { now - it > config.continuityGapNanos } == true) {
			transitionContinuity(TrackingContinuity.GAP_OPEN, "LOCATION_GAP", transitions)
		}
		val estimatorAccepted = estimator?.decision == HorizontalEstimatorDecision.ACCEPTED ||
			estimator?.decision == HorizontalEstimatorDecision.INITIALIZED ||
			estimator?.decision == HorizontalEstimatorDecision.GAP_RESET
		if (estimatorAccepted) {
			// A raw ingress delivery that the estimator rejects must not become a continuity
			// anchor. Otherwise a series of teleport/outlier fixes could conceal a real gap and
			// let downstream distance logic bridge unknown travel.
			lastAcceptedLocationNanos = now
			lastReliableLocationNanos = now
			val qualityReason = when {
				evidence.horizontalAccuracyMeters == null -> "LOCATION_WITH_DEFAULT_UNCERTAINTY"
				evidence.horizontalAccuracyMeters <= RELIABLE_ACCURACY_METERS -> "LOCATION_RELIABLE"
				else -> "LOCATION_HIGH_UNCERTAINTY"
			}
			transitionObservability(
				if (evidence.horizontalAccuracyMeters == null ||
					evidence.horizontalAccuracyMeters <= RELIABLE_ACCURACY_METERS
				) LocationObservabilityState.AVAILABLE else LocationObservabilityState.DEGRADED,
				qualityReason,
				transitions,
			)
			evidence.speedMetersPerSecond?.let { speed ->
				when {
					speed >= MOVING_SPEED_METERS_PER_SECOND -> lastMovingEvidenceNanos = now
					speed <= STATIONARY_SPEED_METERS_PER_SECOND -> lastStationaryEvidenceNanos = now
				}
			}
			if (continuity == TrackingContinuity.GAP_OPEN) {
				transitionContinuity(TrackingContinuity.CONTINUOUS, "LOCATION_GAP_RECOVERED", transitions)
			}
		} else if (estimator?.decision == HorizontalEstimatorDecision.REJECTED_OUTLIER) {
			transitionObservability(LocationObservabilityState.DEGRADED, "ESTIMATOR_OUTLIER", transitions)
		}
	}

	private fun onPolicyIntent(evidence: ControlEvidence.PolicyIntent) {
		locationEnabled = evidence.locationEnabled
		preferredMode = evidence.preferredMode
	}

	private fun onProviderAvailability(
		evidence: ControlEvidence.ProviderAvailability,
		transitions: MutableList<ControlTransition>,
	) {
		if (!evidence.available) {
			transitionObservability(LocationObservabilityState.DEGRADED, "PROVIDER_UNAVAILABLE:${evidence.reason}", transitions)
		}
	}

	private fun onProbeResult(
		evidence: ControlEvidence.ProbeResult,
		now: Long,
		transitions: MutableList<ControlTransition>,
	) {
		if (probeStartedAtNanos == null) return
		probeStartedAtNanos = null
		lastProbeFinishedAtNanos = now
		if (!evidence.succeeded) {
			transitionObservability(LocationObservabilityState.DEGRADED, "PROBE_FAILED:${evidence.reason}", transitions)
		}
	}

	private fun expireTimeBoundedState(now: Long, transitions: MutableList<ControlTransition>) {
		if (
			probeStartedAtNanos?.let { now - it >= config.probeDurationNanos } == true
		) {
			lastProbeFinishedAtNanos = now
			probeStartedAtNanos = null
		}
	}

	private fun resolveAutomaticStop(now: Long, transitions: MutableList<ControlTransition>) {
		if (lifecycle != LogicalLifecycleState.STOP_CANDIDATE) return
		if (lastMovingEvidenceNanos?.let { it >= stopCandidateAtNanos ?: Long.MAX_VALUE } == true) {
			stopCandidateAtNanos = null
			transitionLifecycle(LogicalLifecycleState.ACTIVE, "AUTOMATIC_STOP_CANCELLED_BY_MOVEMENT", transitions)
			return
		}
		val candidateAt = stopCandidateAtNanos ?: return
		if (now - candidateAt >= config.automaticStopGraceNanos) {
			stopCandidateAtNanos = null
			transitionLifecycle(LogicalLifecycleState.FINISHED, "AUTOMATIC_STOP_GRACE_EXPIRED", transitions)
		}
	}

	private fun recomputeMotion(now: Long, transitions: MutableList<ControlTransition>) {
		val movingAt = lastMovingEvidenceNanos?.takeIf { now - it <= config.evidenceTtlNanos }
		val stationaryAt = lastStationaryEvidenceNanos?.takeIf { now - it <= config.evidenceTtlNanos }
		val next = when {
			movingAt != null && stationaryAt != null &&
				abs(movingAt - stationaryAt) <= config.evidenceTtlNanos / 2L -> MotionState.UNCERTAIN
			movingAt != null && (stationaryAt == null || movingAt > stationaryAt) -> MotionState.MOVING
			stationaryAt != null -> MotionState.STATIONARY
			else -> MotionState.UNKNOWN
		}
		transitionMotion(next, "EVIDENCE_TTL", transitions)
	}

	private fun recomputeObservability(now: Long, transitions: MutableList<ControlTransition>) {
		if (lifecycle == LogicalLifecycleState.IDLE || lifecycle == LogicalLifecycleState.FINISHED) return
		val anchor = lastReliableLocationNanos ?: startedAtNanos
		if (anchor != null && now - anchor >= config.observabilityTimeoutNanos) {
			transitionObservability(LocationObservabilityState.UNAVAILABLE, "OBSERVABILITY_TIMEOUT", transitions)
		}
	}

	private fun recomputeAcquisition(now: Long, transitions: MutableList<ControlTransition>) {
		val desired = desiredAcquisition(now)
		val budgetSafe = if (desired.mode.isActiveAcquisition) {
			dutyBudget.usedNanos(now) < config.maximumActiveDutyNanos
		} else {
			true
		}
		val budgeted = if (budgetSafe) desired else passiveRequest("ACTIVE_DUTY_BUDGET_EXHAUSTED")
		val force = budgeted.mode == LocationAcquisitionMode.DISABLED ||
			budgeted.mode.powerRank < acquisition.mode.powerRank
		applyAcquisition(budgeted, now, transitions, force)
	}

	private fun desiredAcquisition(now: Long): AcquisitionRequest {
		if (
			lifecycle != LogicalLifecycleState.ACTIVE &&
			lifecycle != LogicalLifecycleState.STOP_CANDIDATE ||
			!locationEnabled || preferredMode == LocationAcquisitionMode.DISABLED
		) {
			return disabledRequest("LIFECYCLE_OR_LOCATION_DISABLED")
		}
		if (lifecycle == LogicalLifecycleState.STOP_CANDIDATE) {
			return passiveRequest("AUTOMATIC_STOP_CANDIDATE")
		}
		if (probeStartedAtNanos != null) {
			return probeRequest("PROBE_IN_PROGRESS")
		}
		if (
			preferredMode == LocationAcquisitionMode.PROBE &&
			lastProbeFinishedAtNanos?.let { now - it < config.probeCooldownNanos } == true
		) {
			return passiveRequest("PROBE_COOLDOWN")
		}
		if (
			(observability == LocationObservabilityState.UNAVAILABLE ||
				observability == LocationObservabilityState.DEGRADED) &&
			motion == MotionState.MOVING
		) {
			val probeFinishedAt = lastProbeFinishedAtNanos
			if (probeFinishedAt != null && now - probeFinishedAt < config.probeCooldownNanos) {
				return passiveRequest("PROBE_COOLDOWN")
			}
			return probeRequest("MOVING_WITH_${observability.name}_LOCATION")
		}
		return when (motion) {
			MotionState.STATIONARY -> passiveRequest("STATIONARY")
			MotionState.MOVING -> if (preferredMode == LocationAcquisitionMode.PROBE) {
				probeRequest("MOVING")
			} else {
				requestForMode(preferredMode, "MOVING")
			}
			MotionState.UNCERTAIN,
			MotionState.UNKNOWN,
			-> lowPowerRequest("MOTION_UNCERTAIN")
		}
	}

	private fun applyAcquisition(
		desired: AcquisitionRequest,
		now: Long,
		transitions: MutableList<ControlTransition>,
		force: Boolean,
	) {
		if (desired == acquisition) return
		if (
			!force && acquisitionChangedAtNanos != Long.MIN_VALUE &&
			now - acquisitionChangedAtNanos < config.minimumAcquisitionDwellNanos
		) return
		val previous = acquisition
		dutyBudget.onAcquisitionChanged(previous.mode, desired.mode, now)
		acquisition = desired
		acquisitionChangedAtNanos = now
		if (desired.mode == LocationAcquisitionMode.PROBE && previous.mode != LocationAcquisitionMode.PROBE) {
			probeStartedAtNanos = now
		}
		if (previous.mode == LocationAcquisitionMode.PROBE && desired.mode != LocationAcquisitionMode.PROBE) {
			probeStartedAtNanos = null
			lastProbeFinishedAtNanos = now
		}
		transitions += ControlTransition(
			kind = ControlTransitionKind.ACQUISITION,
			from = previous.mode.name,
			to = desired.mode.name,
			reason = desired.reason,
		)
	}

	private fun transitionLifecycle(
		next: LogicalLifecycleState,
		reason: String,
		transitions: MutableList<ControlTransition>,
	) {
		if (lifecycle == next) return
		transitions += ControlTransition(ControlTransitionKind.LIFECYCLE, lifecycle.name, next.name, reason)
		lifecycle = next
	}

	private fun transitionMotion(
		next: MotionState,
		reason: String,
		transitions: MutableList<ControlTransition>,
	) {
		if (motion == next) return
		transitions += ControlTransition(ControlTransitionKind.MOTION, motion.name, next.name, reason)
		motion = next
	}

	private fun transitionObservability(
		next: LocationObservabilityState,
		reason: String,
		transitions: MutableList<ControlTransition>,
	) {
		if (observability == next) return
		transitions += ControlTransition(ControlTransitionKind.OBSERVABILITY, observability.name, next.name, reason)
		observability = next
	}

	private fun transitionContinuity(
		next: TrackingContinuity,
		reason: String,
		transitions: MutableList<ControlTransition>,
	) {
		if (continuity == next) return
		transitions += ControlTransition(ControlTransitionKind.CONTINUITY, continuity.name, next.name, reason)
		continuity = next
	}

	private fun probeRequest(reason: String) = AcquisitionRequest(
		mode = LocationAcquisitionMode.PROBE,
		intervalMs = 5_000L,
		minDistanceMeters = 0,
		probeDurationMs = config.probeDurationNanos / NANOS_PER_MILLISECOND +
			if (config.probeDurationNanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L,
		reason = reason,
	)

	private companion object {
		const val MINIMUM_AUTOMATIC_STOP_CONFIDENCE = 70
		const val MINIMUM_MOTION_CONFIDENCE = 50
		const val RELIABLE_ACCURACY_METERS = 100.0
		const val MOVING_SPEED_METERS_PER_SECOND = 0.8
		const val STATIONARY_SPEED_METERS_PER_SECOND = 0.2

		fun disabledRequest(reason: String) = AcquisitionRequest(
			mode = LocationAcquisitionMode.DISABLED,
			intervalMs = 0L,
			minDistanceMeters = 0,
			reason = reason,
		)

		fun passiveRequest(reason: String) = AcquisitionRequest(
			mode = LocationAcquisitionMode.PASSIVE,
			intervalMs = 60_000L,
			minDistanceMeters = 0,
			reason = reason,
		)

		fun lowPowerRequest(reason: String) = AcquisitionRequest(
			mode = LocationAcquisitionMode.LOW_POWER,
			intervalMs = 60_000L,
			minDistanceMeters = 25,
			reason = reason,
		)

		fun requestForMode(mode: LocationAcquisitionMode, reason: String): AcquisitionRequest = when (mode) {
			LocationAcquisitionMode.DISABLED -> disabledRequest(reason)
			LocationAcquisitionMode.PASSIVE -> passiveRequest(reason)
			LocationAcquisitionMode.LOW_POWER -> lowPowerRequest(reason)
			LocationAcquisitionMode.BALANCED -> AcquisitionRequest(
				mode = LocationAcquisitionMode.BALANCED,
				intervalMs = 15_000L,
				minDistanceMeters = 10,
				reason = reason,
			)
			LocationAcquisitionMode.HIGH_ACCURACY -> AcquisitionRequest(
				mode = LocationAcquisitionMode.HIGH_ACCURACY,
				intervalMs = 5_000L,
				minDistanceMeters = 5,
				reason = reason,
			)
			// Preferred probes are handled by [desiredAcquisition] so they use the configured
			// bounded duration rather than a hidden hard-coded timeout.
			LocationAcquisitionMode.PROBE -> error("Probe requests require TrackingDecisionConfig")
		}

		const val NANOS_PER_MILLISECOND = 1_000_000L
	}
}

private class RollingDutyBudget(
	private val windowNanos: Long,
) {
	private data class ActiveInterval(val startNanos: Long, val endNanos: Long)

	private val completed = mutableListOf<ActiveInterval>()
	private var activeSinceNanos: Long? = null

	fun onAcquisitionChanged(
		previous: LocationAcquisitionMode,
		next: LocationAcquisitionMode,
		nowNanos: Long,
	) {
		if (previous.isActiveAcquisition && !next.isActiveAcquisition) {
			activeSinceNanos?.let { start ->
				if (nowNanos > start) completed += ActiveInterval(start, nowNanos)
			}
			activeSinceNanos = null
		} else if (!previous.isActiveAcquisition && next.isActiveAcquisition) {
			activeSinceNanos = nowNanos
		}
		prune(nowNanos)
	}

	fun usedNanos(nowNanos: Long): Long {
		prune(nowNanos)
		val cutoff = nowNanos - windowNanos
		var used = completed.sumOf { interval ->
			(interval.endNanos - maxOf(interval.startNanos, cutoff)).coerceAtLeast(0L)
		}
		activeSinceNanos?.let { start ->
			used += (nowNanos - maxOf(start, cutoff)).coerceAtLeast(0L)
		}
		return used
	}

	fun clear() {
		completed.clear()
		activeSinceNanos = null
	}

	private fun prune(nowNanos: Long) {
		val cutoff = nowNanos - windowNanos
		completed.removeAll { it.endNanos <= cutoff }
	}
}

private val LocationAcquisitionMode.isActiveAcquisition: Boolean
	get() = this == LocationAcquisitionMode.LOW_POWER ||
		this == LocationAcquisitionMode.BALANCED ||
		this == LocationAcquisitionMode.HIGH_ACCURACY ||
		this == LocationAcquisitionMode.PROBE

private val LocationAcquisitionMode.powerRank: Int
	get() = when (this) {
		LocationAcquisitionMode.DISABLED -> 0
		LocationAcquisitionMode.PASSIVE -> 1
		LocationAcquisitionMode.LOW_POWER -> 2
		LocationAcquisitionMode.BALANCED -> 3
		LocationAcquisitionMode.HIGH_ACCURACY,
		LocationAcquisitionMode.PROBE,
		-> 4
	}
