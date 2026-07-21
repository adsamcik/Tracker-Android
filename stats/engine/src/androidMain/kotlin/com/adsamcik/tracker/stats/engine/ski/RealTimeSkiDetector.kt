package com.adsamcik.tracker.stats.engine.ski

data class FinalizedSkiRun(
	val startTimeMs: Long,
	val endTimeMs: Long,
	val verticalDropM: Float,
	val maxSpeedMps: Float,
)

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
	val totalRunCount: Int,
	val isNearResort: Boolean = false,
	/** OSM lift type during LIFT_UP (e.g. "chairlift", "gondola", "cable_car"), null otherwise. */
	val currentLiftType: String? = null,
	val lastCompletedRun: FinalizedSkiRun? = null,
	val isFinished: Boolean = false,
)

data class FinalSkiDetectorSnapshot(
	val state: RealTimeSkiState,
	val boundaryTimeMs: Long,
	val finalizedRun: FinalizedSkiRun?,
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
 * - [nextSkiCandidate] for shared batch/streaming hysteresis-aware classification
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

	private val lock = Any()

	// Current confirmed state (after min-duration)
	private var confirmedState: SkiState = SkiState.IDLE
	private var confirmedStateEntryElapsedMs: Long? = null
	private var confirmedStateEntryEpochMs: Long = 0L

	// Pending state (before min-duration confirmation)
	private var pendingState: SkiState = SkiState.IDLE
	private var pendingStateEntryElapsedMs: Long = 0L

	// Cycle tracking
	private var sawLiftInCurrentCycle: Boolean = false
	private var completedCycles: Int = 0

	// Per-run metrics for current DOWNHILL_RUN
	private var currentRunStartAltitude: Float? = null
	private var currentRunMinAltitude: Float = Float.MAX_VALUE
	private var currentRunMaxSpeed: Float = 0f
	private var currentRunStartEpochMs: Long = 0L
	private var lastCompletedRun: FinalizedSkiRun? = null

	// Session totals
	private var totalVerticalDescent: Float = 0f
	private var totalDownhillRuns: Int = 0

	// Latest sensor values
	private var lastAltitudeM: Float = 0f
	private var lastSpeedMps: Float = 0f
	private var lastAcceptedTime: SkiSampleTime? = null

	// Listener
	private var listener: SkiStateListener? = null

	// Resort proximity flag — lowers confirmation threshold
	private var nearResort: Boolean = false

	// Current lift type from OSM matching (set during LIFT_UP)
	private var currentLiftType: String? = null
	private var finishedSnapshot: FinalSkiDetectorSnapshot? = null

	/**
	 * Set a listener for state transitions.
	 */
	fun setListener(listener: SkiStateListener?) {
		synchronized(lock) {
			this.listener = listener
		}
	}

	/**
	 * Mark that the device is near a known ski resort.
	 * When true, lowers the cycle threshold for session confirmation to 1.
	 */
	fun setNearResort(near: Boolean) = synchronized(lock) {
		nearResort = near
	}

	/**
	 * Set the current lift type from OSM data matching.
	 * Called when entering LIFT_UP near a known lift.
	 */
	fun setCurrentLiftType(type: String?) = synchronized(lock) {
		currentLiftType = type
	}

	/**
	 * Get the current real-time ski state snapshot.
	 */
	fun getCurrentState(): RealTimeSkiState = synchronized(lock) {
		snapshot()
	}

	private fun snapshot(isFinished: Boolean = finishedSnapshot != null): RealTimeSkiState {
		val lastElapsedMs = lastAcceptedTime?.elapsedMs ?: 0L
		val entryElapsedMs = confirmedStateEntryElapsedMs
		val effectiveMinCycles = if (nearResort) 1 else config.minCyclesForClassification
		return RealTimeSkiState(
			state = confirmedState,
			stateEntryTimeMs = confirmedStateEntryEpochMs,
			stateDurationMs = if (entryElapsedMs != null) {
				(lastElapsedMs - entryElapsedMs).coerceAtLeast(0L)
			} else {
				0L
			},
			completedRunCount = completedCycles,
			isConfirmedSkiSession = completedCycles >= effectiveMinCycles,
			currentRunVerticalM = computeCurrentRunVertical(),
			currentRunMaxSpeedMps = currentRunMaxSpeed,
			totalVerticalM = totalVerticalDescent,
			totalRunCount = totalDownhillRuns,
			isNearResort = nearResort,
			currentLiftType = if (confirmedState == SkiState.LIFT_UP) currentLiftType else null,
			lastCompletedRun = lastCompletedRun,
			isFinished = isFinished,
		)
	}

	/**
	 * Process a new barometric altitude + GPS speed sample.
	 *
	 * Call this on every collection cycle (~1-60s depending on tier).
	 *
	 * Compatibility overload for callers whose event and elapsed clocks share the same timeline.
	 *
	 * Production callers should supply separate monotonic and epoch timestamps.
	 */
	fun onSample(
		timeMs: Long,
		altitudeM: Float,
		speedMps: Float,
		stepRatePerMin: Float = 0f,
	): RealTimeSkiState? = onSample(
		elapsedTimeMs = timeMs,
		epochTimeMs = timeMs,
		altitudeM = altitudeM,
		speedMps = speedMps,
		stepRatePerMin = stepRatePerMin,
	)

	/**
	 * Process a new sample using monotonic time for all interval arithmetic.
	 *
	 * @param elapsedTimeMs monotonic elapsed-realtime timestamp in milliseconds
	 * @param epochTimeMs event timestamp in epoch milliseconds for persisted/displayed boundaries
	 * @param altitudeM barometric altitude in meters
	 * @param speedMps GPS ground speed in m/s (0 if unavailable)
	 * @param stepRatePerMin current step rate (0 if unavailable)
	 * @return current [RealTimeSkiState], or null if not enough data yet
	 */
	fun onSample(
		elapsedTimeMs: Long,
		epochTimeMs: Long,
		altitudeM: Float,
		speedMps: Float,
		stepRatePerMin: Float = 0f,
	): RealTimeSkiState? {
		val update = synchronized(lock) {
			finishedSnapshot?.let { return@synchronized DetectorUpdate(it.state, null) }

			val rateUpdate = verticalRateCalc.onNewSample(
				TimestampedAltitude(elapsedTimeMs, altitudeM)
			)
			if (!rateUpdate.accepted) {
				return@synchronized DetectorUpdate(null, null)
			}

			val sampleTime = SkiSampleTime(elapsedTimeMs, epochTimeMs)
			lastAcceptedTime = sampleTime
			lastAltitudeM = altitudeM
			lastSpeedMps = speedMps

			if (confirmedState == SkiState.DOWNHILL_RUN) {
				currentRunMinAltitude = minOf(currentRunMinAltitude, altitudeM)
				currentRunMaxSpeed = maxOf(currentRunMaxSpeed, speedMps)
			}

			val verticalRate = rateUpdate.verticalRateMps
				?: return@synchronized DetectorUpdate(null, null)
			val signal = SkiSignal(
				timeMs = elapsedTimeMs,
				verticalRateMps = verticalRate,
				speedMps = speedMps,
				stepRatePerMin = stepRatePerMin
			)
			val candidate = nextSkiCandidate(signal, confirmedState, config)
			val notification = updateStateWithHysteresis(candidate, sampleTime)

			DetectorUpdate(snapshot(), notification)
		}

		update.notification?.notifyListener()
		return update.state
	}

	/**
	 * Finalize the detector at the last accepted event boundary.
	 *
	 * Repeated calls return the same immutable result and do not finalize or notify twice.
	 */
	fun finish(): FinalSkiDetectorSnapshot {
		val update = synchronized(lock) {
			finishedSnapshot?.let { return@synchronized FinishUpdate(it, null) }

			val boundary = lastAcceptedTime
			if (boundary == null) {
				val result = FinalSkiDetectorSnapshot(
					state = snapshot(isFinished = true),
					boundaryTimeMs = 0L,
					finalizedRun = null,
				)
				finishedSnapshot = result
				return@synchronized FinishUpdate(result, null)
			}

			val previousCompletedRun = lastCompletedRun
			val notification = transitionTo(SkiState.IDLE, boundary, isFinished = true)
			val finalizedByFinish = lastCompletedRun.takeIf { it !== previousCompletedRun }
			val result = FinalSkiDetectorSnapshot(
				state = notification.newState,
				boundaryTimeMs = boundary.epochMs,
				finalizedRun = finalizedByFinish,
			)
			finishedSnapshot = result
			FinishUpdate(result, notification)
		}

		update.notification?.notifyListener()
		return update.snapshot
	}

	/**
	 * Reset all state. Call when starting a new tracking session.
	 */
	fun reset() = synchronized(lock) {
		verticalRateCalc.reset()
		confirmedState = SkiState.IDLE
		confirmedStateEntryElapsedMs = null
		confirmedStateEntryEpochMs = 0L
		pendingState = SkiState.IDLE
		pendingStateEntryElapsedMs = 0L
		sawLiftInCurrentCycle = false
		completedCycles = 0
		currentRunStartAltitude = null
		currentRunMinAltitude = Float.MAX_VALUE
		currentRunMaxSpeed = 0f
		currentRunStartEpochMs = 0L
		lastCompletedRun = null
		totalVerticalDescent = 0f
		totalDownhillRuns = 0
		lastAltitudeM = 0f
		lastSpeedMps = 0f
		lastAcceptedTime = null
		nearResort = false
		currentLiftType = null
		finishedSnapshot = null
	}

	private fun updateStateWithHysteresis(
		candidateState: SkiState,
		time: SkiSampleTime,
	): TransitionNotification? {
		if (candidateState != pendingState) {
			// New candidate state — start the pending timer
			pendingState = candidateState
			pendingStateEntryElapsedMs = time.elapsedMs
		}

		// Check if pending state has been held long enough
		val pendingDurationMs = time.elapsedMs - pendingStateEntryElapsedMs
		if (pendingState != confirmedState && pendingDurationMs >= config.minStateDurationMs) {
			return transitionTo(pendingState, time)
		}
		return null
	}

	private fun transitionTo(
		newState: SkiState,
		time: SkiSampleTime,
		isFinished: Boolean = false,
	): TransitionNotification {
		val previousState = confirmedState

		// Finalize metrics for the state we're leaving
		when (previousState) {
			SkiState.DOWNHILL_RUN -> finalizeDownhillRun(time.epochMs)
			SkiState.LIFT_UP -> {
				// Completed a lift ride — mark for cycle counting
				sawLiftInCurrentCycle = true
				currentLiftType = null
			}
			else -> { /* no special handling */ }
		}

		// Initialize metrics for the state we're entering
		when (newState) {
			SkiState.DOWNHILL_RUN -> {
				currentRunStartAltitude = lastAltitudeM
				currentRunMinAltitude = lastAltitudeM
				currentRunMaxSpeed = lastSpeedMps
				currentRunStartEpochMs = time.epochMs

				// Check for completed cycle
				if (sawLiftInCurrentCycle) {
					completedCycles++
					sawLiftInCurrentCycle = false
				}
			}
			else -> { /* no special init */ }
		}

		confirmedState = newState
		confirmedStateEntryElapsedMs = time.elapsedMs
		confirmedStateEntryEpochMs = time.epochMs

		return TransitionNotification(
			listener = listener,
			previousState = previousState,
			newState = snapshot(isFinished),
		)
	}

	private fun finalizeDownhillRun(endTimeMs: Long) {
		val startAlt = currentRunStartAltitude ?: return
		val verticalDrop = (startAlt - currentRunMinAltitude).coerceAtLeast(0f)
		lastCompletedRun = FinalizedSkiRun(
			startTimeMs = currentRunStartEpochMs,
			endTimeMs = endTimeMs,
			verticalDropM = verticalDrop,
			maxSpeedMps = currentRunMaxSpeed,
		)
		if (verticalDrop > 0f) {
			totalVerticalDescent += verticalDrop
			totalDownhillRuns++
		}
		currentRunStartAltitude = null
		currentRunMinAltitude = Float.MAX_VALUE
		currentRunMaxSpeed = 0f
		currentRunStartEpochMs = 0L
	}

	private fun computeCurrentRunVertical(): Float {
		val startAlt = currentRunStartAltitude ?: return 0f
		return (startAlt - currentRunMinAltitude).coerceAtLeast(0f)
	}
}

private data class SkiSampleTime(
	val elapsedMs: Long,
	val epochMs: Long,
)

private data class TransitionNotification(
	val listener: SkiStateListener?,
	val previousState: SkiState,
	val newState: RealTimeSkiState,
) {
	fun notifyListener() {
		listener?.onStateChanged(previousState, newState)
	}
}

private data class DetectorUpdate(
	val state: RealTimeSkiState?,
	val notification: TransitionNotification?,
)

private data class FinishUpdate(
	val snapshot: FinalSkiDetectorSnapshot,
	val notification: TransitionNotification?,
)
