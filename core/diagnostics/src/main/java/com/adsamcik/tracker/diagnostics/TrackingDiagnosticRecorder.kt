package com.adsamcik.tracker.diagnostics

import java.util.concurrent.TimeUnit

enum class TrackingDiagnosticScopeRejectionReason {
	RECORDER_MISMATCH,
	SOURCE_MISMATCH,
	PURPOSE_MISMATCH,
	OPERATION_MISMATCH,
	ALREADY_TERMINATED,
	LIFETIME_EXCEEDED,
	EVENT_LIMIT_EXCEEDED,
	INVALID_EVENT,
	PRIVACY_REJECTED,
}

sealed interface TrackingDiagnosticRecordResult {
	data object RecordedLocally : TrackingDiagnosticRecordResult
	data object IgnoredByNoOpRecorder : TrackingDiagnosticRecordResult
	data object RecorderFailed : TrackingDiagnosticRecordResult

	data class Rejected(
		val reason: TrackingDiagnosticScopeRejectionReason,
	) : TrackingDiagnosticRecordResult
}

/**
 * Final local-only recorder facade.
 *
 * Construction and sinks are private to this module. There is no upload, storage, observer, or
 * backend extension API. [NO_OP] is explicit; [LOCAL] is the module-owned Tracebox adapter.
 */
class TrackingDiagnosticRecorder private constructor(
	private val sink: Sink,
	private val nanoClock: NanoClock,
) {
	fun beginOperation(
		source: TrackingDiagnosticSource,
		purpose: TrackingDiagnosticPurpose,
		operation: TrackingDiagnosticOperation,
	): OperationScope = OperationScope(
		owner = this,
		source = source,
		purpose = purpose,
		operation = operation,
		startedAtNanos = nanoClock.read(),
	)

	@Suppress("SwallowedException", "TooGenericExceptionCaught")
	fun record(
		scope: OperationScope,
		event: TrackingDiagnosticEventRequest,
	): TrackingDiagnosticRecordResult {
		val consumption = scope.consume(owner = this, event = event, nowNanos = nanoClock.read())
		if (consumption is ScopeConsumption.Rejected) {
			return TrackingDiagnosticRecordResult.Rejected(consumption.reason)
		}
		consumption as ScopeConsumption.Accepted
		val recordedEvent = event.toRecordedEvent(
			scopeEventCountBucket =
				TrackingDiagnosticCountBucket.fromCount(consumption.eventCount.toLong()),
			scopeDurationBucket = TrackingDiagnosticDurationBucket.fromMilliseconds(
				TimeUnit.NANOSECONDS.toMillis(consumption.elapsedNanos),
			),
		)
		if (TrackingDiagnosticPrivacyValidator.validate(recordedEvent) !is
			TrackingDiagnosticPrivacyValidation.Allowed
		) {
			scope.invalidate()
			return TrackingDiagnosticRecordResult.Rejected(
				TrackingDiagnosticScopeRejectionReason.PRIVACY_REJECTED,
			)
		}
		return try {
			if (sink.record(recordedEvent)) {
				TrackingDiagnosticRecordResult.RecordedLocally
			} else {
				TrackingDiagnosticRecordResult.IgnoredByNoOpRecorder
			}
		} catch (_: Throwable) {
			scope.invalidate()
			TrackingDiagnosticRecordResult.RecorderFailed
		}
	}

	inner class OperationScope private constructor(
		private val owner: TrackingDiagnosticRecorder,
		private val source: TrackingDiagnosticSource,
		private val purpose: TrackingDiagnosticPurpose,
		private val operation: TrackingDiagnosticOperation,
		private val startedAtNanos: Long,
	) {
		private val correlationToken = Any()
		private var eventCount = 0
		private var active = true

		@Synchronized
		private fun consume(
			owner: TrackingDiagnosticRecorder,
			event: TrackingDiagnosticEventRequest,
			nowNanos: Long,
		): ScopeConsumption {
			if (this.owner !== owner) {
				return ScopeConsumption.Rejected(
					TrackingDiagnosticScopeRejectionReason.RECORDER_MISMATCH,
				)
			}
			if (!active) {
				return ScopeConsumption.Rejected(
					TrackingDiagnosticScopeRejectionReason.ALREADY_TERMINATED,
				)
			}
			val elapsedNanos = (nowNanos - startedAtNanos).coerceAtLeast(0L)
			if (elapsedNanos > MAX_SCOPE_LIFETIME_NANOS) {
				active = false
				return ScopeConsumption.Rejected(
					TrackingDiagnosticScopeRejectionReason.LIFETIME_EXCEEDED,
				)
			}
			if (event.source != source) {
				return ScopeConsumption.Rejected(
					TrackingDiagnosticScopeRejectionReason.SOURCE_MISMATCH,
				)
			}
			if (event.purpose != purpose) {
				return ScopeConsumption.Rejected(
					TrackingDiagnosticScopeRejectionReason.PURPOSE_MISMATCH,
				)
			}
			if (event.operation != operation) {
				return ScopeConsumption.Rejected(
					TrackingDiagnosticScopeRejectionReason.OPERATION_MISMATCH,
				)
			}
			if (!event.reason.isCompatibleWith(event.result)) {
				return ScopeConsumption.Rejected(
					TrackingDiagnosticScopeRejectionReason.INVALID_EVENT,
				)
			}
			if (eventCount >= MAX_EVENTS_PER_SCOPE) {
				active = false
				return ScopeConsumption.Rejected(
					TrackingDiagnosticScopeRejectionReason.EVENT_LIMIT_EXCEEDED,
				)
			}
			eventCount += 1
			if (event.lifecycle == TrackingDiagnosticEventLifecycle.TERMINAL) active = false
			return ScopeConsumption.Accepted(
				eventCount = eventCount,
				elapsedNanos = elapsedNanos,
				correlationToken = correlationToken,
			)
		}

		@Synchronized
		private fun invalidate() {
			active = false
		}

		override fun toString(): String = "TrackingDiagnosticOperationScope(opaque)"
	}

	private fun interface Sink {
		fun record(event: RecordedTrackingDiagnosticEvent): Boolean
	}

	private fun interface NanoClock {
		fun read(): Long
	}

	private sealed interface ScopeConsumption {
		data class Accepted(
			val eventCount: Int,
			val elapsedNanos: Long,
			@Suppress("unused") val correlationToken: Any,
		) : ScopeConsumption

		data class Rejected(
			val reason: TrackingDiagnosticScopeRejectionReason,
		) : ScopeConsumption
	}

	companion object {
		private const val MAX_EVENTS_PER_SCOPE = 16
		private val MAX_SCOPE_LIFETIME_NANOS = TimeUnit.MINUTES.toNanos(5L)
		private val SYSTEM_NANO_CLOCK = NanoClock(System::nanoTime)

		@JvmField
		val NO_OP = TrackingDiagnosticRecorder(
			sink = Sink { false },
			nanoClock = SYSTEM_NANO_CLOCK,
		)

		@JvmField
		val LOCAL = TrackingDiagnosticRecorder(
			sink = Sink { event ->
				TraceboxTrackingDiagnosticAdapter.record(event)
				true
			},
			nanoClock = SYSTEM_NANO_CLOCK,
		)
	}
}
