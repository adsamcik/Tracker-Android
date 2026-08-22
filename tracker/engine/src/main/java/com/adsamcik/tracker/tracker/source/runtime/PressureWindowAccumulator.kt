package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

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
 * revisions). Keeping it beside the registration generation prevents a checkpoint from joining
 * samples that were collected for different capture/control purposes.
 */
internal data class PressureAccumulatorBoundary(
	val registrationGeneration: Long,
	val eligibilityFingerprint: String,
	val appliedRevision: Long,
) {
	init {
		require(registrationGeneration >= 0L)
		require(eligibilityFingerprint.isNotBlank())
		require(appliedRevision >= 0L)
	}
}

internal class PressureWindowAccumulator(
	private val windowNanos: Long,
	private val boundary: PressureAccumulatorBoundary = UNSCOPED_PRESSURE_BOUNDARY,
	private var state: PressureAccumulatorState? = null,
) {
	init {
		require(windowNanos > 0L)
		require(state == null || state?.boundary == boundary) {
			"A pressure window cannot cross a registration or demand-eligibility boundary"
		}
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

internal fun PressureAccumulatorState.encode(): ByteArray = ByteArrayOutputStream().use { bytes ->
	DataOutputStream(bytes).use { output ->
		output.writeLong(boundary.registrationGeneration)
		output.writeUTF(boundary.eligibilityFingerprint)
		output.writeLong(boundary.appliedRevision)
		output.writeInt(sampleCount)
		output.writeDouble(mean)
		output.writeDouble(sumSquaredDeviations)
		output.writeFloat(minimum)
		output.writeFloat(maximum)
		output.writeLong(windowStartElapsedNanos)
		output.writeLong(windowEndElapsedNanos)
		output.writeLong(firstProviderSequence)
		output.writeLong(lastProviderSequence)
	}
	bytes.toByteArray()
}

internal fun decodePressureAccumulator(
	payload: ByteArray,
	version: Int,
	expectedBoundary: PressureAccumulatorBoundary? = null,
): PressureAccumulatorState? = runCatching {
	if (version != PRESSURE_ACCUMULATOR_VERSION || payload.isEmpty()) return@runCatching null
	DataInputStream(ByteArrayInputStream(payload)).use { input ->
		val decoded = PressureAccumulatorState(
			boundary = PressureAccumulatorBoundary(
				registrationGeneration = input.readLong(),
				eligibilityFingerprint = input.readUTF(),
				appliedRevision = input.readLong(),
			),
			sampleCount = input.readInt(),
			mean = input.readDouble(),
			sumSquaredDeviations = input.readDouble(),
			minimum = input.readFloat(),
			maximum = input.readFloat(),
			windowStartElapsedNanos = input.readLong(),
			windowEndElapsedNanos = input.readLong(),
			firstProviderSequence = input.readLong(),
			lastProviderSequence = input.readLong(),
		)
		require(input.available() == 0)
		decoded.takeIf { state ->
			state.sampleCount > 0 && (expectedBoundary == null || state.boundary == expectedBoundary)
		}
	}
}.getOrNull()

private val UNSCOPED_PRESSURE_BOUNDARY = PressureAccumulatorBoundary(
	registrationGeneration = 0L,
	eligibilityFingerprint = "unscoped-test-or-projection",
	appliedRevision = 0L,
)

internal const val PRESSURE_ACCUMULATOR_VERSION = 2
