package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.PressureSensorAccuracy
import com.adsamcik.tracker.tracker.source.model.PressureWindowClosureKind
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.expectedPressureSampleCount
import kotlin.math.sqrt

internal data class PressureAccumulatorState(
	val boundary: PressureAccumulatorBoundary,
	val sampleCount: Int,
	val mean: Double,
	val sumSquaredDeviations: Double,
	val firstPressure: Float,
	val lastPressure: Float,
	val minimum: Float,
	val maximum: Float,
	val meanElapsedSeconds: Double,
	val sumSquaredElapsedSeconds: Double,
	val sumElapsedPressureCoDeviations: Double,
	val sensorAccuracy: PressureSensorAccuracy,
	val maximumInterSampleGapNanos: Long,
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
	private val effectiveSamplePeriodMicros: Int,
	private val effectiveMaximumReportLatencyMicros: Int,
	private val boundary: PressureAccumulatorBoundary = UNSCOPED_PRESSURE_BOUNDARY,
) {
	private var state: PressureAccumulatorState? = null

	init {
		require(windowNanos > 0L)
		require(effectiveSamplePeriodMicros > 0)
		require(effectiveMaximumReportLatencyMicros >= 0)
		expectedPressureSampleCount(windowNanos, effectiveSamplePeriodMicros)
	}

	/** Returns the completed prior window; the incoming sample starts the next window. */
	fun add(
		pressureHectopascals: Float,
		elapsedNanos: Long,
		providerSequence: Long,
		sensorAccuracy: PressureSensorAccuracy = PressureSensorAccuracy.UNKNOWN,
	): PressureWindowPayload? {
		requireQualifiedPressureSample(
			pressureHectopascals,
			elapsedNanos,
			providerSequence,
			sensorAccuracy,
		)
		val current = state
		current?.requireLaterPressureSample(elapsedNanos, providerSequence)
		val windowElapsed = current != null && elapsedNanos - current.windowStartElapsedNanos >= windowNanos
		val completed = if (current != null && windowElapsed) {
			current.toPayload(
				PressureWindowClosureKind.TARGET_ELAPSED,
				windowNanos,
				effectiveSamplePeriodMicros,
				effectiveMaximumReportLatencyMicros,
			)
		} else {
			null
		}
		state = if (current == null || windowElapsed) {
			firstPressureAccumulatorState(
				boundary,
				pressureHectopascals,
				elapsedNanos,
				providerSequence,
				sensorAccuracy,
			)
		} else {
			current.withNextPressureSample(
				pressureHectopascals,
				elapsedNanos,
				providerSequence,
				sensorAccuracy,
			)
		}
		return completed
	}

	/** A boundary drain remains durable partial evidence; product stability is carried separately. */
	fun drain(
		closureKind: PressureWindowClosureKind = PressureWindowClosureKind.SOURCE_BOUNDARY,
	): PressureWindowPayload? {
		require(closureKind != PressureWindowClosureKind.LEGACY_UNAVAILABLE)
		return state?.toPayload(
			closureKind,
			windowNanos,
			effectiveSamplePeriodMicros,
			effectiveMaximumReportLatencyMicros,
		).also { state = null }
	}

	fun snapshot(): PressureAccumulatorState? = state
}

private fun requireQualifiedPressureSample(
	pressureHectopascals: Float,
	elapsedNanos: Long,
	providerSequence: Long,
	sensorAccuracy: PressureSensorAccuracy,
) {
	require(pressureHectopascals.isFinite() && pressureHectopascals > 0f)
	require(elapsedNanos >= 0L)
	require(providerSequence > 0L)
	require(sensorAccuracy != PressureSensorAccuracy.LEGACY_UNAVAILABLE)
}

private fun PressureAccumulatorState.requireLaterPressureSample(
	elapsedNanos: Long,
	providerSequence: Long,
) {
	require(elapsedNanos > windowEndElapsedNanos)
	require(providerSequence > lastProviderSequence)
}

private fun firstPressureAccumulatorState(
	boundary: PressureAccumulatorBoundary,
	pressureHectopascals: Float,
	elapsedNanos: Long,
	providerSequence: Long,
	sensorAccuracy: PressureSensorAccuracy,
) = PressureAccumulatorState(
	boundary = boundary,
	sampleCount = 1,
	mean = pressureHectopascals.toDouble(),
	sumSquaredDeviations = 0.0,
	firstPressure = pressureHectopascals,
	lastPressure = pressureHectopascals,
	minimum = pressureHectopascals,
	maximum = pressureHectopascals,
	meanElapsedSeconds = 0.0,
	sumSquaredElapsedSeconds = 0.0,
	sumElapsedPressureCoDeviations = 0.0,
	sensorAccuracy = sensorAccuracy,
	maximumInterSampleGapNanos = 0L,
	windowStartElapsedNanos = elapsedNanos,
	windowEndElapsedNanos = elapsedNanos,
	firstProviderSequence = providerSequence,
	lastProviderSequence = providerSequence,
)

