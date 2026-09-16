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

/**
 * The authoritative purpose that caused source work.
 *
 * These values mirror the source-broker purposes without depending on the persistence module.
 */
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
}

enum class TrackingDiagnosticFailureReason : TrackingDiagnosticReason {
	PROVIDER_FAILURE,
	STORAGE_UNAVAILABLE,
	TIMEOUT,
	INTERNAL_INVARIANT,
	UNKNOWN,
}

enum class TrackingDiagnosticCancellationReason : TrackingDiagnosticReason {
	LIFECYCLE_STOP,
	REQUEST_SUPERSEDED,
}

/**
 * Operation-scoped correlation by reference identity.
 *
 * The token has no value constructor, parser, serializer, or public payload. Callers must create a
 * fresh token for a short-lived operation and must never persist it or derive it from product IDs.
 */
class TrackingDiagnosticCorrelationToken private constructor() {
	override fun toString(): String = "TrackingDiagnosticCorrelationToken(opaque)"

	companion object {
		@JvmStatic
		fun create(): TrackingDiagnosticCorrelationToken = TrackingDiagnosticCorrelationToken()
	}
}

/**
 * Fixed payload-free event schema.
 *
 * There are deliberately no timestamps, coordinates, sensor values, identifiers, filenames,
 * checksums, provider payloads, attribute maps, or free-form strings. Debug and release builds use
 * this same schema; debug code has no wider payload surface.
 */
sealed interface TrackingDiagnosticEvent {
	val source: TrackingDiagnosticSource
	val purpose: TrackingDiagnosticPurpose
	val pipelineStage: TrackingDiagnosticPipelineStage
	val operation: TrackingDiagnosticOperation
	val result: TrackingDiagnosticResult
	val reason: TrackingDiagnosticReason
	val correlationToken: TrackingDiagnosticCorrelationToken?
	val countBucket: TrackingDiagnosticCountBucket
	val durationBucket: TrackingDiagnosticDurationBucket
	val backlogBucket: TrackingDiagnosticBacklogBucket
	val sizeBucket: TrackingDiagnosticSizeBucket

	companion object {
		@Suppress("LongParameterList")
		fun create(
			source: TrackingDiagnosticSource,
			purpose: TrackingDiagnosticPurpose,
			pipelineStage: TrackingDiagnosticPipelineStage,
			operation: TrackingDiagnosticOperation,
			result: TrackingDiagnosticResult,
			reason: TrackingDiagnosticReason,
			correlationToken: TrackingDiagnosticCorrelationToken? = null,
			countBucket: TrackingDiagnosticCountBucket = TrackingDiagnosticCountBucket.NOT_REPORTED,
			durationBucket: TrackingDiagnosticDurationBucket =
				TrackingDiagnosticDurationBucket.NOT_REPORTED,
			backlogBucket: TrackingDiagnosticBacklogBucket =
				TrackingDiagnosticBacklogBucket.NOT_REPORTED,
			sizeBucket: TrackingDiagnosticSizeBucket = TrackingDiagnosticSizeBucket.NOT_REPORTED,
		): TrackingDiagnosticEvent {
			require(reason.isCompatibleWith(result)) {
				"$reason is not valid for tracking diagnostic result $result"
			}
			return PayloadFreeTrackingDiagnosticEvent(
				source = source,
				purpose = purpose,
				pipelineStage = pipelineStage,
				operation = operation,
				result = result,
				reason = reason,
				correlationToken = correlationToken,
				countBucket = countBucket,
				durationBucket = durationBucket,
				backlogBucket = backlogBucket,
				sizeBucket = sizeBucket,
			)
		}
	}
}

private data class PayloadFreeTrackingDiagnosticEvent(
	override val source: TrackingDiagnosticSource,
	override val purpose: TrackingDiagnosticPurpose,
	override val pipelineStage: TrackingDiagnosticPipelineStage,
	override val operation: TrackingDiagnosticOperation,
	override val result: TrackingDiagnosticResult,
	override val reason: TrackingDiagnosticReason,
	override val correlationToken: TrackingDiagnosticCorrelationToken?,
	override val countBucket: TrackingDiagnosticCountBucket,
	override val durationBucket: TrackingDiagnosticDurationBucket,
	override val backlogBucket: TrackingDiagnosticBacklogBucket,
	override val sizeBucket: TrackingDiagnosticSizeBucket,
) : TrackingDiagnosticEvent

/**
 * Single local-only recording port.
 *
 * Implementations are owned by this module so a Tracebox adapter cannot be replaced with a feature,
 * network, upload, analytics, or observer-fanout implementation. [record] validates the event and
 * contains all recorder failures so diagnostics never change tracking behavior.
 */
abstract class TrackingDiagnosticRecorder internal constructor() {
	@Suppress("SwallowedException", "TooGenericExceptionCaught")
	fun record(event: TrackingDiagnosticEvent) {
		if (TrackingDiagnosticPrivacyValidator.validate(event) !is
			TrackingDiagnosticPrivacyValidation.Allowed
		) {
			return
		}
		try {
			recordLocally(event)
		} catch (_: Throwable) {
			Unit
		}
	}

	protected abstract fun recordLocally(event: TrackingDiagnosticEvent)

	companion object {
		@JvmField
		val NO_OP: TrackingDiagnosticRecorder = object : TrackingDiagnosticRecorder() {
			override fun recordLocally(event: TrackingDiagnosticEvent) = Unit
		}
	}
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
