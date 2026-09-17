package com.adsamcik.tracker.diagnostics

import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

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
	data class Storage(
		val result: TrackingDiagnosticStorageResult,
	) : TrackingDiagnosticRecordResult

	data class Rejected(
		val reason: TrackingDiagnosticScopeRejectionReason,
	) : TrackingDiagnosticRecordResult
}

/**
 * Internal storage boundary for the local recorder. It is deliberately unavailable to consumers;
 * a storage implementation receives only the encoded payload-free shape, never recorder scope
 * correlation.
 */
internal fun interface TrackingDiagnosticEventStore {
	suspend fun append(event: EncodedTrackingDiagnosticEvent): TrackingDiagnosticStorageResult
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
	private val scopeOpaqueFactory: ScopeOpaqueFactory,
	private val recordedEventProbe: (RecordedTrackingDiagnosticEvent) -> Unit,
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
	suspend fun record(
		scope: OperationScope,
		event: TrackingDiagnosticEventRequest,
	): TrackingDiagnosticRecordResult {
		val consumption = scope.consume(owner = this, event = event, nowNanos = nanoClock.read())
		if (consumption is ScopeConsumption.Rejected) {
			return TrackingDiagnosticRecordResult.Rejected(consumption.reason)
		}
		consumption as ScopeConsumption.Accepted
		return try {
			val recordedEvent = event.toRecordedEvent(
				operationScope = consumption.operationScope,
				scopeSequence =
					TrackingDiagnosticScopeSequence.fromEventCount(consumption.eventCount),
				coarseTimeBucket =
					TrackingDiagnosticCoarseTimeBucket.fromEpochMilliseconds(
						epochMilliseconds = wallClock.readEpochMilliseconds(),
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
			recordedEventProbe(recordedEvent)
			val storageResult = store.append(EncodedTrackingDiagnosticEvent.from(recordedEvent))
			when (storageResult) {
				TrackingDiagnosticStorageResult.STORAGE_RETRYABLE,
				TrackingDiagnosticStorageResult.PERMANENT_REJECTED,
				-> {
					scope.invalidate()
					TrackingDiagnosticRecordResult.Storage(storageResult)
				}
				TrackingDiagnosticStorageResult.STORED,
				TrackingDiagnosticStorageResult.AGGREGATED,
				TrackingDiagnosticStorageResult.DROPPED_RATE_LIMIT,
				-> TrackingDiagnosticRecordResult.Storage(storageResult)
			}
		} catch (cancelled: CancellationException) {
			scope.invalidate()
			throw cancelled
		} catch (_: Throwable) {
			scope.invalidate()
			TrackingDiagnosticRecordResult.Storage(
				TrackingDiagnosticStorageResult.STORAGE_RETRYABLE,
			)
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
		internal fun local(store: TrackingDiagnosticEventStore): TrackingDiagnosticRecorder {
			val random = SecureRandom()
			val processEpoch = TrackingDiagnosticProcessEpoch.random(random)
			return TrackingDiagnosticRecorder(
				store = store,
				nanoClock = NanoClock(System::nanoTime),
				wallClock = WallClock(System::currentTimeMillis),
				scopeOpaqueFactory = ScopeOpaqueFactory {
					TrackingDiagnosticScopeOpaque.random(processEpoch, random)
				},
				recordedEventProbe = {},
			)
		}

		@JvmSynthetic
		internal fun droppingForTest(
			nanoTime: () -> Long = { 0L },
			epochMilliseconds: () -> Long = { 0L },
		): TrackingDiagnosticRecorder = createTestRecorder(
			store = TrackingDiagnosticEventStore {
				TrackingDiagnosticStorageResult.DROPPED_RATE_LIMIT
			},
			nanoTime = nanoTime,
			epochMilliseconds = epochMilliseconds,
			recordedEventProbe = {},
		)

		@JvmSynthetic
		internal fun recordingForTest(
			recordedEvents: MutableList<RecordedTrackingDiagnosticEvent>,
			nanoTime: () -> Long = { 0L },
			epochMilliseconds: () -> Long = { 0L },
			failWrites: Boolean = false,
			throwWrites: Boolean = false,
			cancelWrites: Boolean = false,
			processEpochSeed: Long = 0L,
		): TrackingDiagnosticRecorder = createTestRecorder(
			store = TrackingDiagnosticEventStore {
				if (cancelWrites) {
					throw CancellationException("Test storage cancellation")
				} else if (throwWrites) {
					error("Test storage failure")
				} else if (failWrites) {
					TrackingDiagnosticStorageResult.STORAGE_RETRYABLE
				} else {
					TrackingDiagnosticStorageResult.STORED
				}
			},
			nanoTime = nanoTime,
			epochMilliseconds = epochMilliseconds,
			processEpochSeed = processEpochSeed,
			recordedEventProbe = { event -> recordedEvents += event },
		)

		private fun createTestRecorder(
			store: TrackingDiagnosticEventStore,
			nanoTime: () -> Long,
			epochMilliseconds: () -> Long,
			recordedEventProbe: (RecordedTrackingDiagnosticEvent) -> Unit,
			processEpochSeed: Long = 0L,
		): TrackingDiagnosticRecorder {
			val scopeSequence = AtomicLong()
			return TrackingDiagnosticRecorder(
				store = store,
				nanoClock = NanoClock(nanoTime),
				wallClock = WallClock(epochMilliseconds),
				scopeOpaqueFactory = ScopeOpaqueFactory {
					TrackingDiagnosticScopeOpaque.fixedForTest(
						scopeSeed = scopeSequence.incrementAndGet(),
						processSeed = processEpochSeed,
					)
				},
				recordedEventProbe = recordedEventProbe,
			)
		}
	}
}
