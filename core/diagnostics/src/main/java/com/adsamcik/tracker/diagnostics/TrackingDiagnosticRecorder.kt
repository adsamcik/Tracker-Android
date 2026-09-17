package com.adsamcik.tracker.diagnostics

import java.security.SecureRandom
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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

internal enum class TrackingDiagnosticStoreResult {
	RECORDED_LOCALLY,
	IGNORED,
	FAILED,
}

/**
 * Internal storage boundary for the local recorder. It is deliberately unavailable to consumers;
 * a storage implementation can receive only the already-closed, payload-free event shape.
 */
internal fun interface TrackingDiagnosticEventStore {
	fun append(event: RecordedTrackingDiagnosticEvent): TrackingDiagnosticStoreResult
}

/**
 * Final local-only recorder facade.
 *
 * Construction and storage are module-owned. There is no upload, observer, backend, or arbitrary
 * attribute extension API.
 */
class TrackingDiagnosticRecorder private constructor(
	private val store: TrackingDiagnosticEventStore,
	private val nanoClock: NanoClock,
	private val wallClock: WallClock,
	private val zoneProvider: ZoneProvider,
	private val scopeOpaqueFactory: ScopeOpaqueFactory,
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
		operationScope = scopeOpaqueFactory.create(),
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
			operationScope = consumption.operationScope,
			scopeSequence = TrackingDiagnosticScopeSequence.fromEventCount(consumption.eventCount),
			coarseLocalTimestamp = TrackingDiagnosticCoarseLocalTimestamp.fromEpochMilliseconds(
				epochMilliseconds = wallClock.readEpochMilliseconds(),
				zoneId = zoneProvider.currentZone(),
			),
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
			when (store.append(recordedEvent)) {
				TrackingDiagnosticStoreResult.RECORDED_LOCALLY ->
					TrackingDiagnosticRecordResult.RecordedLocally
				TrackingDiagnosticStoreResult.IGNORED ->
					TrackingDiagnosticRecordResult.IgnoredByNoOpRecorder
				TrackingDiagnosticStoreResult.FAILED -> {
					scope.invalidate()
					TrackingDiagnosticRecordResult.RecorderFailed
				}
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
		private val operationScope: TrackingDiagnosticScopeOpaque,
	) {
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
				operationScope = operationScope,
			)
		}

		@Synchronized
		private fun invalidate() {
			active = false
		}

		override fun toString(): String = "TrackingDiagnosticOperationScope(opaque)"
	}

	private fun interface NanoClock {
		fun read(): Long
	}

	private fun interface WallClock {
		fun readEpochMilliseconds(): Long
	}

	private fun interface ZoneProvider {
		fun currentZone(): ZoneId
	}

	private fun interface ScopeOpaqueFactory {
		fun create(): TrackingDiagnosticScopeOpaque
	}

	private sealed interface ScopeConsumption {
		data class Accepted(
			val eventCount: Int,
			val elapsedNanos: Long,
			val operationScope: TrackingDiagnosticScopeOpaque,
		) : ScopeConsumption

		data class Rejected(
			val reason: TrackingDiagnosticScopeRejectionReason,
		) : ScopeConsumption
	}

	companion object {
		private const val MAX_EVENTS_PER_SCOPE = 16
		private val MAX_SCOPE_LIFETIME_NANOS = TimeUnit.MINUTES.toNanos(5L)

		@JvmSynthetic
		internal fun local(): TrackingDiagnosticRecorder {
			val random = SecureRandom()
			return TrackingDiagnosticRecorder(
				store = TraceboxTrackingDiagnosticAdapter.PRODUCTION,
				nanoClock = NanoClock(System::nanoTime),
				wallClock = WallClock(System::currentTimeMillis),
				zoneProvider = ZoneProvider(ZoneId::systemDefault),
				scopeOpaqueFactory = ScopeOpaqueFactory {
					TrackingDiagnosticScopeOpaque.random(random)
				},
			)
		}

		@JvmSynthetic
		internal fun noOpForTest(
			nanoTime: () -> Long = { 0L },
			epochMilliseconds: () -> Long = { 0L },
			zoneId: ZoneId = ZoneOffset.UTC,
		): TrackingDiagnosticRecorder = createTestRecorder(
			store = TrackingDiagnosticEventStore { TrackingDiagnosticStoreResult.IGNORED },
			nanoTime = nanoTime,
			epochMilliseconds = epochMilliseconds,
			zoneId = zoneId,
		)

		@JvmSynthetic
		internal fun recordingForTest(
			recordedEvents: MutableList<RecordedTrackingDiagnosticEvent>,
			nanoTime: () -> Long = { 0L },
			epochMilliseconds: () -> Long = { 0L },
			zoneId: ZoneId = ZoneOffset.UTC,
			failWrites: Boolean = false,
		): TrackingDiagnosticRecorder = createTestRecorder(
			store = TrackingDiagnosticEventStore { event ->
				if (failWrites) {
					TrackingDiagnosticStoreResult.FAILED
				} else {
					recordedEvents += event
					TrackingDiagnosticStoreResult.RECORDED_LOCALLY
				}
			},
			nanoTime = nanoTime,
			epochMilliseconds = epochMilliseconds,
			zoneId = zoneId,
		)

		private fun createTestRecorder(
			store: TrackingDiagnosticEventStore,
			nanoTime: () -> Long,
			epochMilliseconds: () -> Long,
			zoneId: ZoneId,
		): TrackingDiagnosticRecorder {
			val scopeSequence = AtomicLong()
			return TrackingDiagnosticRecorder(
				store = store,
				nanoClock = NanoClock(nanoTime),
				wallClock = WallClock(epochMilliseconds),
				zoneProvider = ZoneProvider { zoneId },
				scopeOpaqueFactory = ScopeOpaqueFactory {
					TrackingDiagnosticScopeOpaque.fixedForTest(scopeSequence.incrementAndGet())
				},
			)
		}
	}
}
