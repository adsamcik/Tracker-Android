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
 * Confidence context for the current sailing classification.
 *
 * A speed in the sailing range is not sufficient evidence by itself because it overlaps cycling
 * and slow driving. The detector exposes that signal separately instead of treating absent
 * context as evidence for sailing.
 */
enum class SailingDetectionReason {
	IDLE,
	WALK,
	CONTEXT_CONFIRMED_SAILING,
	BOAT_LIKE_MOTION,
	VEHICLE_OR_BICYCLE_MOTION,
	UNKNOWN_SAMPLE_GAP,
}

/**
 * Input signal for the sailing state machine at a point in time.
 */
data class SailingSignal(
	val timeMs: Long,
	val speedMps: Float,
	val stepRatePerMin: Float = 0f,
	/** Whether a current local activity-recognition observation is available. */
	val motionContextAvailable: Boolean = false,
	/** A sufficiently confident local bicycle or vehicle observation. */
	val hasStrongVehicleOrBicycleSignature: Boolean = false,
)

/**
 * Classify a sample without applying the state-dependent exit hysteresis.
 */
internal fun classifySailingSignal(
	signal: SailingSignal,
	config: SailingDetectionConfig,
): SailingState = when {
	signal.stepRatePerMin >= config.walkStepRateThreshold -> SailingState.WALK
	signal.hasStrongVehicleOrBicycleSignature -> SailingState.IDLE
	signal.speedMps >= config.sailingEnterMinSpeedMps &&
		signal.speedMps <= config.sailingMaxSpeedMps &&
		signal.motionContextAvailable -> SailingState.SAILING
	else -> SailingState.IDLE
}

/**
 * The confidence context associated with [classifySailingSignal].
 */
internal fun sailingDetectionReason(
	signal: SailingSignal,
	config: SailingDetectionConfig,
): SailingDetectionReason = when {
	signal.stepRatePerMin >= config.walkStepRateThreshold -> SailingDetectionReason.WALK
	signal.hasStrongVehicleOrBicycleSignature -> SailingDetectionReason.VEHICLE_OR_BICYCLE_MOTION
	signal.speedMps >= config.sailingEnterMinSpeedMps &&
		signal.speedMps <= config.sailingMaxSpeedMps &&
		!signal.motionContextAvailable -> SailingDetectionReason.BOAT_LIKE_MOTION
	signal.speedMps >= config.sailingEnterMinSpeedMps &&
		signal.speedMps <= config.sailingMaxSpeedMps -> SailingDetectionReason.CONTEXT_CONFIRMED_SAILING
	else -> SailingDetectionReason.IDLE
}

/**
 * Shared hysteresis-aware transition candidate for batch and streaming sailing detection.
 */
internal fun nextSailingCandidate(
	signal: SailingSignal,
	currentState: SailingState,
	config: SailingDetectionConfig,
): SailingState {
	if (signal.stepRatePerMin >= config.walkStepRateThreshold) return SailingState.WALK
	if (signal.hasStrongVehicleOrBicycleSignature) return SailingState.IDLE

	val shouldStaySailing = currentState == SailingState.SAILING &&
		signal.speedMps >= config.sailingExitMinSpeedMps &&
		signal.speedMps <= config.sailingMaxSpeedMps
	return if (shouldStaySailing) SailingState.SAILING else classifySailingSignal(signal, config)
}

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
		var currentState = nextSailingCandidate(signals[0], SailingState.IDLE, config)
		rawStates.add(currentState to signals[0].timeMs)
		for (i in 1 until signals.size) {
			currentState = nextSailingCandidate(signals[i], currentState, config)
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
		return classifySailingSignal(signal, config)
	}

	/**
	 * Classify a signal considering the current state. Uses relaxed exit thresholds so a state
	 * "sticks" until conditions clearly change (e.g. a brief wind lull doesn't flip to IDLE).
	 */
	internal fun classifyWithHysteresis(
		signal: SailingSignal,
		currentState: SailingState,
	): SailingState = nextSailingCandidate(signal, currentState, config)

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
