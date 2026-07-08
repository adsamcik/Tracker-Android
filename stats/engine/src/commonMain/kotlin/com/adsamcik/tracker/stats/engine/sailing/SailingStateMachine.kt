package com.adsamcik.tracker.stats.engine.sailing

/**
 * States in the sailing activity state machine.
 */
enum class SailingState {
	/** Stationary: moored, anchored, or becalmed. */
	IDLE,
	/** Underway at a speed consistent with sailing/boating. */
	SAILING,
	/** Walking — e.g. on the dock or shore. */
	WALK,
}

/**
 * Input signal for the sailing state machine at a point in time.
 */
data class SailingSignal(
	val timeMs: Long,
	val speedMps: Float,
	val stepRatePerMin: Float = 0f,
)

/**
 * A contiguous time segment in a single sailing state.
 */
data class SailingStateSegment(
	val state: SailingState,
	val startMs: Long,
	val endMs: Long,
) {
	val durationMs: Long get() = endMs - startMs
}

/**
 * Rule-based state machine for sailing session segmentation.
 *
 * Classifies each time window into one of 3 states using GPS speed and step rate, with
 * enter/exit hysteresis thresholds and a minimum-duration constraint to prevent state flicker
 * from wave motion / GPS jitter. Structurally mirrors [com.adsamcik.tracker.stats.engine.ski.SkiStateMachine]
 * (segment merge / min-duration / re-merge pipeline) with a simpler 3-state model, since sailing
 * has no vertical-rate signal to classify on.
 *
 * Pure function, no platform dependencies.
 */
class SailingStateMachine(private val config: SailingDetectionConfig = SailingDetectionConfig()) {

	/**
	 * Process a sequence of signals and return the resulting state segments.
	 *
	 * @param signals time-ordered sensor signals
	 * @return list of contiguous state segments (merged by minimum duration)
	 */
	fun process(signals: List<SailingSignal>): List<SailingStateSegment> {
		if (signals.isEmpty()) return emptyList()

		// Phase 1: Classify with hysteresis (sticky states using exit thresholds)
		val rawStates = mutableListOf<Pair<SailingState, Long>>()
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
	 * Classify a single signal into a sailing state using hysteresis-aware rules.
	 * Order of checks matters: more specific states first.
	 */
	internal fun classifySignal(signal: SailingSignal): SailingState {
		// Check WALK first (step rate is a strong indicator, regardless of speed)
		if (signal.stepRatePerMin >= config.walkStepRateThreshold) {
			return SailingState.WALK
		}

		// Check SAILING: within the plausible sailing speed band
		if (signal.speedMps >= config.sailingEnterMinSpeedMps &&
			signal.speedMps <= config.sailingMaxSpeedMps
		) {
			return SailingState.SAILING
		}

		// Default: IDLE (moored/anchored/becalmed)
		return SailingState.IDLE
	}

	/**
	 * Classify a signal considering the current state. Uses relaxed exit thresholds so a state
	 * "sticks" until conditions clearly change (e.g. a brief wind lull doesn't flip to IDLE).
	 */
	internal fun classifyWithHysteresis(
		signal: SailingSignal,
		currentState: SailingState,
	): SailingState {
		val shouldStay = when (currentState) {
			SailingState.SAILING ->
				signal.stepRatePerMin < config.walkStepRateThreshold &&
					signal.speedMps >= config.sailingExitMinSpeedMps &&
					signal.speedMps <= config.sailingMaxSpeedMps

			SailingState.WALK -> signal.stepRatePerMin >= config.walkStepRateThreshold

			SailingState.IDLE -> false
		}
		return if (shouldStay) currentState else classifySignal(signal)
	}

	internal fun mergeIntoSegments(
		classifiedPoints: List<Pair<SailingState, Long>>,
	): List<SailingStateSegment> {
		if (classifiedPoints.isEmpty()) return emptyList()

		val segments = mutableListOf<SailingStateSegment>()
		var currentState = classifiedPoints[0].first
		var segmentStart = classifiedPoints[0].second

		for (i in 1 until classifiedPoints.size) {
			val (state, timeMs) = classifiedPoints[i]
			if (state != currentState) {
				segments.add(SailingStateSegment(currentState, segmentStart, timeMs))
				currentState = state
				segmentStart = timeMs
			}
		}
		// Close final segment
		segments.add(
			SailingStateSegment(
				currentState,
				segmentStart,
				classifiedPoints.last().second,
			),
		)
		return segments
	}

	/**
	 * Enforce minimum duration: segments shorter than [SailingDetectionConfig.minStateDurationMs]
	 * are absorbed into the preceding segment (or following if first).
	 */
	internal fun enforceMinDuration(
		segments: List<SailingStateSegment>,
	): List<SailingStateSegment> {
		if (segments.size <= 1) return segments

		val result = mutableListOf<SailingStateSegment>()
		for (segment in segments) {
			if (segment.durationMs < config.minStateDurationMs && result.isNotEmpty()) {
				// Absorb into previous segment by extending its end time
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
	 * Cleans up artefacts from [enforceMinDuration], which can absorb a short gap into the
	 * preceding segment, leaving two adjacent same-state segments that should be one.
	 */
	internal fun mergeSameState(
		segments: List<SailingStateSegment>,
	): List<SailingStateSegment> {
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

	/**
	 * Total time spent in [SailingState.SAILING] across all segments.
	 */
	fun totalSailingDurationMs(segments: List<SailingStateSegment>): Long =
		segments.filter { it.state == SailingState.SAILING }.sumOf { it.durationMs }
}
