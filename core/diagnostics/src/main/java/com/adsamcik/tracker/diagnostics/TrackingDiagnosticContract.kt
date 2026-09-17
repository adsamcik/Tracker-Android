package com.adsamcik.tracker.diagnostics

/** The six independently useful tracking sources. */
enum class TrackingDiagnosticSource {
	LOCATION,
	WIFI,
	CELL,
	ACTIVITY,
	STEPS,
	PRESSURE,
}

/** The authoritative purpose that caused source work. */
enum class TrackingDiagnosticPurpose {
	SESSION_CAPTURE,
	CONTROL_AUTOSTART,
	CONTROL_CONTINUATION,
	AMBIENT_PRODUCT,
}

/** A coarse source-to-product boundary, never a class or method name. */
enum class TrackingDiagnosticPipelineStage {
	POLICY,
	LIFECYCLE,
	ACQUISITION,
	QUALIFICATION,
	ADMISSION,
	DURABLE_INGRESS,
	PERSISTENCE,
	PROJECTION,
	PRODUCT_QUERY,
	MAINTENANCE,
}

/** A fixed operation vocabulary for local source diagnostics. */
enum class TrackingDiagnosticOperation {
	START,
	STOP,
	REGISTER,
	UNREGISTER,
	RECEIVE,
	VALIDATE,
	ENQUEUE,
	DRAIN,
	WRITE,
	READ,
	RECONCILE,
	RECOVER,
	RETAIN,
	DELETE,
	IMPORT,
	EXPORT,
}

enum class TrackingDiagnosticResult {
	SUCCEEDED,
	NO_EFFECT,
	BLOCKED,
	DEFERRED,
	REJECTED,
	RETRYABLE_FAILURE,
	PERMANENT_FAILURE,
	CANCELLED,
}

/** Closed reason hierarchy; adapters cannot attach free-form reason text. */
sealed interface TrackingDiagnosticReason

enum class TrackingDiagnosticSuccessReason : TrackingDiagnosticReason {
	COMPLETED,
}

enum class TrackingDiagnosticNoEffectReason : TrackingDiagnosticReason {
	EXACT_REPLAY,
	EMPTY_DELIVERY,
	ALREADY_APPLIED,
}

enum class TrackingDiagnosticBlockedReason : TrackingDiagnosticReason {
	POLICY_DISABLED,
	CONSENT_REQUIRED,
	PERMISSION_REQUIRED,
	PLATFORM_UNAVAILABLE,
	PROVIDER_UNAVAILABLE,
	ROLLOUT_CONTAINED,
	DELETION_FENCE_ACTIVE,
	ACTIVE_OWNER,
}

enum class TrackingDiagnosticDeferredReason : TrackingDiagnosticReason {
	BACKLOG_LIMIT,
	DEPENDENCY_NOT_READY,
	STORAGE_BUSY,
	RETRY_SCHEDULED,
	LIFECYCLE_TRANSITION,
}

enum class TrackingDiagnosticRejectedReason : TrackingDiagnosticReason {
	STALE_GENERATION,
	STALE_CALLBACK,
	WRONG_BOOT,
	WRONG_PURPOSE,
	OWNER_MISMATCH,
	MALFORMED_DELIVERY,
	OVERSIZED_DELIVERY,
	INTEGRITY_MISMATCH,
	OUT_OF_ORDER,
	START_NOT_AUTHORIZED,
}

enum class TrackingDiagnosticFailureReason : TrackingDiagnosticReason {
	PROVIDER_FAILURE,
	STORAGE_UNAVAILABLE,
	TIMEOUT,
	SCHEDULING_FAILURE,
	DECODING_FAILURE,
	POLICY_READ_FAILURE,
	INITIALIZATION_FAILURE,
	RECOVERY_FAILURE,
	PROCESSING_FAILURE,
	PERMISSION_RECONCILIATION_FAILURE,
	INTERNAL_INVARIANT,
	UNKNOWN,
}

enum class TrackingDiagnosticCancellationReason : TrackingDiagnosticReason {
	LIFECYCLE_STOP,
	REQUEST_SUPERSEDED,
}