private fun PressureAccumulatorState.withNextPressureSample(
	pressureHectopascals: Float,
	elapsedNanos: Long,
	providerSequence: Long,
	sensorAccuracy: PressureSensorAccuracy,
): PressureAccumulatorState {
	val count = sampleCount + 1
	val pressure = pressureHectopascals.toDouble()
	val pressureDelta = pressure - mean
	val nextMean = mean + pressureDelta / count
	val elapsedSeconds = (elapsedNanos - windowStartElapsedNanos).toDouble() / NANOS_PER_SECOND
	val elapsedDelta = elapsedSeconds - meanElapsedSeconds
	val nextMeanElapsedSeconds = meanElapsedSeconds + elapsedDelta / count
	return copy(
		sampleCount = count,
		mean = nextMean,
		sumSquaredDeviations = sumSquaredDeviations + pressureDelta * (pressure - nextMean),
		lastPressure = pressureHectopascals,
		minimum = minOf(minimum, pressureHectopascals),
		maximum = maxOf(maximum, pressureHectopascals),
		meanElapsedSeconds = nextMeanElapsedSeconds,
		sumSquaredElapsedSeconds = sumSquaredElapsedSeconds +
			elapsedDelta * (elapsedSeconds - nextMeanElapsedSeconds),
		sumElapsedPressureCoDeviations = sumElapsedPressureCoDeviations +
			elapsedDelta * (pressure - nextMean),
		sensorAccuracy = worsePressureAccuracy(this.sensorAccuracy, sensorAccuracy),
		maximumInterSampleGapNanos = maxOf(
			maximumInterSampleGapNanos,
			elapsedNanos - windowEndElapsedNanos,
		),
		windowEndElapsedNanos = elapsedNanos,
		lastProviderSequence = providerSequence,
	)
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

private fun PressureAccumulatorState.toPayload(
	closureKind: PressureWindowClosureKind,
	targetWindowDurationNanos: Long,
	effectiveSamplePeriodMicros: Int,
	effectiveMaximumReportLatencyMicros: Int,
): PressureWindowPayload {
	val slope = if (sumSquaredElapsedSeconds > 0.0) {
		(sumElapsedPressureCoDeviations / sumSquaredElapsedSeconds).canonicalZero()
	} else {
		null
	}
	val rSquared = if (
		sumSquaredElapsedSeconds > 0.0 && sumSquaredDeviations > 0.0
	) {
		val correlation = sumElapsedPressureCoDeviations /
			sqrt(sumSquaredElapsedSeconds) / sqrt(sumSquaredDeviations)
		check(correlation.isFinite())
		(correlation * correlation).coerceIn(0.0, 1.0).canonicalZero()
	} else {
		null
	}
	return PressureWindowPayload(
		sampleCount = sampleCount,
		meanHectopascals = mean,
		sumSquaredDeviations = sumSquaredDeviations,
		minimumHectopascals = minimum,
		maximumHectopascals = maximum,
		windowStartElapsedRealtimeNanos = windowStartElapsedNanos,
		windowEndElapsedRealtimeNanos = windowEndElapsedNanos,
		firstProviderSequence = firstProviderSequence,
		lastProviderSequence = lastProviderSequence,
		firstHectopascals = firstPressure,
		lastHectopascals = lastPressure,
		slopeHectopascalsPerSecond = slope,
		rSquared = rSquared,
		sensorAccuracy = sensorAccuracy,
		effectiveSamplePeriodMicros = effectiveSamplePeriodMicros,
		effectiveMaximumReportLatencyMicros = effectiveMaximumReportLatencyMicros,
		targetWindowDurationNanos = targetWindowDurationNanos,
		expectedSampleCount = expectedPressureSampleCount(
			targetWindowDurationNanos,
			effectiveSamplePeriodMicros,
		),
		maximumInterSampleGapNanos = maximumInterSampleGapNanos,
		closureKind = closureKind,
	)
}

private fun worsePressureAccuracy(
	first: PressureSensorAccuracy,
	second: PressureSensorAccuracy,
): PressureSensorAccuracy = if (first.qualityRank <= second.qualityRank) {
	first
} else {
	second
}

private val PressureSensorAccuracy.qualityRank: Int
	get() = when (this) {
		PressureSensorAccuracy.UNKNOWN -> 0
		PressureSensorAccuracy.UNRELIABLE -> 1
		PressureSensorAccuracy.LOW -> 2
		PressureSensorAccuracy.MEDIUM -> 3
		PressureSensorAccuracy.HIGH -> 4
		PressureSensorAccuracy.LEGACY_UNAVAILABLE -> error(
			"Legacy Pressure accuracy cannot enter a qualified accumulator",
		)
	}

private fun Double.canonicalZero(): Double = if (this == 0.0) {
	0.0
} else {
	this
}

private const val NANOS_PER_SECOND = 1_000_000_000.0

private val UNSCOPED_PRESSURE_BOUNDARY = PressureAccumulatorBoundary(
	registrationGeneration = 0L,
	eligibilityFingerprint = "unscoped-test-or-projection",
	appliedRevision = 0L,
	authorizationRevision = 0L,
)

internal const val PRESSURE_RUNTIME_COMPONENT_VERSION = 4
