package com.adsamcik.tracker.stats.engine.ski

/**
 * States in the ski activity state machine.
 */
enum class SkiState {
    /** Stationary: in queue, at hut, resting. */
    IDLE,
    /** Riding a lift (chairlift, gondola, drag lift). */
    LIFT_UP,
    /** Active descent (skiing/snowboarding). */
    DOWNHILL_RUN,
    /** Walking or traversing between areas. */
    WALK
}

/**
 * Input signal for the ski state machine at a point in time.
 */
data class SkiSignal(
    val timeMs: Long,
    val verticalRateMps: Float,
    val speedMps: Float,
    val stepRatePerMin: Float = 0f
)

/**
 * A contiguous time segment in a single ski state.
 */
data class SkiStateSegment(
    val state: SkiState,
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Rule-based state machine for ski session segmentation.
 *
 * Classifies each time window into one of 4 states using vertical rate,
 * speed, and step rate signals with enter/exit hysteresis thresholds
 * and minimum duration constraints to prevent state flicker.
 *
 * Pure function, no Android dependencies.
 */
class SkiStateMachine(private val config: SkiDetectionConfig = SkiDetectionConfig()) {

    /**
     * Process a sequence of signals and return the resulting state segments.
     *
     * @param signals time-ordered sensor signals
     * @return list of contiguous state segments (merged by minimum duration)
     */
    fun process(signals: List<SkiSignal>): List<SkiStateSegment> {
        if (signals.isEmpty()) return emptyList()

        // Phase 1: Classify with hysteresis (sticky states using exit thresholds)
        val rawStates = mutableListOf<Pair<SkiState, Long>>()
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
     * Classify a single signal into a ski state using hysteresis-aware rules.
     * Order of checks matters: more specific states first.
     */
    internal fun classifySignal(signal: SkiSignal): SkiState {
        // Check WALK first (step rate is a strong indicator)
        if (signal.stepRatePerMin >= config.walkStepRateThreshold &&
            signal.verticalRateMps > config.downhillEnterVerticalRate
        ) {
            return SkiState.WALK
        }

        // Check DOWNHILL_RUN: descending fast
        if (signal.verticalRateMps <= config.downhillEnterVerticalRate &&
            signal.speedMps >= config.downhillEnterSpeed
        ) {
            return SkiState.DOWNHILL_RUN
        }

        // Check LIFT_UP: ascending with limited speed
        if (signal.verticalRateMps >= config.liftEnterVerticalRate &&
            signal.speedMps <= config.liftMaxSpeed
        ) {
            return SkiState.LIFT_UP
        }

        // Default: IDLE
        return SkiState.IDLE
    }

    /**
     * Classify a signal considering the current state. Uses relaxed exit
     * thresholds so a state "sticks" until conditions clearly change.
     */
    internal fun classifyWithHysteresis(
        signal: SkiSignal,
        currentState: SkiState
    ): SkiState {
        val shouldStay = when (currentState) {
            SkiState.DOWNHILL_RUN ->
                signal.verticalRateMps <= config.downhillExitVerticalRate &&
                        signal.speedMps >= config.downhillExitSpeed

            SkiState.LIFT_UP ->
                signal.verticalRateMps >= config.liftExitVerticalRate &&
                        signal.speedMps <= config.liftMaxSpeed

            SkiState.WALK ->
                signal.stepRatePerMin >= config.walkStepRateThreshold

            SkiState.IDLE -> false
        }
        return if (shouldStay) currentState else classifySignal(signal)
    }

    internal fun mergeIntoSegments(
        classifiedPoints: List<Pair<SkiState, Long>>
    ): List<SkiStateSegment> {
        if (classifiedPoints.isEmpty()) return emptyList()

        val segments = mutableListOf<SkiStateSegment>()
        var currentState = classifiedPoints[0].first
        var segmentStart = classifiedPoints[0].second

        for (i in 1 until classifiedPoints.size) {
            val (state, timeMs) = classifiedPoints[i]
            if (state != currentState) {
                segments.add(SkiStateSegment(currentState, segmentStart, timeMs))
                currentState = state
                segmentStart = timeMs
            }
        }
        // Close final segment
        segments.add(
            SkiStateSegment(
                currentState,
                segmentStart,
                classifiedPoints.last().second
            )
        )
        return segments
    }

    /**
     * Enforce minimum duration: segments shorter than [SkiDetectionConfig.minStateDurationMs]
     * are absorbed into the preceding segment (or following if first).
     */
    internal fun enforceMinDuration(
        segments: List<SkiStateSegment>
    ): List<SkiStateSegment> {
        if (segments.size <= 1) return segments

        val result = mutableListOf<SkiStateSegment>()
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
     * This cleans up artefacts from [enforceMinDuration] which can absorb a
     * short gap into the preceding segment, leaving two adjacent same-state
     * segments that should be one.
     */
    internal fun mergeSameState(
        segments: List<SkiStateSegment>
    ): List<SkiStateSegment> {
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
     * Count the number of complete LIFT_UP → DOWNHILL_RUN cycles in segments.
     */
    fun countSkiCycles(segments: List<SkiStateSegment>): Int {
        var cycles = 0
        var sawLift = false
        for (segment in segments) {
            when (segment.state) {
                SkiState.LIFT_UP -> sawLift = true
                SkiState.DOWNHILL_RUN -> {
                    if (sawLift) {
                        cycles++
                        sawLift = false
                    }
                }
                else -> { /* keep looking */ }
            }
        }
        return cycles
    }
}
