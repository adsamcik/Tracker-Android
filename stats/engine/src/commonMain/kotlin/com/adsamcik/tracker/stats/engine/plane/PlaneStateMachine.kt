package com.adsamcik.tracker.stats.engine.plane

/**
 * States in the plane/flight activity state machine.
 */
enum class PlaneState {
	/** On the ground: parked, taxiing slowly, boarding. */
	IDLE,
	/** Insufficient fresh barometric data to infer a current flight phase. */
	UNKNOWN,
	/** Climbing — rapid, sustained altitude/cabin-pressure increase after takeoff. */
	CLIMBING,
	/** Level flight at (cabin-pressure-equivalent) altitude, sustained. */
	CRUISING,
	/** Descending — rapid, sustained altitude/cabin-pressure decrease before landing. */
	DESCENDING,
	/** Walking — e.g. the terminal, or the cabin aisle. */
	WALK,
}

/** Provenance for CRUISING, used to keep high-speed ground transport out of flight confirmation. */
enum class CruiseEvidence {
	UNKNOWN,
	SPEED_ONLY,
	QUALIFIED_CLIMB,
}

/**
 * Input signal for the plane state machine at a point in time.
 */
data class PlaneSignal(
	val timeMs: Long,
	val verticalRateMps: Float,
	val speedMps: Float = 0f,
	val stepRatePerMin: Float = 0f,
	val cruiseEvidence: CruiseEvidence = CruiseEvidence.UNKNOWN,
)

/**
 * A contiguous time segment in a single plane state.
 */
data class PlaneStateSegment(
	val state: PlaneState,
	val startMs: Long,
	val endMs: Long,
) {
	val durationMs: Long get() = endMs - startMs
}

/**
 * Rule-based state machine for flight segmentation.
 *
 * Classifies each time window using barometric vertical rate (primary — always available,
 * unlike GPS at cruise altitude) and step rate, with GPS speed only as a low-confidence hint.
 * [PlaneState.CRUISING] is special: a flat isolated sample is always IDLE, because there is no
 * way to distinguish level flight from stable ground altitude without context. It is reached only
 * via sustained high GPS speed, or by "graduating" out of [PlaneState.CLIMBING] once the climb
 * rate flattens. See
 * [classifyWithHysteresis].
 *
 * Structurally mirrors [com.adsamcik.tracker.stats.engine.ski.SkiStateMachine]'s segment-merge /
 * min-duration / re-merge pipeline.
 *
 * Pure function, no platform dependencies.
 */
class PlaneStateMachine(private val config: PlaneDetectionConfig = PlaneDetectionConfig()) {

	/**
	 * Process a sequence of signals and return the resulting state segments.
	 *
	 * @param signals time-ordered sensor signals
	 * @return list of contiguous state segments (merged by minimum duration)
	 */
	fun process(signals: List<PlaneSignal>): List<PlaneStateSegment> {
		if (signals.isEmpty()) return emptyList()

		// Phase 1: Classify with hysteresis (sticky states using exit thresholds)
		val rawStates = mutableListOf<Pair<PlaneState, Long>>()
		var currentState = classifySignal(signals[0])
		rawStates.add(currentState to signals[0].timeMs)
		for (i in 1 until signals.size) {
			currentState = classifyWithHysteresis(signals[i], currentState)
			rawStates.add(currentState to signals[i].timeMs)
		}

		// Phase 2: Merge into contiguous segments
		val rawSegments = mergeIntoSegments(rawStates)

		// Phase 3: Apply minimum duration constraint (absorb short segments into neighbors)
		val durationEnforced = enforceMinDuration(rawSegments)

		// Phase 4: Re-merge consecutive same-state segments created by phase 3
		return mergeSameState(durationEnforced)
	}

	/**
	 * Classify a single signal context-free. A speed-only [PlaneState.CRUISING] is a
	 * low-confidence transport hint; qualified climbs reach CRUISING through
	 * [classifyWithHysteresis].
	 */
	internal fun classifySignal(signal: PlaneSignal): PlaneState {
		if (signal.stepRatePerMin >= config.walkStepRateThreshold) return PlaneState.WALK
		if (signal.verticalRateMps >= config.climbEnterVerticalRateMps) return PlaneState.CLIMBING
		if (signal.verticalRateMps <= config.descendEnterVerticalRateMps) return PlaneState.DESCENDING
		if (signal.speedMps >= config.cruiseMinSpeedMps) return PlaneState.CRUISING
		return PlaneState.IDLE
	}

