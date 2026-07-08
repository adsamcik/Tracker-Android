package com.adsamcik.tracker.stats.engine.sailing

/**
 * Real-time sailing state for the current moment.
 */
data class RealTimeSailingState(
	val state: SailingState,
	val stateEntryTimeMs: Long,
	val stateDurationMs: Long,
	val totalSailingDurationMs: Long,
	val totalSailingDistanceM: Float,
	val isConfirmedSailingSession: Boolean,
	val currentSpeedMps: Float,
	val maxSpeedMps: Float,
)

/**
 * Callback for sailing state transitions.
 */
fun interface SailingStateListener {
	fun onStateChanged(previousState: SailingState, newState: RealTimeSailingState)
}

/**
 * Real-time sailing/boating detector that processes streaming GPS speed (+ optional step rate)
 * samples.
 *
 * Maintains internal state, classifies the current phase (idle/sailing/walking), and accumulates
 * cumulative sailing duration/distance. A session is "confirmed" once cumulative time spent in
 * [SailingState.SAILING] crosses [SailingDetectionConfig.minSailingDurationForConfirmationMs] —
 * the sailing equivalent of ski's lift→run cycle count, adapted because sailing has no discrete
 * repeating cycles to count.
 *
 * Architecture mirrors [com.adsamcik.tracker.stats.engine.ski.RealTimeSkiDetector]: a light
 * median smoother for the noisy input signal, [SailingStateMachine.classifySignal] for per-sample
 * classification, and minimum-duration hysteresis to prevent state flicker.
 *
 * Pure Kotlin, no platform dependencies. Thread-safe via synchronized blocks.
 */
class RealTimeSailingDetector(
	private val config: SailingDetectionConfig = SailingDetectionConfig(),
) {
	private val speedSmoother = MedianSmoother(config.speedMedianWindow)
	private val stateMachine = SailingStateMachine(config)

	private val lock = Any()

	// Current confirmed state (after min-duration)
	private var confirmedState: SailingState = SailingState.IDLE
	private var confirmedStateEntryMs: Long = 0L

	// Pending state (before min-duration confirmation)
	private var pendingState: SailingState = SailingState.IDLE
	private var pendingStateEntryMs: Long = 0L

	// Session accumulators
	/** Sum of durations of SAILING segments that have already ENDED (transitioned away from). */
	private var accumulatedSailingDurationMs: Long = 0L
	private var totalSailingDistanceM: Float = 0f
	private var maxSpeedMps: Float = 0f

	// Latest sensor values
	private var lastSpeedMps: Float = 0f
	private var lastSampleTimeMs: Long = 0L

	// Listener
	private var listener: SailingStateListener? = null

	/**
	 * Set a listener for state transitions.
	 */
	fun setListener(listener: SailingStateListener?) {
		this.listener = listener
	}

	/**
	 * Get the current real-time sailing state snapshot.
	 *
	 * [RealTimeSailingState.totalSailingDurationMs] is the *live* cumulative total: past completed
	 * SAILING segments plus the ongoing one (if currently sailing), so confirmation can flip to
	 * true mid-session rather than only once the state changes away from SAILING.
	 */
	fun getCurrentState(): RealTimeSailingState = synchronized(lock) {
		val ongoingSailingMs = if (confirmedState == SailingState.SAILING && confirmedStateEntryMs > 0L && lastSampleTimeMs > 0L) {
			lastSampleTimeMs - confirmedStateEntryMs
		} else {
			0L
		}
		val liveTotalSailingDurationMs = accumulatedSailingDurationMs + ongoingSailingMs
		RealTimeSailingState(
			state = confirmedState,
			stateEntryTimeMs = confirmedStateEntryMs,
			stateDurationMs = if (confirmedStateEntryMs > 0L && lastSampleTimeMs > 0L) {
				lastSampleTimeMs - confirmedStateEntryMs
			} else {
				0L
			},
			totalSailingDurationMs = liveTotalSailingDurationMs,
			totalSailingDistanceM = totalSailingDistanceM,
			isConfirmedSailingSession = liveTotalSailingDurationMs >= config.minSailingDurationForConfirmationMs,
			currentSpeedMps = lastSpeedMps,
			maxSpeedMps = maxSpeedMps,
		)
	}

	/**
	 * Process a new GPS speed sample.
	 *
	 * Call this on every collection cycle that has a speed reading.
	 *
	 * @param timeMs sample timestamp (epoch millis)
	 * @param speedMps GPS ground speed in m/s
	 * @param distanceDeltaM distance traveled since the previous sample (m), 0 if unavailable
	 * @param stepRatePerMin current step rate (0 if unavailable)
	 * @return current [RealTimeSailingState]
	 */
	fun onSample(
		timeMs: Long,
		speedMps: Float,
		distanceDeltaM: Float = 0f,
		stepRatePerMin: Float = 0f,
	): RealTimeSailingState = synchronized(lock) {
		val smoothedSpeed = speedSmoother.add(speedMps)
		lastSpeedMps = smoothedSpeed
		lastSampleTimeMs = timeMs

		val signal = SailingSignal(
			timeMs = timeMs,
			speedMps = smoothedSpeed,
			stepRatePerMin = stepRatePerMin,
		)
		val rawClassification = stateMachine.classifySignal(signal)

		// Apply minimum-duration hysteresis
		updateStateWithHysteresis(rawClassification, timeMs)

		// Accumulate sailing totals for the CONFIRMED state (avoids counting pending flicker)
		if (confirmedState == SailingState.SAILING) {
			totalSailingDistanceM += distanceDeltaM.coerceAtLeast(0f)
			maxSpeedMps = maxOf(maxSpeedMps, smoothedSpeed)
		}

		getCurrentState()
	}

	/**
	 * Reset all state. Call when starting a new tracking session.
	 */
	fun reset() = synchronized(lock) {
		speedSmoother.reset()
		confirmedState = SailingState.IDLE
		confirmedStateEntryMs = 0L
		pendingState = SailingState.IDLE
		pendingStateEntryMs = 0L
		accumulatedSailingDurationMs = 0L
		totalSailingDistanceM = 0f
		maxSpeedMps = 0f
		lastSpeedMps = 0f
		lastSampleTimeMs = 0L
	}

	private fun updateStateWithHysteresis(rawState: SailingState, timeMs: Long) {
		if (rawState != pendingState) {
			// New candidate state — start the pending timer
			pendingState = rawState
			pendingStateEntryMs = timeMs
		}

		// Check if pending state has been held long enough
		val pendingDurationMs = timeMs - pendingStateEntryMs
		if (pendingState != confirmedState && pendingDurationMs >= config.minStateDurationMs) {
			transitionTo(pendingState, timeMs)
		}
	}

	private fun transitionTo(newState: SailingState, timeMs: Long) {
		val previousState = confirmedState

		// Fold the segment we're leaving into the completed accumulator
		if (previousState == SailingState.SAILING && confirmedStateEntryMs > 0L) {
			accumulatedSailingDurationMs += (timeMs - confirmedStateEntryMs)
		}

		confirmedState = newState
		confirmedStateEntryMs = timeMs

		// Notify listener
		listener?.onStateChanged(previousState, getCurrentState())
	}
}

/**
 * Minimal streaming median filter over a fixed-size sliding window. Reduces GPS-speed jitter
 * (wave chop, brief signal noise) without the latency/complexity of a full smoothing filter.
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
