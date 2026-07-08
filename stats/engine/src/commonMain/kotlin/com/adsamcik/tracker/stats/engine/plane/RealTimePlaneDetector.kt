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
	private var previousTimeMs: Long = 0L
	private var smoothedRate: Float? = null

	/** Returns the current smoothed vertical rate (m/s), or null until at least 2 samples have been seen. */
	fun onNewSample(timeMs: Long, altitudeM: Float): Float? {
		val smoothedAltitude = altitudeSmoother.add(altitudeM)
		val previousAltitude = previousSmoothedAltitude
		val previousTime = previousTimeMs

		val result: Float? = if (previousAltitude != null && previousTime > 0L && timeMs > previousTime) {
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
		previousTimeMs = 0L
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
 * duration. A session is "confirmed" as a flight once cumulative airborne time crosses
 * [PlaneDetectionConfig.minFlightDurationForConfirmationMs] — the same style of confirmation as
 * [com.adsamcik.tracker.stats.engine.sailing.RealTimeSailingDetector], adapted because a flight
 * (unlike a ski day) does not have discrete repeating cycles to count.
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
	private var maxSpeedMps: Float = 0f

	// Latest sensor values
	private var lastVerticalRateMps: Float = 0f
	private var lastSampleTimeMs: Long = 0L

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
		val ongoingAirborneMs = if (confirmedState.isAirborne() && confirmedStateEntryMs > 0L && lastSampleTimeMs > 0L) {
			lastSampleTimeMs - confirmedStateEntryMs
		} else {
			0L
		}
		val liveTotalAirborneDurationMs = accumulatedAirborneDurationMs + ongoingAirborneMs
		RealTimePlaneState(
			state = confirmedState,
			stateEntryTimeMs = confirmedStateEntryMs,
			stateDurationMs = if (confirmedStateEntryMs > 0L && lastSampleTimeMs > 0L) {
				lastSampleTimeMs - confirmedStateEntryMs
			} else {
				0L
			},
			totalAirborneDurationMs = liveTotalAirborneDurationMs,
			isConfirmedFlight = liveTotalAirborneDurationMs >= config.minFlightDurationForConfirmationMs,
			currentVerticalRateMps = lastVerticalRateMps,
			maxSpeedMps = maxSpeedMps,
		)
	}

	/**
	 * Process a new barometric altitude sample (+ optional GPS speed / step rate).
	 *
	 * Call this on every collection cycle that has barometric data.
	 *
	 * @param timeMs sample timestamp (epoch millis)
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
		val verticalRate = verticalRateCalc.onNewSample(timeMs, altitudeM) ?: return null
		lastVerticalRateMps = verticalRate
		lastSampleTimeMs = timeMs

		val signal = PlaneSignal(
			timeMs = timeMs,
			verticalRateMps = verticalRate,
			speedMps = speedMps,
			stepRatePerMin = stepRatePerMin,
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
	 * Reset all state. Call when starting a new tracking session.
	 */
	fun reset() = synchronized(lock) {
		verticalRateCalc.reset()
		confirmedState = PlaneState.IDLE
		confirmedStateEntryMs = 0L
		pendingState = PlaneState.IDLE
		pendingStateEntryMs = 0L
		accumulatedAirborneDurationMs = 0L
		maxSpeedMps = 0f
		lastVerticalRateMps = 0f
		lastSampleTimeMs = 0L
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
		if (previousState.isAirborne() && confirmedStateEntryMs > 0L) {
			accumulatedAirborneDurationMs += (timeMs - confirmedStateEntryMs)
		}

		confirmedState = newState
		confirmedStateEntryMs = timeMs

		listener?.onStateChanged(previousState, getCurrentState())
	}
}
