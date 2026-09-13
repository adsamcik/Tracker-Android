package com.adsamcik.tracker.tracker.source.ambient.steps

import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Explicit, dormant command for one already-accepted Ambient Steps provider replacement.
 *
 * The provider-registration owner must invoke this before removing the RETIRING predecessor. It is
 * deliberately not wired to lifecycle scheduling yet: activation remains a separate rollout gate.
 */
internal data class AmbientStepsProviderHandoffCommand(
	val predecessorRegistrationGeneration: Long,
	val successorRegistrationGeneration: Long,
	val observedBootId: String,
	val observedElapsedRealtimeNanos: Long,
	val observedAtMs: Long,
	val zoneId: ZoneId,
) {
	init {
		require(predecessorRegistrationGeneration > 0L)
		require(successorRegistrationGeneration > predecessorRegistrationGeneration)
		require(observedBootId.isNotBlank())
		require(observedElapsedRealtimeNanos >= 0L)
		require(observedAtMs >= 0L)
	}
}

internal enum class AmbientStepsProviderHandoffIneligibleReason {
	REPLACEMENT_NOT_ACCEPTED,
	PREDECESSOR_NOT_DRAINABLE,
	AUTHORITY_NOT_PROVABLE,
	DESTINATION_OWNER_INELIGIBLE,
	PROVIDER_READER_UNAVAILABLE,
}

internal enum class AmbientStepsProviderHandoffStaleReason {
	CURRENT_STATE_CHANGED,
	LIFECYCLE_CHANGED,
	REGISTRATION_CHANGED,
	AUTHORIZATION_CHANGED,
	CURSOR_CHANGED,
	FACT_RETRACTED,
}

internal enum class AmbientStepsProviderHandoffRetryableReason {
	PROVIDER_READ_FAILED,
	PROVIDER_RESULT_MISMATCH,
	STORAGE_UNAVAILABLE,
}

internal enum class AmbientStepsProviderDrainDisposition {
	/** The predecessor already ended at the exact provider boundary, so no read was needed. */
	ALREADY_COVERED,

	/** One affirmative old-provider aggregate covered the entire remaining predecessor interval. */
	APPLIED,

	/** The final aggregate exactly replayed the already-stored semantic value. */
	UNCHANGED,

	/** The old provider returned no affirmative aggregate; the entire remainder is a gap. */
	UNAVAILABLE,

	/** One bounded aggregate was stored and the undrained remainder is an explicit gap. */
	PARTIAL,
}

internal sealed interface AmbientStepsProviderHandoffResult {
	data class Ineligible(
		val reason: AmbientStepsProviderHandoffIneligibleReason,
	) : AmbientStepsProviderHandoffResult

	data class Stale(
		val reason: AmbientStepsProviderHandoffStaleReason,
	) : AmbientStepsProviderHandoffResult

	data class Retryable(
		val reason: AmbientStepsProviderHandoffRetryableReason,
	) : AmbientStepsProviderHandoffResult

	data class Completed(
		val predecessorRegistrationGeneration: Long,
		val successorRegistrationGeneration: Long,
		val successorStartTimeMs: Long,
		val drainDisposition: AmbientStepsProviderDrainDisposition,
		val drainedWindow: AmbientStepsProviderReadWindow?,
		val logicalFactId: String?,
		val replayed: Boolean,
	) : AmbientStepsProviderHandoffResult {
		init {
			require(predecessorRegistrationGeneration > 0L)
			require(successorRegistrationGeneration > predecessorRegistrationGeneration)
			require(successorStartTimeMs >= 0L && successorStartTimeMs % 1_000L == 0L)
			require((logicalFactId != null) == (drainedWindow != null))
			require(!replayed || drainDisposition == AmbientStepsProviderDrainDisposition.ALREADY_COVERED)
		}
	}
}

/** Thin command boundary around the canonical Ambient Steps importer. */
@Singleton
internal class AmbientStepsProviderHandoffCoordinator @Inject constructor(
	private val importer: AmbientStepsFactImporter,
) {
	suspend fun execute(
		command: AmbientStepsProviderHandoffCommand,
	): AmbientStepsProviderHandoffResult = importer.handoffAcceptedProvider(command)
}
