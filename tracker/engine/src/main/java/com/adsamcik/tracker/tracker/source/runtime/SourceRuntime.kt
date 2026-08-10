package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import kotlinx.coroutines.flow.StateFlow

interface SourceRuntime<P : SourcePlan> {
	val source: SourceKind
	val capabilities: StateFlow<SourceCapabilities>

	suspend fun start(plan: P, sink: SourceEventSink): SourceStartResult
	suspend fun reconfigure(plan: P): SourceApplyResult
	suspend fun quiesce(cutoff: SessionCutoff): SourceStopAck
	suspend fun close()
}

fun interface SourceEventSink {
	suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff
}

sealed interface SourceAdmissionHandoff {
	data class Durable(val admissionOrdinal: Long) : SourceAdmissionHandoff
	data class Duplicate(val existingAdmissionOrdinal: Long) : SourceAdmissionHandoff
	data class TerminalFailure(val code: SourceAdmissionFailureCode) : SourceAdmissionHandoff
	data class RetryableFailure(val code: SourceAdmissionFailureCode) : SourceAdmissionHandoff
}

enum class SourceAdmissionFailureCode {
	STALE_COLLECTED_DATA_EPOCH,
	BEFORE_RETENTION_BOUNDARY,
	INVALID_EVIDENCE,
	CODEC_UNSUPPORTED,
	STORAGE_UNAVAILABLE,
	STORAGE_FULL,
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
	data class Applied(val state: AppliedSourcePlan) : SourceApplyResult
	data class Degraded(val state: AppliedSourcePlan) : SourceApplyResult
	data class RolledBack(val state: AppliedSourcePlan) : SourceApplyResult
	data class Failed(val state: AppliedSourcePlan, val retryable: Boolean) : SourceApplyResult
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
)

enum class RegistrationRemovalOutcome { REMOVED, NOT_REGISTERED, FAILED, UNOBSERVABLE }
enum class ProviderFlushOutcome { COMPLETE, NOT_SUPPORTED, FAILED, TIMED_OUT, NOT_REQUESTED }

enum class ProviderCoverage {
	FIFO_COMPLETE_AT_FLUSH_CALL,
	CALLBACKS_ENTERED_BEFORE_BARRIER,
	PROVIDER_COMPLETENESS_UNOBSERVABLE,
}

enum class SourceStopStatus { COMPLETE, TIMED_OUT, PERMISSION_LOST, PROVIDER_FAILED, PROCESS_RESTARTED }