enum class TrackingDiagnosticEventLifecycle {
	PROGRESS,
	TERMINAL,
}

/** Metrics whose meaning is fixed by an operation-specific event factory. */
enum class TrackingDiagnosticMetric {
	ENCODED_ENVELOPE_SIZE,
	QUEUE_BACKLOG,
	DRAINED_ENVELOPE_COUNT,
	REMAINING_ENVELOPE_BACKLOG,
	PERSISTED_ENVELOPE_COUNT,
}

object TrackingDiagnosticMetricPolicy {
	private val approvedSources = TrackingDiagnosticSource.entries.toSet()

	fun allowedMetrics(
		source: TrackingDiagnosticSource,
		pipelineStage: TrackingDiagnosticPipelineStage,
		operation: TrackingDiagnosticOperation,
	): Set<TrackingDiagnosticMetric> {
		if (source !in approvedSources) return emptySet()
		return when (pipelineStage to operation) {
			TrackingDiagnosticPipelineStage.DURABLE_INGRESS to
				TrackingDiagnosticOperation.ENQUEUE -> setOf(
				TrackingDiagnosticMetric.ENCODED_ENVELOPE_SIZE,
				TrackingDiagnosticMetric.QUEUE_BACKLOG,
			)
			TrackingDiagnosticPipelineStage.DURABLE_INGRESS to
				TrackingDiagnosticOperation.DRAIN -> setOf(
				TrackingDiagnosticMetric.DRAINED_ENVELOPE_COUNT,
				TrackingDiagnosticMetric.REMAINING_ENVELOPE_BACKLOG,
			)
			TrackingDiagnosticPipelineStage.PERSISTENCE to TrackingDiagnosticOperation.WRITE ->
				setOf(TrackingDiagnosticMetric.PERSISTED_ENVELOPE_COUNT)
			else -> emptySet()
		}
	}
}

/**
 * Closed request consumed by [TrackingDiagnosticRecorder].
 *
 * Implementations are private to [TrackingDiagnosticEvents]. Callers cannot provide generic metric
 * maps, bucket slots, correlation values, strings, identifiers, or provider payloads.
 */
sealed interface TrackingDiagnosticEventRequest {
	val source: TrackingDiagnosticSource
	val purpose: TrackingDiagnosticPurpose
	val pipelineStage: TrackingDiagnosticPipelineStage
	val operation: TrackingDiagnosticOperation
	val result: TrackingDiagnosticResult
	val reason: TrackingDiagnosticReason
	val lifecycle: TrackingDiagnosticEventLifecycle
}

object TrackingDiagnosticEvents {
	@Suppress("LongParameterList")
	fun unmetered(
		source: TrackingDiagnosticSource,
		purpose: TrackingDiagnosticPurpose,
		pipelineStage: TrackingDiagnosticPipelineStage,
		operation: TrackingDiagnosticOperation,
		result: TrackingDiagnosticResult,
		reason: TrackingDiagnosticReason,
		lifecycle: TrackingDiagnosticEventLifecycle,
	): TrackingDiagnosticEventRequest = UnmeteredTrackingDiagnosticRequest(
		context = context(source, purpose, pipelineStage, operation, result, reason, lifecycle),
	)

	@Suppress("LongParameterList")
	fun enqueue(
		source: TrackingDiagnosticSource,
		purpose: TrackingDiagnosticPurpose,
		pipelineStage: TrackingDiagnosticPipelineStage,
		result: TrackingDiagnosticResult,
		reason: TrackingDiagnosticReason,
		lifecycle: TrackingDiagnosticEventLifecycle,
		encodedEnvelopeBytes: Long,
		queuedEnvelopeBacklog: Long,
	): TrackingDiagnosticEventRequest {
		requireApprovedMetrics(
			source = source,
			pipelineStage = pipelineStage,
			operation = TrackingDiagnosticOperation.ENQUEUE,
			expectedMetrics = setOf(
				TrackingDiagnosticMetric.ENCODED_ENVELOPE_SIZE,
				TrackingDiagnosticMetric.QUEUE_BACKLOG,
			),
		)
		return EnqueueTrackingDiagnosticRequest(
			context = context(
				source = source,
				purpose = purpose,
				pipelineStage = pipelineStage,
				operation = TrackingDiagnosticOperation.ENQUEUE,
				result = result,
				reason = reason,
				lifecycle = lifecycle,
			),
			encodedEnvelopeSizeBucket = TrackingDiagnosticSizeBucket.fromBytes(encodedEnvelopeBytes),
			queueBacklogBucket = TrackingDiagnosticBacklogBucket.fromItemCount(queuedEnvelopeBacklog),
		)
	}

