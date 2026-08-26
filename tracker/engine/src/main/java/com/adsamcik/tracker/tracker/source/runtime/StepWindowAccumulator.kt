package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.StepBoundaryKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal data class StepBaseline(
	val cumulativeCount: Long,
	val elapsedRealtimeNanos: Long,
	val providerSequence: Long,
	val boundary: StepBaselineBoundary? = null,
	val authorizationBoundary: StepAuthorizationBoundary? = null,
)

internal data class StepBaselineBoundary(
	val registrationGeneration: Long,
) {
	init {
		require(registrationGeneration > 0L)
	}
}

internal data class StepAuthorizationBoundary(
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
	val purposeEligibilityMask: Long,
	val effectiveElapsedRealtimeNanos: Long = 0L,
) {
	init {
		require(authorizationRevision > 0L)
		require(authorizationFingerprint.isNotBlank())
		require(purposeEligibilityMask > 0L)
		require(effectiveElapsedRealtimeNanos >= 0L)
	}
}

internal data class StepWindowPreview(
	val payload: StepCounterWindowPayload,
	internal val expectedBaseline: StepBaseline?,
	internal val nextBaseline: StepBaseline,
)

internal class StepWindowAccumulator(
	initialBaseline: StepBaseline?,
	private val boundary: StepBaselineBoundary? = initialBaseline?.boundary,
) {
	private var baseline = initialBaseline?.takeIf { it.boundary == boundary }

	/** Builds a candidate window without advancing the continuing or checkpointed baseline. */
	fun preview(
		bootClockDomainId: String,
		cumulativeCount: Long,
		elapsedRealtimeNanos: Long,
		providerSequence: Long,
		receivedElapsedRealtimeNanos: Long = Long.MAX_VALUE,
		authorizationBoundary: StepAuthorizationBoundary? = null,
	): StepWindowPreview? {
		require(cumulativeCount >= 0L)
		val previous = baseline
		if (authorizationBoundary?.let {
			elapsedRealtimeNanos < it.effectiveElapsedRealtimeNanos
		} == true) return null
		if (!isFreshStepSampleTimestamp(
			providerElapsedNanos = elapsedRealtimeNanos,
			receivedElapsedNanos = receivedElapsedRealtimeNanos,
			previousProviderElapsedNanos = previous?.elapsedRealtimeNanos,
		) || previous?.providerSequence?.let { providerSequence <= it } == true) return null
		val authorizedPrevious = previous?.takeIf {
			it.authorizationBoundary == authorizationBoundary
		}
		val boundaryKind = when {
			authorizedPrevious == null -> StepBoundaryKind.BASELINE
			cumulativeCount < authorizedPrevious.cumulativeCount -> StepBoundaryKind.COUNTER_RESET
			else -> StepBoundaryKind.COVERED
		}
		val delta = if (boundaryKind == StepBoundaryKind.COVERED) {
			cumulativeCount - requireNotNull(authorizedPrevious).cumulativeCount
		} else {
			// A baseline contributes nothing. A hardware/provider counter reset destroys the
			// interval between cumulative domains, so the new absolute value is also not steps
			// observed by this app (for example 10_000 -> 3 must not become +3).
			0L
		}
		val payloadBaseline = authorizedPrevious?.takeIf {
			boundaryKind == StepBoundaryKind.COVERED
		}
		val payload = StepCounterWindowPayload(
			bootClockDomainId = bootClockDomainId,
			firstCumulativeCount = payloadBaseline?.cumulativeCount ?: cumulativeCount,
			lastCumulativeCount = cumulativeCount,
			deltaCount = delta,
			windowStartElapsedRealtimeNanos =
				payloadBaseline?.elapsedRealtimeNanos ?: elapsedRealtimeNanos,
			windowEndElapsedRealtimeNanos = elapsedRealtimeNanos,
			firstProviderSequence = payloadBaseline?.providerSequence ?: providerSequence,
			lastProviderSequence = providerSequence,
			boundaryKind = boundaryKind,
		)
		return StepWindowPreview(
			payload = payload,
			expectedBaseline = previous,
			nextBaseline = StepBaseline(
				cumulativeCount,
				elapsedRealtimeNanos,
				providerSequence,
				boundary,
				authorizationBoundary,
			),
		)
	}

	/** Commits exactly one preview whose predecessor is still the current durable baseline. */
	fun commit(preview: StepWindowPreview): Boolean {
		if (baseline != preview.expectedBaseline) return false
		baseline = preview.nextBaseline
		return true
	}

	/** Convenience for callers whose preview is already known to be durable. */
	fun accept(
		bootClockDomainId: String,
		cumulativeCount: Long,
		elapsedRealtimeNanos: Long,
		providerSequence: Long,
		receivedElapsedRealtimeNanos: Long = Long.MAX_VALUE,
		authorizationBoundary: StepAuthorizationBoundary? = null,
	): StepCounterWindowPayload? {
		val preview = preview(
			bootClockDomainId,
			cumulativeCount,
			elapsedRealtimeNanos,
			providerSequence,
			receivedElapsedRealtimeNanos,
			authorizationBoundary,
		) ?: return null
		check(commit(preview))
		return preview.payload
	}

	fun snapshot(): StepBaseline? = baseline
}

internal fun isFreshStepSampleTimestamp(
	providerElapsedNanos: Long,
	receivedElapsedNanos: Long,
	previousProviderElapsedNanos: Long?,
): Boolean = providerElapsedNanos > 0L &&
	providerElapsedNanos <= receivedElapsedNanos &&
	(previousProviderElapsedNanos == null || providerElapsedNanos > previousProviderElapsedNanos)

internal fun StepBaseline.encode(): ByteArray = ByteArrayOutputStream().use { bytes ->
	DataOutputStream(bytes).use { output ->
		output.writeLong(boundary?.registrationGeneration ?: NO_REGISTRATION_GENERATION)
		output.writeBoolean(authorizationBoundary != null)
		authorizationBoundary?.let { authorization ->
			output.writeLong(authorization.authorizationRevision)
			output.writeUTF(authorization.authorizationFingerprint)
			output.writeLong(authorization.purposeEligibilityMask)
			output.writeLong(authorization.effectiveElapsedRealtimeNanos)
		}
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
		val boundary = if (registrationGeneration == NO_REGISTRATION_GENERATION) {
			null
		} else {
			StepBaselineBoundary(registrationGeneration)
		}
		if (expectedBoundary != null && boundary != expectedBoundary) return@runCatching null
		val authorizationBoundary = if (input.readBoolean()) {
			StepAuthorizationBoundary(
				authorizationRevision = input.readLong(),
				authorizationFingerprint = input.readUTF(),
				purposeEligibilityMask = input.readLong(),
				effectiveElapsedRealtimeNanos = input.readLong(),
			)
		} else {
			null
		}
		StepBaseline(
			input.readLong(),
			input.readLong(),
			input.readLong(),
			boundary,
			authorizationBoundary,
		).also { require(input.available() == 0) }
	}
}.getOrNull()

internal const val STEP_BASELINE_VERSION = 5
private const val NO_REGISTRATION_GENERATION = 0L
