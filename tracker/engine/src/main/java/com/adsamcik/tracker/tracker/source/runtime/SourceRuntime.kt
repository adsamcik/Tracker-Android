package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import kotlinx.coroutines.flow.StateFlow

interface SourceRuntime<P : SourcePlan> {
	val source: SourceKind
	val capabilities: StateFlow<SourceCapabilities>

	suspend fun start(plan: P, sink: SourceEventSink): SourceStartResult
	suspend fun start(
		claim: SourceRuntimeClaim,
		plan: P,
		sink: SourceEventSink,
	): SourceStartResult {
		require(claim.source == source)
		return start(plan, sink)
	}
	suspend fun reconfigure(plan: P, sink: SourceEventSink): SourceApplyResult
	suspend fun reconfigure(
		claim: SourceRuntimeClaim,
		plan: P,
		sink: SourceEventSink,
	): SourceApplyResult {
		require(claim.source == source)
		return reconfigure(plan, sink)
	}
	suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck
	suspend fun close()

	/**
	 * Releases only the logical runtime join currently owned by [claim]. The safe default has no
	 * side effects: production runtimes opt in after binding the claim under their lifecycle lock.
	 */
	suspend fun shutdownIfOwned(
		claim: SourceRuntimeClaim,
		cutoff: SessionCutoff,
	): OwnedSourceShutdown {
		require(claim.source == source)
		return OwnedSourceShutdown.NotOwned
	}
}

/**
 * Runtime contract required by [SourceRuntimeRegistry].
 *
 * A coordinator-owned provider can side-effect before it reports acceptance, so every registered
 * runtime must expose exact action-attempt cleanup. Keeping this as a distinct subtype prevents a
 * newly added source from silently inheriting the legacy no-op claim defaults.
 */
interface ClaimedSourceRuntime<P : SourcePlan> : SourceRuntime<P> {
	override suspend fun start(
		claim: SourceRuntimeClaim,
		plan: P,
		sink: SourceEventSink,
	): SourceStartResult

	override suspend fun reconfigure(
		claim: SourceRuntimeClaim,
		plan: P,
		sink: SourceEventSink,
	): SourceApplyResult

	override suspend fun shutdownIfOwned(
		claim: SourceRuntimeClaim,
		cutoff: SessionCutoff,
	): OwnedSourceShutdown
}

/** Process-local ownership derived from one already-durable lifecycle-action attempt. */
data class SourceRuntimeClaim(
	val source: SourceKind,
	val actionId: String,
	val attemptCount: Int,
	val leaseGeneration: Long,
	val logicalTrackingId: String,
	val serviceRunId: String,
) {
	init {
		require(actionId.isNotBlank())
		require(attemptCount > 0)
		require(leaseGeneration > 0L)
		require(logicalTrackingId.isNotBlank())
		require(serviceRunId.isNotBlank())
	}
}

data class SourceProviderKey(
	val sourceInstanceId: SourceInstanceId,
	val registrationGeneration: Long,
) {
	init {
		require(sourceInstanceId.value.isNotBlank())
		require(registrationGeneration > 0L)
	}
}

sealed interface OwnedSourceShutdown {
	data object NotOwned : OwnedSourceShutdown

	data class Released(
		val provider: SourceProviderKey?,
		val stopAck: SourceStopAck?,
	) : OwnedSourceShutdown

	data class Incomplete(
		val provider: SourceProviderKey?,
		val stopAck: SourceStopAck?,
	) : OwnedSourceShutdown
}

internal fun SourceRegistration.providerKey(): SourceProviderKey = SourceProviderKey(
	sourceInstanceId = SourceInstanceId(state.sourceInstanceId),
	registrationGeneration = state.registrationGeneration,
)

internal fun SourceStopAck.providerKeyOrNull(): SourceProviderKey? =
	registrationGeneration.takeIf { it > 0L }?.let { generation ->
		SourceProviderKey(sourceInstanceId, generation)
	}

internal fun SourceStopAck.toOwnedShutdown(): OwnedSourceShutdown {
	return if (hasCompleteTerminalRetirement()) {
		OwnedSourceShutdown.Released(providerKeyOrNull(), this)
	} else {
		OwnedSourceShutdown.Incomplete(providerKeyOrNull(), this)
	}
}

internal fun SourceStopAck.hasCompleteTerminalRetirement(): Boolean =
	status == SourceStopStatus.COMPLETE &&
		registrationRemovalOutcome in setOf(
			RegistrationRemovalOutcome.REMOVED,
			RegistrationRemovalOutcome.NOT_REGISTERED,
		) &&
		providerFlushOutcome !in setOf(ProviderFlushOutcome.FAILED, ProviderFlushOutcome.TIMED_OUT) &&
		appDrainComplete

internal fun SourceStopAck.hasIncompleteTerminalRetirement(): Boolean =
	!hasCompleteTerminalRetirement()

/** Binds provider retirement evidence to the exact durable action that owned the provider join. */
internal fun SourceStopAck.withSessionMembership(claim: SourceRuntimeClaim?): SourceStopAck {
	claim ?: return this
	check(source == claim.source) { "Stop acknowledgement belongs to another source claim" }
	check(logicalTrackingId == null || logicalTrackingId == claim.logicalTrackingId) {
		"Stop acknowledgement belongs to another logical session"
	}
	check(serviceRunId == null || serviceRunId == claim.serviceRunId) {
		"Stop acknowledgement belongs to another service run"
	}
	return copy(
		logicalTrackingId = claim.logicalTrackingId,
		serviceRunId = claim.serviceRunId,
	)
}

