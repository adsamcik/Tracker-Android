package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal data class StepBaseline(
	val cumulativeCount: Long,
	val elapsedRealtimeNanos: Long,
	val providerSequence: Long,
)

internal class StepWindowAccumulator(private var baseline: StepBaseline?) {
	fun accept(
		bootClockDomainId: String,
		cumulativeCount: Long,
		elapsedRealtimeNanos: Long,
		providerSequence: Long,
	): StepCounterWindowPayload {
		require(cumulativeCount >= 0L)
		val previous = baseline
		val reset = previous == null || cumulativeCount < previous.cumulativeCount
		val delta = when {
			previous == null -> 0L
			reset -> cumulativeCount
			else -> cumulativeCount - previous.cumulativeCount
		}
		val payload = StepCounterWindowPayload(
			bootClockDomainId = bootClockDomainId,
			firstCumulativeCount = previous?.cumulativeCount ?: cumulativeCount,
			lastCumulativeCount = cumulativeCount,
			deltaCount = delta,
			windowStartElapsedRealtimeNanos = previous?.elapsedRealtimeNanos ?: elapsedRealtimeNanos,
			windowEndElapsedRealtimeNanos = elapsedRealtimeNanos,
			firstProviderSequence = previous?.providerSequence ?: providerSequence,
			lastProviderSequence = providerSequence,
			baselineReset = reset,
		)
		baseline = StepBaseline(cumulativeCount, elapsedRealtimeNanos, providerSequence)
		return payload
	}

	fun snapshot(): StepBaseline? = baseline
}

internal fun StepBaseline.encode(): ByteArray = ByteArrayOutputStream().use { bytes ->
	DataOutputStream(bytes).use { output ->
		output.writeLong(cumulativeCount)
		output.writeLong(elapsedRealtimeNanos)
		output.writeLong(providerSequence)
	}
	bytes.toByteArray()
}

internal fun decodeStepBaseline(payload: ByteArray, version: Int): StepBaseline? = runCatching {
	if (version != STEP_BASELINE_VERSION || payload.isEmpty()) return@runCatching null
	DataInputStream(ByteArrayInputStream(payload)).use { input ->
		StepBaseline(input.readLong(), input.readLong(), input.readLong())
	}
}.getOrNull()

internal const val STEP_BASELINE_VERSION = 1