	/**
	 * Classify a signal considering the current state. Uses relaxed exit thresholds so a state
	 * "sticks" until conditions clearly change, and — crucially — graduates [PlaneState.CLIMBING]
	 * into [PlaneState.CRUISING] once the climb rate flattens, rather than falling back to IDLE
	 * (there is no altitude-only way to tell "still airborne, level" from "on the ground").
	 */
	internal fun classifyWithHysteresis(
		signal: PlaneSignal,
		currentState: PlaneState,
	): PlaneState {
		return when (currentState) {
			PlaneState.CLIMBING -> {
				if (signal.verticalRateMps >= config.climbExitVerticalRateMps) {
					PlaneState.CLIMBING
				} else {
					val raw = classifySignal(signal)
					if (raw == PlaneState.WALK || raw == PlaneState.DESCENDING) raw else PlaneState.CRUISING
				}
			}

			PlaneState.CRUISING -> {
				val raw = classifySignal(signal)
				when {
					raw == PlaneState.WALK || raw == PlaneState.DESCENDING -> raw
					raw == PlaneState.IDLE && signal.cruiseEvidence != CruiseEvidence.QUALIFIED_CLIMB -> PlaneState.IDLE
					else -> PlaneState.CRUISING
				}
			}

			PlaneState.DESCENDING ->
				if (signal.verticalRateMps <= config.descendExitVerticalRateMps) {
					PlaneState.DESCENDING
				} else {
					classifySignal(signal)
				}

			PlaneState.WALK ->
				if (signal.stepRatePerMin >= config.walkStepRateThreshold) PlaneState.WALK else classifySignal(signal)

			PlaneState.IDLE, PlaneState.UNKNOWN -> classifySignal(signal)
		}
	}

	internal fun mergeIntoSegments(
		classifiedPoints: List<Pair<PlaneState, Long>>,
	): List<PlaneStateSegment> {
		if (classifiedPoints.isEmpty()) return emptyList()

		val segments = mutableListOf<PlaneStateSegment>()
		var currentState = classifiedPoints[0].first
		var segmentStart = classifiedPoints[0].second

		for (i in 1 until classifiedPoints.size) {
			val (state, timeMs) = classifiedPoints[i]
			if (state != currentState) {
				segments.add(PlaneStateSegment(currentState, segmentStart, timeMs))
				currentState = state
				segmentStart = timeMs
			}
		}
		// Close final segment
		segments.add(
			PlaneStateSegment(
				currentState,
				segmentStart,
				classifiedPoints.last().second,
			),
		)
		return segments
	}

	/**
	 * Enforce minimum duration: segments shorter than [PlaneDetectionConfig.minStateDurationMs]
	 * are absorbed into the preceding segment (or following if first).
	 */
	internal fun enforceMinDuration(
		segments: List<PlaneStateSegment>,
	): List<PlaneStateSegment> {
		if (segments.size <= 1) return segments

		val result = mutableListOf<PlaneStateSegment>()
		for (segment in segments) {
			if (segment.durationMs < config.minStateDurationMs && result.isNotEmpty()) {
				val prev = result.removeAt(result.lastIndex)
				result.add(prev.copy(endMs = segment.endMs))
			} else {
				result.add(segment)
			}
		}
		return result
	}

	/**
	 * Merge consecutive segments that share the same state.
	 */
	internal fun mergeSameState(
		segments: List<PlaneStateSegment>,
	): List<PlaneStateSegment> {
		if (segments.size <= 1) return segments

		val result = mutableListOf(segments[0])
		for (i in 1 until segments.size) {
			val current = segments[i]
			val prev = result.last()
			if (current.state == prev.state) {
				result[result.lastIndex] = prev.copy(endMs = current.endMs)
			} else {
				result.add(current)
			}
		}
		return result
	}

	/** True for any state considered "in the air". */
	private fun PlaneState.isAirborne(): Boolean =
		this == PlaneState.CLIMBING || this == PlaneState.CRUISING || this == PlaneState.DESCENDING

	/**
	 * Total time spent airborne (climbing + cruising + descending) across all segments.
	 */
	fun totalAirborneDurationMs(segments: List<PlaneStateSegment>): Long =
		segments.filter { it.state.isAirborne() }.sumOf { it.durationMs }
}
