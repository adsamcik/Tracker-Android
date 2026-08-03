package com.adsamcik.tracker.stats.engine.plane

/**
 * Minimal streaming median filter over a fixed-size sliding window, same purpose as
 * [com.adsamcik.tracker.stats.engine.sailing.MedianSmoother] — duplicated locally (rather than
 * shared) to keep the plane/sailing/ski detection packages independent of each other.
 */
internal class MedianSmoother(private val windowSize: Int) {
	private val window = ArrayDeque<Float>()

	fun add(value: Float): Float {
		if (windowSize <= 1) return value
		window.addLast(value)
		if (window.size > windowSize) window.removeFirst()
		return window.sorted()[window.size / 2]
	}

	fun reset() {
		window.clear()
	}
}

/**
 * Computes a smoothed vertical rate (m/s) from a stream of (possibly noisy) altitude samples:
 * a median filter to reject spikes, then an exponential moving average over the instantaneous
 * rate between consecutive smoothed samples. Mirrors
 * [com.adsamcik.tracker.stats.engine.ski.StreamingVerticalRateCalculator]'s approach in pure
 * Kotlin (no `java.util.Arrays`), which is why this lives in `commonMain` rather than `androidMain`.
 */
internal class StreamingVerticalRateCalculator(
	medianWindowSize: Int,
	private val emaAlpha: Float,
) {
	private val altitudeSmoother = MedianSmoother(medianWindowSize)
	private var previousSmoothedAltitude: Float? = null
	private var previousTimeMs: Long? = null
	private var smoothedRate: Float? = null

	/** Returns the current smoothed vertical rate (m/s), or null until two valid ordered samples have been seen. */
	fun onNewSample(timeMs: Long, altitudeM: Float): Float? {
		if (timeMs < 0L || !altitudeM.isFinite() || (previousTimeMs != null && timeMs <= previousTimeMs!!)) {
			return null
		}

		val smoothedAltitude = altitudeSmoother.add(altitudeM)
		val previousAltitude = previousSmoothedAltitude
		val previousTime = previousTimeMs

		val result: Float? = if (previousAltitude != null && previousTime != null) {
			val dtSeconds = (timeMs - previousTime) / 1000f
			val instantRate = (smoothedAltitude - previousAltitude) / dtSeconds
			val previousEma = smoothedRate
			val newEma = if (previousEma == null) instantRate else previousEma + emaAlpha * (instantRate - previousEma)
			smoothedRate = newEma
			newEma
		} else {
			null
		}

		previousSmoothedAltitude = smoothedAltitude
		previousTimeMs = timeMs
		return result
	}

	fun reset() {
		altitudeSmoother.reset()
		previousSmoothedAltitude = null
		previousTimeMs = null
		smoothedRate = null
	}
}

/**
 * Real-time plane/flight state for the current moment.
 */
data class RealTimePlaneState(
	val state: PlaneState,
	val stateEntryTimeMs: Long,
	val stateDurationMs: Long,
	val totalAirborneDurationMs: Long,
	val isConfirmedFlight: Boolean,
	val currentVerticalRateMps: Float,
	val maxSpeedMps: Float,
	val cruiseEvidence: CruiseEvidence = CruiseEvidence.UNKNOWN,
)

/**
 * Callback for plane state transitions.
 */
fun interface PlaneStateListener {
	fun onStateChanged(previousState: PlaneState, newState: RealTimePlaneState)
}

/**
 * Real-time plane/flight detector that processes streaming barometric altitude (+ optional GPS
 * speed and step rate) samples.
 *
 * Maintains internal state from barometric (cabin-pressure) altitude, classifies the current
 * phase (idle/climbing/cruising/descending/walking), and accumulates cumulative airborne
 * duration. A session is confirmed only after a qualified barometric climb and enough subsequent
 * airborne time to cross [PlaneDetectionConfig.minFlightDurationForConfirmationMs].
 *
 * Pure Kotlin, no platform dependencies. Thread-safe via synchronized blocks.
 */