fun interface SourceEventSink {
	suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff

	/**
	 * Atomically admits one sensor fact with its provider checkpoint. Implementations that do not
	 * own that transaction must fail explicitly instead of weakening this to legacy admission.
	 */
	suspend fun admit(
		candidate: SourceEvidenceCandidate<*>,
		checkpoint: SensorAdmissionCheckpoint,
	): SourceAdmissionHandoff = SourceAdmissionHandoff.RetryableFailure(
		SourceAdmissionFailureCode.ATOMIC_CHECKPOINT_UNSUPPORTED,
	)

	/** Atomic provider-delivery path. Sources adopt this without weakening the legacy single-unit API. */
	suspend fun admit(delivery: SourceDeliveryCandidate): SourceDeliveryAdmissionHandoff =
		SourceDeliveryAdmissionHandoff.TerminalFailure(SourceAdmissionFailureCode.INVALID_EVIDENCE)
}

sealed interface SourceDeliveryAdmissionHandoff {
	data class Durable(val admissionOrdinals: List<Long>) : SourceDeliveryAdmissionHandoff
	data class Duplicate(val existingAdmissionOrdinals: List<Long>) : SourceDeliveryAdmissionHandoff
	data class TerminalFailure(val code: SourceAdmissionFailureCode) : SourceDeliveryAdmissionHandoff
	data class RetryableFailure(val code: SourceAdmissionFailureCode) : SourceDeliveryAdmissionHandoff
}

sealed interface SourceAdmissionHandoff {
	data class Durable(val admissionOrdinal: Long) : SourceAdmissionHandoff
	data class Duplicate(val existingAdmissionOrdinal: Long) : SourceAdmissionHandoff
	data class TerminalFailure(val code: SourceAdmissionFailureCode) : SourceAdmissionHandoff
	data class RetryableFailure(val code: SourceAdmissionFailureCode) : SourceAdmissionHandoff
}

enum class SourceAdmissionFailureCode {
	STALE_REGISTRATION_GENERATION,
	SOURCE_POLICY_STALE,
	STALE_COLLECTED_DATA_EPOCH,
	BEFORE_RETENTION_BOUNDARY,
	INVALID_EVIDENCE,
	CODEC_UNSUPPORTED,
	STORAGE_UNAVAILABLE,
	STORAGE_FULL,
	ATOMIC_CHECKPOINT_UNSUPPORTED,
	UNKNOWN,
}

data class SourceCapabilities(
	val available: Boolean,
	val batchingSupported: Boolean,
	val flushSupported: Boolean,
	val maximumBatchSize: Int?,
	val minimumDelayMs: Long?,
	val degradedReasons: Set<SourceDegradedReason> = emptySet(),
)

sealed interface SourceStartResult {
	data class Started(val applied: AppliedSourcePlan) : SourceStartResult
	data class Degraded(val applied: AppliedSourcePlan) : SourceStartResult
	data class Blocked(val applied: AppliedSourcePlan) : SourceStartResult
	data class Failed(val applied: AppliedSourcePlan, val retryable: Boolean) : SourceStartResult
}

sealed interface SourceApplyResult {
	data class Applied(val state: AppliedSourcePlan, val stopAck: SourceStopAck? = null) : SourceApplyResult
	data class Degraded(val state: AppliedSourcePlan, val stopAck: SourceStopAck? = null) : SourceApplyResult
	data class RolledBack(val state: AppliedSourcePlan, val stopAck: SourceStopAck? = null) : SourceApplyResult
	data class Failed(
		val state: AppliedSourcePlan,
		val retryable: Boolean,
		val stopAck: SourceStopAck? = null,
	) : SourceApplyResult
}

data class SessionCutoff(
	val logicalTrackingId: String,
	val elapsedRealtimeNanos: Long,
	val wallTimeMs: Long,
	val deadlineElapsedRealtimeNanos: Long,
)

data class SourceStopAck(
	val source: SourceKind,
	val sourceInstanceId: SourceInstanceId,
	val registrationGeneration: Long,
	val appliedRevision: Long?,
	val callbackEntryBarrierSequence: Long,
	val lastDurablyAdmittedSequence: Long?,
	val lastAdmissionOrdinal: Long?,
	val failedAdmissionCount: Long,
	val unresolvedSequenceStart: Long?,
	val unresolvedSequenceEndInclusive: Long?,
	val registrationRemovalOutcome: RegistrationRemovalOutcome,
	val providerFlushOutcome: ProviderFlushOutcome,
	val providerCoverage: ProviderCoverage,
	val appDrainComplete: Boolean,
	val status: SourceStopStatus,
	/** Exact durable session membership when the retiring registration had one capture owner. */
	val logicalTrackingId: String? = null,
	val serviceRunId: String? = null,
) {
	init {
		require((logicalTrackingId == null) == (serviceRunId == null)) {
			"Stop acknowledgement membership must be fully present or fully absent"
		}
		require(logicalTrackingId == null || logicalTrackingId.isNotBlank())
		require(serviceRunId == null || serviceRunId.isNotBlank())
	}
}

enum class RegistrationRemovalOutcome { REMOVED, NOT_REGISTERED, FAILED, UNOBSERVABLE }
enum class ProviderFlushOutcome { COMPLETE, NOT_SUPPORTED, FAILED, TIMED_OUT, NOT_REQUESTED }

enum class ProviderCoverage {
	CALLBACKS_ENTERED_BEFORE_BARRIER,
	PROVIDER_COMPLETENESS_UNOBSERVABLE,
}

enum class SourceStopStatus { COMPLETE, TIMED_OUT, PERMISSION_LOST, PROVIDER_FAILED, PROCESS_RESTARTED }
