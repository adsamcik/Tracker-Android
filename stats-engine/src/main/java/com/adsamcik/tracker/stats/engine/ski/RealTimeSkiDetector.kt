package com.adsamcik.tracker.stats.engine.ski

/**
 * Real-time ski state for the current moment.
 */
data class RealTimeSkiState(
	val state: SkiState,
	val stateEntryTimeMs: Long,
	val stateDurationMs: Long,
	val completedRunCount: Int,
	val isConfirmedSkiSession: Boolean,
	val currentRunVerticalM: Float,
	val currentRunMaxSpeedMps: Float,
	val totalVerticalM: Float,
	val totalRunCount: Int
)

/**
 * Callback for ski state transitions.
 */
fun interface SkiStateListener {
	fun onStateChanged(previousState: SkiState, newState: RealTimeSkiState)
}

/**
 * Real-time ski activity detector that processes streaming sensor data.
 *
 * Maintains internal state from barometric altitude and GPS speed samples,
 * classifies the current phase of a ski day (idle, lift, descending, walking),
 * and counts completed lift→descent cycles.
 *
 * Architecture:
 * - [StreamingVerticalRateCalculator] for smoothed vertical rate from barometer
 * - [SkiStateMachine.classifySignal] for per-sample state classification
 * - Minimum-duration hysteresis to prevent state flicker
 * - Run counter and per-run metric accumulation
 *
 * Pure Kotlin, no Android dependencies. Thread-safe via synchronized blocks.
 */