class RealTimePlaneDetector(
	private val config: PlaneDetectionConfig = PlaneDetectionConfig(),
) {
	private val verticalRateCalc = StreamingVerticalRateCalculator(
		medianWindowSize = config.baroMedianWindow,
		emaAlpha = config.verticalRateEmaAlpha,
	)
	private val stateMachine = PlaneStateMachine(config)

	private val lock = Any()

	// Current confirmed state (after min-duration)
	private var confirmedState: PlaneState = PlaneState.IDLE
	private var confirmedStateEntryMs: Long = 0L

	// Pending state (before min-duration confirmation)
	private var pendingState: PlaneState = PlaneState.IDLE
	private var pendingStateEntryMs: Long = 0L

	// Session accumulators
	/** Sum of durations of airborne segments that have already ENDED (transitioned away from). */
	private var accumulatedAirborneDurationMs: Long = 0L
	/** Airborne duration accrued only after a barometric climb has been qualified. */
	private var qualifiedAirborneDurationMs: Long = 0L
	private var qualifiedAirborneSegmentStartMs: Long? = null
	private var maxSpeedMps: Float = 0f
	private var cruiseEvidence: CruiseEvidence = CruiseEvidence.UNKNOWN
	private var hasQualifiedClimb: Boolean = false
	private var climbStartAltitudeM: Float? = null

	// Latest sensor values
	private var lastVerticalRateMps: Float = 0f
	private var lastSampleTimeMs: Long = 0L
	private var lastBarometricSampleTimeMs: Long = 0L
	private var hasAcceptedSample: Boolean = false

	// Listener
	private var listener: PlaneStateListener? = null

	/**
	 * Set a listener for state transitions.
	 */
	fun setListener(listener: PlaneStateListener?) {
		this.listener = listener
	}

	private fun PlaneState.isAirborne(): Boolean =
		this == PlaneState.CLIMBING || this == PlaneState.CRUISING || this == PlaneState.DESCENDING

	/**
	 * Get the current real-time plane state snapshot.
	 */
	fun getCurrentState(): RealTimePlaneState = synchronized(lock) {
		val ongoingAirborneMs = if (confirmedState.isAirborne() && hasAcceptedSample) {
			lastSampleTimeMs - confirmedStateEntryMs
		} else {
			0L
		}
		val liveTotalAirborneDurationMs = accumulatedAirborneDurationMs + ongoingAirborneMs
		val liveQualifiedAirborneDurationMs = qualifiedAirborneDurationMs +
			(qualifiedAirborneSegmentStartMs?.let { lastSampleTimeMs - it } ?: 0L)
		RealTimePlaneState(
			state = confirmedState,
			stateEntryTimeMs = confirmedStateEntryMs,
			stateDurationMs = if (hasAcceptedSample) {
				lastSampleTimeMs - confirmedStateEntryMs
			} else {
				0L
			},
			totalAirborneDurationMs = liveTotalAirborneDurationMs,
			isConfirmedFlight = hasQualifiedClimb &&
				liveQualifiedAirborneDurationMs >= config.minFlightDurationForConfirmationMs,
			currentVerticalRateMps = lastVerticalRateMps,
			maxSpeedMps = maxSpeedMps,
			cruiseEvidence = cruiseEvidence,
		)
	}

	/**
	 * Process a new barometric altitude sample (+ optional GPS speed / step rate).
	 *
	 * Call this on every collection cycle that has barometric data.
	 *
	 * @param timeMs monotonic sample timestamp (`elapsedRealtimeNanos / 1_000_000`)
	 * @param altitudeM barometric (cabin-pressure) altitude in meters
	 * @param speedMps GPS ground speed in m/s (0 if unavailable — commonly the case at cruise altitude)
	 * @param stepRatePerMin current step rate (0 if unavailable)
	 * @return current [RealTimePlaneState], or null if not enough altitude data yet
	 */
	fun onSample(
		timeMs: Long,
		altitudeM: Float,
		speedMps: Float = 0f,
		stepRatePerMin: Float = 0f,
	): RealTimePlaneState? = synchronized(lock) {
		if (!speedMps.isFinite() || !stepRatePerMin.isFinite()) return null
		val stateAfterGap = if (
			hasAcceptedSample &&
			timeMs > lastBarometricSampleTimeMs &&
			timeMs - lastBarometricSampleTimeMs >= config.pressureFreshnessTimeoutMs
		) {
			onDataGap(timeMs)
		} else {
			null
		}
		val verticalRate = verticalRateCalc.onNewSample(timeMs, altitudeM) ?: return stateAfterGap
		lastVerticalRateMps = verticalRate
		lastSampleTimeMs = timeMs
		lastBarometricSampleTimeMs = timeMs
		hasAcceptedSample = true
		updateCruiseEvidence(verticalRate, altitudeM, speedMps, timeMs)

		val signal = PlaneSignal(
			timeMs = timeMs,
			verticalRateMps = verticalRate,
			speedMps = speedMps,
			stepRatePerMin = stepRatePerMin,
			cruiseEvidence = cruiseEvidence,
		)

		// Unlike ski/sailing's real-time detectors (which gate their own pending/confirmed timer
		// on a context-free raw classification), plane detection needs classifyWithHysteresis
		// here because CRUISING is only reachable with context (see PlaneStateMachine kdoc):
		// the min-duration pending timer below still guards against flicker on top of it.
		updateStateWithHysteresis(signal, timeMs)

		if (confirmedState.isAirborne()) {
			maxSpeedMps = maxOf(maxSpeedMps, speedMps)
		}

		getCurrentState()
	}

	/**
	 * Record a collection cycle without barometric data. A sustained gap closes the live phase as
	 * UNKNOWN rather than treating the missing evidence as a landing or rejecting a prior climb.
	 */
	fun onDataGap(timeMs: Long): RealTimePlaneState = synchronized(lock) {
		if (!hasAcceptedSample || timeMs <= lastSampleTimeMs) return@synchronized getCurrentState()
		if (timeMs - lastBarometricSampleTimeMs < config.pressureFreshnessTimeoutMs) {
			lastSampleTimeMs = timeMs
			return@synchronized getCurrentState()
		}

		val expiryTimeMs = lastBarometricSampleTimeMs + config.pressureFreshnessTimeoutMs
		lastSampleTimeMs = expiryTimeMs
		transitionTo(PlaneState.UNKNOWN, expiryTimeMs)
		pendingState = PlaneState.UNKNOWN
		pendingStateEntryMs = expiryTimeMs
		lastSampleTimeMs = timeMs
		lastVerticalRateMps = 0f
		lastBarometricSampleTimeMs = 0L
		hasAcceptedSample = false
		verticalRateCalc.reset()
		climbStartAltitudeM = null
		getCurrentState()
	}

	/**
	 * Reset all state. Call when starting a new tracking session.
	 */
	fun reset() = synchronized(lock) {
		verticalRateCalc.reset()
		confirmedState = PlaneState.IDLE
		confirmedStateEntryMs = 0L
		pendingState = PlaneState.IDLE
		pendingStateEntryMs = 0L
		accumulatedAirborneDurationMs = 0L
		qualifiedAirborneDurationMs = 0L
		qualifiedAirborneSegmentStartMs = null
		maxSpeedMps = 0f
		cruiseEvidence = CruiseEvidence.UNKNOWN
		hasQualifiedClimb = false
		climbStartAltitudeM = null
		lastVerticalRateMps = 0f
		lastSampleTimeMs = 0L
		lastBarometricSampleTimeMs = 0L
		hasAcceptedSample = false
	}

	private fun updateStateWithHysteresis(signal: PlaneSignal, timeMs: Long) {
		val candidate = stateMachine.classifyWithHysteresis(signal, confirmedState)
		if (candidate != pendingState) {
			// New candidate state — start the pending timer
			pendingState = candidate
			pendingStateEntryMs = timeMs
		}

		// Check if pending state has been held long enough
		val pendingDurationMs = timeMs - pendingStateEntryMs
		if (pendingState != confirmedState && pendingDurationMs >= config.minStateDurationMs) {
			transitionTo(pendingState, timeMs)
		}
	}

	private fun transitionTo(newState: PlaneState, timeMs: Long) {
		val previousState = confirmedState

		// Fold the segment we're leaving into the completed accumulator
		if (previousState.isAirborne() && hasAcceptedSample) {
			accumulatedAirborneDurationMs += (timeMs - confirmedStateEntryMs)
		}
		qualifiedAirborneSegmentStartMs?.let { startMs ->
			qualifiedAirborneDurationMs += timeMs - startMs
			qualifiedAirborneSegmentStartMs = null
		}

		confirmedState = newState
		confirmedStateEntryMs = timeMs
		if (newState.isAirborne() && cruiseEvidence == CruiseEvidence.QUALIFIED_CLIMB) {
			qualifiedAirborneSegmentStartMs = timeMs
		}

		listener?.onStateChanged(previousState, getCurrentState())
	}

	private fun updateCruiseEvidence(
		verticalRateMps: Float,
		altitudeM: Float,
		speedMps: Float,
		timeMs: Long,
	) {
		if (cruiseEvidence == CruiseEvidence.QUALIFIED_CLIMB) return

		if (verticalRateMps >= config.climbEnterVerticalRateMps) {
			val climbStart = climbStartAltitudeM ?: altitudeM.also { climbStartAltitudeM = it }
			if (altitudeM - climbStart >= config.minQualifiedClimbAltitudeGainM) {
				cruiseEvidence = CruiseEvidence.QUALIFIED_CLIMB
				hasQualifiedClimb = true
				if (confirmedState.isAirborne()) {
					qualifiedAirborneSegmentStartMs = timeMs
				}
			}
		} else {
			climbStartAltitudeM = null
			cruiseEvidence = if (speedMps >= config.cruiseMinSpeedMps) {
				CruiseEvidence.SPEED_ONLY
			} else {
				CruiseEvidence.UNKNOWN
			}
		}
	}
}
