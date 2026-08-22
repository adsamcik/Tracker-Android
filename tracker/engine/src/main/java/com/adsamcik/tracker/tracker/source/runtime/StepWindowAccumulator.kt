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
	val boundary: StepBaselineBoundary? = null,
)

internal data class StepBaselineBoundary(
	val registrationGeneration: Long,
	val eligibilityFingerprint: String,
) {
	init {
		require(registrationGeneration > 0L)
		require(eligibilityFingerprint.isNotBlank())
	}
}

internal class StepWindowAccumulator(
	initialBaseline: StepBaseline?,
	private val boundary: StepBaselineBoundary? = initialBaseline?.boundary,
) {
	private var baseline = initialBaseline?.takeIf { it.boundary == boundary }

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
		baseline = StepBaseline(cumulativeCount, elapsedRealtimeNanos, providerSequence, boundary)
		return payload
	}

	fun snapshot(): StepBaseline? = baseline
}

internal fun StepBaseline.encode(): ByteArray = ByteArrayOutputStream().use { bytes ->
	DataOutputStream(bytes).use { output ->
		output.writeLong(boundary?.registrationGeneration ?: NO_REGISTRATION_GENERATION)
		output.writeUTF(boundary?.eligibilityFingerprint.orEmpty())
		output.writeLong(cumulativeCount)
		output.writeLong(elapsedRealtimeNanos)
		output.writeLong(providerSequence)
	}
	bytes.toByteArray()
}

internal fun decodeStepBaseline(
	payload: ByteArray,
	version: Int,
	expectedBoundary: StepBaselineBoundary? = null,
): StepBaseline? = runCatching {
	if (version != STEP_BASELINE_VERSION || payload.isEmpty()) return@runCatching null
	DataInputStream(ByteArrayInputStream(payload)).use { input ->
		val registrationGeneration = input.readLong()
		val eligibilityFingerprint = input.readUTF()
		val boundary = if (registrationGeneration == NO_REGISTRATION_GENERATION && eligibilityFingerprint.isEmpty()) {
			null
		} else {
			StepBaselineBoundary(registrationGeneration, eligibilityFingerprint)
		}
		if (expectedBoundary != null && boundary != expectedBoundary) return@runCatching null
		StepBaseline(
			input.readLong(),
			input.readLong(),
			input.readLong(),
			boundary,
		).also { require(input.available() == 0) }
	}
}.getOrNull()

internal const val STEP_BASELINE_VERSION = 2
private const val NO_REGISTRATION_GENERATION = 0L
