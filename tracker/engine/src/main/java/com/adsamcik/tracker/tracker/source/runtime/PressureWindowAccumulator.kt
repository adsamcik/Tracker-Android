package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload

internal data class PressureAccumulatorState(
	val boundary: PressureAccumulatorBoundary,
	val sampleCount: Int,
	val mean: Double,
	val sumSquaredDeviations: Double,
	val minimum: Float,
	val maximum: Float,
	val windowStartElapsedNanos: Long,
	val windowEndElapsedNanos: Long,
	val firstProviderSequence: Long,
	val lastProviderSequence: Long,
)

/**
 * Immutable identity of the provider registration whose samples may contribute to a window.
 *
 * The eligibility fingerprint includes the durable demand vector (and therefore its manifest
 * revisions). Keeping it beside the registration generation prevents one in-process accumulator
 * from joining samples collected for different capture/control purposes.
 */
internal data class PressureAccumulatorBoundary(
	val registrationGeneration: Long,
	val eligibilityFingerprint: String,
	val appliedRevision: Long,
	val authorizationRevision: Long = 0L,
) {
	init {
		require(registrationGeneration >= 0L)
		require(eligibilityFingerprint.isNotBlank())
		require(appliedRevision >= 0L)
		require(authorizationRevision >= 0L)
	}
}

internal class PressureWindowAccumulator(
	private val windowNanos: Long,
	private val boundary: PressureAccumulatorBoundary = UNSCOPED_PRESSURE_BOUNDARY,
) {
	private var state: PressureAccumulatorState? = null

	init {
		require(windowNanos > 0L)
	}

	/** Returns the completed prior window; the incoming sample starts the next window. */
	fun add(pressureHectopascals: Float, elapsedNanos: Long, providerSequence: Long): PressureWindowPayload? {
		val current = state
		val windowElapsed = current != null && elapsedNanos - current.windowStartElapsedNanos >= windowNanos
		val completed = if (current != null && windowElapsed) {
			current.toPayload()
		} else {
			null
		}
		state = if (current == null || windowElapsed) {
			PressureAccumulatorState(
				boundary = boundary,
				sampleCount = 1,
				mean = pressureHectopascals.toDouble(),
				sumSquaredDeviations = 0.0,
				minimum = pressureHectopascals,
				maximum = pressureHectopascals,
				windowStartElapsedNanos = elapsedNanos,
				windowEndElapsedNanos = elapsedNanos,
				firstProviderSequence = providerSequence,
				lastProviderSequence = providerSequence,
			)
		} else {
			val count = current.sampleCount + 1
			val delta = pressureHectopascals - current.mean
			val mean = current.mean + delta / count
			current.copy(
				sampleCount = count,
				mean = mean,
				sumSquaredDeviations = current.sumSquaredDeviations +
					delta * (pressureHectopascals - mean),
				minimum = minOf(current.minimum, pressureHectopascals),
				maximum = maxOf(current.maximum, pressureHectopascals),
				windowEndElapsedNanos = elapsedNanos,
				lastProviderSequence = providerSequence,
			)
		}
		return completed
	}

	/** A single-sample drain remains durable evidence; product stability is carried separately. */
	fun drain(): PressureWindowPayload? = state?.toPayload().also { state = null }

	fun snapshot(): PressureAccumulatorState? = state
}

internal data class PressureWindowCapacityDecision(
	val pendingWindowCount: Int,
	val pauseAcquisition: Boolean,
)

/**
 * Bounds every retained completed window, including the actor's current FIFO head. One slot is
 * reserved for the partial window closed while acquisition is retired.
 */
internal class PressureWindowCapacityGuard(
	val maximumPendingWindows: Int,
	private val terminalReserve: Int = 1,
) {
	private val acquisitionLimit = maximumPendingWindows - terminalReserve
	var pendingWindowCount: Int = 0
		private set

	init {
		require(maximumPendingWindows > 1)
		require(terminalReserve in 1 until maximumPendingWindows)
	}

	fun acquisitionWindowEnqueued(): PressureWindowCapacityDecision {
		check(pendingWindowCount < acquisitionLimit) {
			"Pressure acquisition must be retired before its bounded window lane overflows"
		}
		pendingWindowCount++
		return PressureWindowCapacityDecision(
			pendingWindowCount = pendingWindowCount,
			pauseAcquisition = pendingWindowCount == acquisitionLimit,
		)
	}

	fun terminalWindowEnqueued(): PressureWindowCapacityDecision {
		check(pendingWindowCount < maximumPendingWindows) {
			"Pressure terminal reserve was exhausted"
		}
		pendingWindowCount++
		return PressureWindowCapacityDecision(pendingWindowCount, pauseAcquisition = false)
	}

	fun windowSettled() {
		check(pendingWindowCount > 0) { "Pressure window settlement underflow" }
		pendingWindowCount--
	}
}

/** Coordinates one event-driven resume only after the overflow gap is present in a checkpoint. */
internal class PressureCapacityResumeGate(
	private val lowWaterWindowCount: Int,
) {
	var capacityPaused: Boolean = false
		private set
	var postGapCheckpointDurable: Boolean = false
		private set
	var activeGapSequence: Long? = null
		private set
	private var resumeScheduled = false

	init {
		require(lowWaterWindowCount >= 0)
	}

	fun onCapacityPause(gapSequence: Long) {
		require(gapSequence > 0L)
		capacityPaused = true
		postGapCheckpointDurable = false
		activeGapSequence = gapSequence
		resumeScheduled = false
	}

	fun onSuccessfulPostGapCheckpoint(
		pendingWindowCount: Int,
		checkpointedGapSequence: Long?,
	): Boolean {
		require(pendingWindowCount >= 0)
		if (!capacityPaused || checkpointedGapSequence != activeGapSequence) return false
		postGapCheckpointDurable = true
		if (resumeScheduled || pendingWindowCount > lowWaterWindowCount) return false
		resumeScheduled = true
		return true
	}

	fun onResumeSucceeded() {
		capacityPaused = false
		postGapCheckpointDurable = false
		activeGapSequence = null
		resumeScheduled = false
	}

	fun fenceResume() {
		resumeScheduled = false
	}
}

private fun PressureAccumulatorState.toPayload() = PressureWindowPayload(
	sampleCount = sampleCount,
	meanHectopascals = mean,
	sumSquaredDeviations = sumSquaredDeviations,
	minimumHectopascals = minimum,
	maximumHectopascals = maximum,
	windowStartElapsedRealtimeNanos = windowStartElapsedNanos,
	windowEndElapsedRealtimeNanos = windowEndElapsedNanos,
	firstProviderSequence = firstProviderSequence,
	lastProviderSequence = lastProviderSequence,
)

private val UNSCOPED_PRESSURE_BOUNDARY = PressureAccumulatorBoundary(
	registrationGeneration = 0L,
	eligibilityFingerprint = "unscoped-test-or-projection",
	appliedRevision = 0L,
	authorizationRevision = 0L,
)

internal const val PRESSURE_RUNTIME_COMPONENT_VERSION = 4
