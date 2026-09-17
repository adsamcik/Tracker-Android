package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.StepBoundaryKind
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
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
	val counterDomainToken: StepsCounterDomainToken? = null,
	val counterEpochGeneration: Long = INITIAL_COUNTER_EPOCH_GENERATION,
) {
	init {
		require(counterEpochGeneration > 0L)
	}
}

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

internal class StepCounterEpochGenerationOverflowException :
	IllegalStateException("Steps counter epoch generation exhausted")

internal class StepWindowAccumulator(
	initialBaseline: StepBaseline?,
	private val boundary: StepBaselineBoundary? = initialBaseline?.boundary,
	initialCounterEpochGeneration: Long =
		initialBaseline?.counterEpochGeneration ?: INITIAL_COUNTER_EPOCH_GENERATION,
) {
	private var baseline = initialBaseline?.takeIf { it.boundary == boundary }
	private var counterEpochGeneration =
		baseline?.counterEpochGeneration ?: initialCounterEpochGeneration

	init {
		require(initialCounterEpochGeneration > 0L)
	}

	/** Builds a candidate window without advancing the continuing or checkpointed baseline. */
	fun preview(
		bootClockDomainId: String,
		cumulativeCount: Long,
		elapsedRealtimeNanos: Long,
		providerSequence: Long,
		receivedElapsedRealtimeNanos: Long = Long.MAX_VALUE,
		authorizationBoundary: StepAuthorizationBoundary? = null,
		counterDomainToken: StepsCounterDomainToken? = null,
		successorCounterDomainToken: () -> StepsCounterDomainToken? = { counterDomainToken },
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
			it.authorizationBoundary == authorizationBoundary &&
				it.counterDomainToken == counterDomainToken &&
				it.counterEpochGeneration == counterEpochGeneration
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
		val nextCounterEpochGeneration = if (boundaryKind == StepBoundaryKind.COUNTER_RESET) {
			if (counterEpochGeneration == Long.MAX_VALUE) {
				throw StepCounterEpochGenerationOverflowException()
			}
			counterEpochGeneration + 1L
		} else {
			counterEpochGeneration
		}
		val payloadCounterDomainToken =
			counterDomainToken.takeUnless { boundaryKind == StepBoundaryKind.COUNTER_RESET }
		val nextCounterDomainToken = if (boundaryKind == StepBoundaryKind.COUNTER_RESET) {
			successorCounterDomainToken()
		} else {
			counterDomainToken
		}
		// A reset contributes no steps, but its canonical gap still spans the last value from the
		// discarded counter domain through the first value in the new domain. Keeping that boundary
		// lets the sole fact writer distinguish a reset from a newly authorized baseline.
		val payloadBaseline = authorizedPrevious
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
			counterDomainToken = payloadCounterDomainToken,
			counterEpochGeneration = nextCounterEpochGeneration,
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
				nextCounterDomainToken,
				nextCounterEpochGeneration,
			),
		)
	}

	/** Commits exactly one preview whose predecessor is still the current durable baseline. */
	fun commit(preview: StepWindowPreview): Boolean {
		if (baseline != preview.expectedBaseline) return false
		baseline = preview.nextBaseline
		counterEpochGeneration = preview.nextBaseline.counterEpochGeneration
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
		counterDomainToken: StepsCounterDomainToken? = null,
		successorCounterDomainToken: () -> StepsCounterDomainToken? = { counterDomainToken },
	): StepCounterWindowPayload? {
		val preview = preview(
			bootClockDomainId,
			cumulativeCount,
			elapsedRealtimeNanos,
			providerSequence,
			receivedElapsedRealtimeNanos,
			authorizationBoundary,
			counterDomainToken,
			successorCounterDomainToken,
		) ?: return null
		check(commit(preview))
		return preview.payload
	}

	fun snapshot(): StepBaseline? = baseline

	fun counterEpochGeneration(): Long = counterEpochGeneration
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
		output.writeLong(counterEpochGeneration)
		output.writeBoolean(counterDomainToken != null)
		counterDomainToken?.let { output.writeUTF(it.encoded) }
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
	if (version !in LEGACY_STEP_BASELINE_VERSION..STEP_BASELINE_VERSION ||
		payload.isEmpty()
	) return@runCatching null
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
		val counterEpochGeneration = if (version >= STEP_BASELINE_VERSION) {
			input.readLong().also { require(it > 0L) }
		} else {
			INITIAL_COUNTER_EPOCH_GENERATION
		}
		val counterDomainToken = if (
			version >= STEP_COUNTER_DOMAIN_BASELINE_VERSION && input.readBoolean()
		) {
			StepsCounterDomainToken.opaque(input.readUTF())
		} else {
			null
		}
		StepBaseline(
			input.readLong(),
			input.readLong(),
			input.readLong(),
			boundary,
			authorizationBoundary,
			counterDomainToken,
			counterEpochGeneration,
		).also { require(input.available() == 0) }
	}
}.getOrNull()

internal const val STEP_BASELINE_VERSION = 7
private const val STEP_COUNTER_DOMAIN_BASELINE_VERSION = 6
private const val LEGACY_STEP_BASELINE_VERSION = 5
private const val NO_REGISTRATION_GENERATION = 0L
internal const val INITIAL_COUNTER_EPOCH_GENERATION = 1L