class RealTimeSkiDetector(
	private val config: SkiDetectionConfig = SkiDetectionConfig()
) {
	private val verticalRateCalc = StreamingVerticalRateCalculator(
		medianWindowSize = config.baroMedianWindow,
		emaAlpha = config.verticalRateEmaAlpha
	)
	private val stateMachine = SkiStateMachine(config)

	private val lock = Any()

	// Current confirmed state (after min-duration)
	private var confirmedState: SkiState = SkiState.IDLE
	private var confirmedStateEntryMs: Long = 0L

	// Pending state (before min-duration confirmation)
	private var pendingState: SkiState = SkiState.IDLE
	private var pendingStateEntryMs: Long = 0L

	// Cycle tracking
	private var sawLiftInCurrentCycle: Boolean = false
	private var completedCycles: Int = 0

	// Per-run metrics for current DOWNHILL_RUN
	private var currentRunStartAltitude: Float? = null
	private var currentRunMinAltitude: Float = Float.MAX_VALUE
	private var currentRunMaxSpeed: Float = 0f
	private var currentRunStartTimeMs: Long = 0L

	// Session totals
	private var totalVerticalDescent: Float = 0f
	private var totalDownhillRuns: Int = 0

	// Latest sensor values
	private var lastAltitudeM: Float = 0f
	private var lastSpeedMps: Float = 0f

	// Listener
	private var listener: SkiStateListener? = null

	/**
	 * Set a listener for state transitions.
	 */
	fun setListener(listener: SkiStateListener?) {
		this.listener = listener
	}

	/**
	 * Get the current real-time ski state snapshot.
	 */
	fun getCurrentState(): RealTimeSkiState = synchronized(lock) {
		val now = if (confirmedStateEntryMs > 0L) {
			System.currentTimeMillis()
		} else {
			0L
		}
		RealTimeSkiState(
			state = confirmedState,
			stateEntryTimeMs = confirmedStateEntryMs,
			stateDurationMs = if (confirmedStateEntryMs > 0L) now - confirmedStateEntryMs else 0L,
			completedRunCount = completedCycles,
			isConfirmedSkiSession = completedCycles >= config.minCyclesForClassification,
			currentRunVerticalM = computeCurrentRunVertical(),
			currentRunMaxSpeedMps = currentRunMaxSpeed,
			totalVerticalM = totalVerticalDescent,
			totalRunCount = totalDownhillRuns
		)
	}

	/**
	 * Process a new barometric altitude + GPS speed sample.
	 *
	 * Call this on every collection cycle (~1-60s depending on tier).
	 *
	 * @param timeMs sample timestamp (epoch millis)
	 * @param altitudeM barometric altitude in meters
	 * @param speedMps GPS ground speed in m/s (0 if unavailable)
	 * @param stepRatePerMin current step rate (0 if unavailable)
	 * @return current [RealTimeSkiState], or null if not enough data yet
	 */
	fun onSample(
		timeMs: Long,
		altitudeM: Float,
		speedMps: Float,
		stepRatePerMin: Float = 0f
	): RealTimeSkiState? = synchronized(lock) {
		lastAltitudeM = altitudeM
		lastSpeedMps = speedMps

		// Feed altitude to vertical rate calculator
		val verticalRate = verticalRateCalc.onNewSample(
			TimestampedAltitude(timeMs, altitudeM)
		) ?: return null

		// Classify the current signal
		val signal = SkiSignal(
			timeMs = timeMs,
			verticalRateMps = verticalRate,
			speedMps = speedMps,
			stepRatePerMin = stepRatePerMin
		)
		val rawClassification = stateMachine.classifySignal(signal)

		// Apply minimum-duration hysteresis
		updateStateWithHysteresis(rawClassification, timeMs)

		getCurrentState()
	}

	/**
	 * Reset all state. Call when starting a new tracking session.
	 */
	fun reset() = synchronized(lock) {
		verticalRateCalc.reset()
		confirmedState = SkiState.IDLE
		confirmedStateEntryMs = 0L
		pendingState = SkiState.IDLE
		pendingStateEntryMs = 0L
		sawLiftInCurrentCycle = false
		completedCycles = 0
		currentRunStartAltitude = null
		currentRunMinAltitude = Float.MAX_VALUE
		currentRunMaxSpeed = 0f
		currentRunStartTimeMs = 0L
		totalVerticalDescent = 0f
		totalDownhillRuns = 0
		lastAltitudeM = 0f
		lastSpeedMps = 0f
	}

	private fun updateStateWithHysteresis(rawState: SkiState, timeMs: Long) {
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

	private fun transitionTo(newState: SkiState, timeMs: Long) {
		val previousState = confirmedState

		// Finalize metrics for the state we're leaving
		when (previousState) {
			SkiState.DOWNHILL_RUN -> finalizeDownhillRun()
			SkiState.LIFT_UP -> {
				// Completed a lift ride — mark for cycle counting
				sawLiftInCurrentCycle = true
			}
			else -> { /* no special handling */ }
		}

		// Initialize metrics for the state we're entering
		when (newState) {
			SkiState.DOWNHILL_RUN -> {
				currentRunStartAltitude = lastAltitudeM
				currentRunMinAltitude = lastAltitudeM
				currentRunMaxSpeed = lastSpeedMps
				currentRunStartTimeMs = timeMs

				// Check for completed cycle
				if (sawLiftInCurrentCycle) {
					completedCycles++
					sawLiftInCurrentCycle = false
				}
			}
			else -> { /* no special init */ }
		}

		confirmedState = newState
		confirmedStateEntryMs = timeMs

		// Notify listener
		listener?.onStateChanged(previousState, getCurrentState())
	}

	private fun finalizeDownhillRun() {
		val startAlt = currentRunStartAltitude ?: return
		val verticalDrop = startAlt - currentRunMinAltitude
		if (verticalDrop > 0f) {
			totalVerticalDescent += verticalDrop
			totalDownhillRuns++
		}
		currentRunStartAltitude = null
		currentRunMinAltitude = Float.MAX_VALUE
		currentRunMaxSpeed = 0f
	}

	private fun computeCurrentRunVertical(): Float {
		val startAlt = currentRunStartAltitude ?: return 0f
		return (startAlt - lastAltitudeM).coerceAtLeast(0f)
	}
}