	@Suppress("LongParameterList")
	fun drain(
		source: TrackingDiagnosticSource,
		purpose: TrackingDiagnosticPurpose,
		pipelineStage: TrackingDiagnosticPipelineStage,
		result: TrackingDiagnosticResult,
		reason: TrackingDiagnosticReason,
		lifecycle: TrackingDiagnosticEventLifecycle,
		drainedEnvelopeCount: Long,
		remainingEnvelopeBacklog: Long,
	): TrackingDiagnosticEventRequest {
		requireApprovedMetrics(
			source = source,
			pipelineStage = pipelineStage,
			operation = TrackingDiagnosticOperation.DRAIN,
			expectedMetrics = setOf(
				TrackingDiagnosticMetric.DRAINED_ENVELOPE_COUNT,
				TrackingDiagnosticMetric.REMAINING_ENVELOPE_BACKLOG,
			),
		)
		return DrainTrackingDiagnosticRequest(
			context = context(
				source = source,
				purpose = purpose,
				pipelineStage = pipelineStage,
				operation = TrackingDiagnosticOperation.DRAIN,
				result = result,
				reason = reason,
				lifecycle = lifecycle,
			),
			drainedEnvelopeCountBucket =
				TrackingDiagnosticCountBucket.fromCount(drainedEnvelopeCount),
			remainingEnvelopeBacklogBucket =
				TrackingDiagnosticBacklogBucket.fromItemCount(remainingEnvelopeBacklog),
		)
	}

	@Suppress("LongParameterList")
	fun writeBatch(
		source: TrackingDiagnosticSource,
		purpose: TrackingDiagnosticPurpose,
		pipelineStage: TrackingDiagnosticPipelineStage,
		result: TrackingDiagnosticResult,
		reason: TrackingDiagnosticReason,
		lifecycle: TrackingDiagnosticEventLifecycle,
		persistedEnvelopeCount: Long,
	): TrackingDiagnosticEventRequest {
		requireApprovedMetrics(
			source = source,
			pipelineStage = pipelineStage,
			operation = TrackingDiagnosticOperation.WRITE,
			expectedMetrics = setOf(TrackingDiagnosticMetric.PERSISTED_ENVELOPE_COUNT),
		)
		return WriteTrackingDiagnosticRequest(
			context = context(
				source = source,
				purpose = purpose,
				pipelineStage = pipelineStage,
				operation = TrackingDiagnosticOperation.WRITE,
				result = result,
				reason = reason,
				lifecycle = lifecycle,
			),
			persistedEnvelopeCountBucket =
				TrackingDiagnosticCountBucket.fromCount(persistedEnvelopeCount),
		)
	}

	private fun requireApprovedMetrics(
		source: TrackingDiagnosticSource,
		pipelineStage: TrackingDiagnosticPipelineStage,
		operation: TrackingDiagnosticOperation,
		expectedMetrics: Set<TrackingDiagnosticMetric>,
	) {
		require(
			TrackingDiagnosticMetricPolicy.allowedMetrics(source, pipelineStage, operation) ==
				expectedMetrics,
		) {
			"$operation metrics are not approved for $source at $pipelineStage"
		}
	}

