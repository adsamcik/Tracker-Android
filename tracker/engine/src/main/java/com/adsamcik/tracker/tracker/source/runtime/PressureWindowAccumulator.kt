package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal data class PressureAccumulatorState(
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

internal class PressureWindowAccumulator(
	private val windowNanos: Long,
	private var state: PressureAccumulatorState? = null,
) {
	init {
		require(windowNanos > 0L)
	}

	/** Returns the completed prior window; the incoming sample starts the next window. */
	fun add(pressureHectopascals: Float, elapsedNanos: Long, providerSequence: Long): PressureWindowPayload? {
		val current = state
		val completed = if (current != null && elapsedNanos - current.windowStartElapsedNanos >= windowNanos) {
			current.toPayload()
		} else {
			null
		}
		state = if (current == null || completed != null) {
			PressureAccumulatorState(
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

internal fun decodePressureAccumulator(payload: ByteArray, version: Int): PressureAccumulatorState? = runCatching {
	if (version != PRESSURE_ACCUMULATOR_VERSION || payload.isEmpty()) return@runCatching null
	DataInputStream(ByteArrayInputStream(payload)).use { input ->
		PressureAccumulatorState(
			sampleCount = input.readInt(),
			mean = input.readDouble(),
			sumSquaredDeviations = input.readDouble(),
			minimum = input.readFloat(),
			maximum = input.readFloat(),
			windowStartElapsedNanos = input.readLong(),
			windowEndElapsedNanos = input.readLong(),
			firstProviderSequence = input.readLong(),
			lastProviderSequence = input.readLong(),
		).takeIf { it.sampleCount > 0 }
	}
}.getOrNull()

internal const val PRESSURE_ACCUMULATOR_VERSION = 1