	@Suppress("LongParameterList")
	private fun context(
		source: TrackingDiagnosticSource,
		purpose: TrackingDiagnosticPurpose,
		pipelineStage: TrackingDiagnosticPipelineStage,
		operation: TrackingDiagnosticOperation,
		result: TrackingDiagnosticResult,
		reason: TrackingDiagnosticReason,
		lifecycle: TrackingDiagnosticEventLifecycle,
	): TrackingDiagnosticEventContext {
		require(reason.isCompatibleWith(result)) {
			"$reason is not valid for tracking diagnostic result $result"
		}
		return TrackingDiagnosticEventContext(
			source = source,
			purpose = purpose,
			pipelineStage = pipelineStage,
			operation = operation,
			result = result,
			reason = reason,
			lifecycle = lifecycle,
		)
	}
}

private data class TrackingDiagnosticEventContext(
	override val source: TrackingDiagnosticSource,
	override val purpose: TrackingDiagnosticPurpose,
	override val pipelineStage: TrackingDiagnosticPipelineStage,
	override val operation: TrackingDiagnosticOperation,
	override val result: TrackingDiagnosticResult,
	override val reason: TrackingDiagnosticReason,
	override val lifecycle: TrackingDiagnosticEventLifecycle,
) : TrackingDiagnosticEventRequest

private data class UnmeteredTrackingDiagnosticRequest(
	private val context: TrackingDiagnosticEventContext,
) : TrackingDiagnosticEventRequest by context

private data class EnqueueTrackingDiagnosticRequest(
	private val context: TrackingDiagnosticEventContext,
	val encodedEnvelopeSizeBucket: TrackingDiagnosticSizeBucket,
	val queueBacklogBucket: TrackingDiagnosticBacklogBucket,
) : TrackingDiagnosticEventRequest by context

private data class DrainTrackingDiagnosticRequest(
	private val context: TrackingDiagnosticEventContext,
	val drainedEnvelopeCountBucket: TrackingDiagnosticCountBucket,
	val remainingEnvelopeBacklogBucket: TrackingDiagnosticBacklogBucket,
) : TrackingDiagnosticEventRequest by context

private data class WriteTrackingDiagnosticRequest(
	private val context: TrackingDiagnosticEventContext,
	val persistedEnvelopeCountBucket: TrackingDiagnosticCountBucket,
) : TrackingDiagnosticEventRequest by context

internal fun TrackingDiagnosticEventRequest.toRecordedEvent(
	scopeEventCountBucket: TrackingDiagnosticCountBucket,
	scopeDurationBucket: TrackingDiagnosticDurationBucket,
): RecordedTrackingDiagnosticEvent = when (this) {
	is UnmeteredTrackingDiagnosticRequest -> UnmeteredRecordedTrackingDiagnosticEvent(
		request = this,
		scopeEventCountBucket = scopeEventCountBucket,
		scopeDurationBucket = scopeDurationBucket,
	)
	is EnqueueTrackingDiagnosticRequest -> EnqueueRecordedTrackingDiagnosticEvent(
		request = this,
		scopeEventCountBucket = scopeEventCountBucket,
		scopeDurationBucket = scopeDurationBucket,
		encodedEnvelopeSizeBucket = encodedEnvelopeSizeBucket,
		queueBacklogBucket = queueBacklogBucket,
	)
	is DrainTrackingDiagnosticRequest -> DrainRecordedTrackingDiagnosticEvent(
		request = this,
		scopeEventCountBucket = scopeEventCountBucket,
		scopeDurationBucket = scopeDurationBucket,
		drainedEnvelopeCountBucket = drainedEnvelopeCountBucket,
		remainingEnvelopeBacklogBucket = remainingEnvelopeBacklogBucket,
	)
	is WriteTrackingDiagnosticRequest -> WriteRecordedTrackingDiagnosticEvent(
		request = this,
		scopeEventCountBucket = scopeEventCountBucket,
		scopeDurationBucket = scopeDurationBucket,
		persistedEnvelopeCountBucket = persistedEnvelopeCountBucket,
	)
	is TrackingDiagnosticEventContext -> error("Event context cannot be recorded directly")
}

internal sealed interface RecordedTrackingDiagnosticEvent : TrackingDiagnosticEventRequest {
	val scopeEventCountBucket: TrackingDiagnosticCountBucket
	val scopeDurationBucket: TrackingDiagnosticDurationBucket
	val metrics: Set<TrackingDiagnosticMetric>
}

internal data class UnmeteredRecordedTrackingDiagnosticEvent(
	private val request: TrackingDiagnosticEventRequest,
	override val scopeEventCountBucket: TrackingDiagnosticCountBucket,
	override val scopeDurationBucket: TrackingDiagnosticDurationBucket,
) : RecordedTrackingDiagnosticEvent, TrackingDiagnosticEventRequest by request {
	override val metrics: Set<TrackingDiagnosticMetric> = emptySet()
}

internal data class EnqueueRecordedTrackingDiagnosticEvent(
	private val request: TrackingDiagnosticEventRequest,
	override val scopeEventCountBucket: TrackingDiagnosticCountBucket,
	override val scopeDurationBucket: TrackingDiagnosticDurationBucket,
	internal val encodedEnvelopeSizeBucket: TrackingDiagnosticSizeBucket,
	internal val queueBacklogBucket: TrackingDiagnosticBacklogBucket,
) : RecordedTrackingDiagnosticEvent, TrackingDiagnosticEventRequest by request {
	override val metrics: Set<TrackingDiagnosticMetric> = setOf(
		TrackingDiagnosticMetric.ENCODED_ENVELOPE_SIZE,
		TrackingDiagnosticMetric.QUEUE_BACKLOG,
	)
}

internal data class DrainRecordedTrackingDiagnosticEvent(
	private val request: TrackingDiagnosticEventRequest,
	override val scopeEventCountBucket: TrackingDiagnosticCountBucket,
	override val scopeDurationBucket: TrackingDiagnosticDurationBucket,
	internal val drainedEnvelopeCountBucket: TrackingDiagnosticCountBucket,
	internal val remainingEnvelopeBacklogBucket: TrackingDiagnosticBacklogBucket,
) : RecordedTrackingDiagnosticEvent, TrackingDiagnosticEventRequest by request {
	override val metrics: Set<TrackingDiagnosticMetric> = setOf(
		TrackingDiagnosticMetric.DRAINED_ENVELOPE_COUNT,
		TrackingDiagnosticMetric.REMAINING_ENVELOPE_BACKLOG,
	)
}

internal data class WriteRecordedTrackingDiagnosticEvent(
	private val request: TrackingDiagnosticEventRequest,
	override val scopeEventCountBucket: TrackingDiagnosticCountBucket,
	override val scopeDurationBucket: TrackingDiagnosticDurationBucket,
	internal val persistedEnvelopeCountBucket: TrackingDiagnosticCountBucket,
) : RecordedTrackingDiagnosticEvent, TrackingDiagnosticEventRequest by request {
	override val metrics: Set<TrackingDiagnosticMetric> =
		setOf(TrackingDiagnosticMetric.PERSISTED_ENVELOPE_COUNT)
}

internal fun TrackingDiagnosticReason.isCompatibleWith(result: TrackingDiagnosticResult): Boolean =
	when (this) {
		is TrackingDiagnosticSuccessReason -> result == TrackingDiagnosticResult.SUCCEEDED
		is TrackingDiagnosticNoEffectReason -> result == TrackingDiagnosticResult.NO_EFFECT
		is TrackingDiagnosticBlockedReason -> result == TrackingDiagnosticResult.BLOCKED
		is TrackingDiagnosticDeferredReason -> result == TrackingDiagnosticResult.DEFERRED
		is TrackingDiagnosticRejectedReason -> result == TrackingDiagnosticResult.REJECTED
		is TrackingDiagnosticFailureReason -> result == TrackingDiagnosticResult.RETRYABLE_FAILURE ||
			result == TrackingDiagnosticResult.PERMANENT_FAILURE
		is TrackingDiagnosticCancellationReason -> result == TrackingDiagnosticResult.CANCELLED
	}

internal val TrackingDiagnosticReason.stableName: String
	get() = (this as Enum<*>).name
