package com.adsamcik.tracker.diagnostics

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class TrackingDiagnosticContractTest {
	@Test
	fun `operation-specific factories retain fixed context and bounded semantic metrics`() {
		val request = TrackingDiagnosticEvents.enqueue(
			source = TrackingDiagnosticSource.WIFI,
			purpose = TrackingDiagnosticPurpose.AMBIENT_PRODUCT,
			pipelineStage = TrackingDiagnosticPipelineStage.DURABLE_INGRESS,
			result = TrackingDiagnosticResult.DEFERRED,
			reason = TrackingDiagnosticDeferredReason.BACKLOG_LIMIT,
			lifecycle = TrackingDiagnosticEventLifecycle.PROGRESS,
			encodedEnvelopeBytes = 2_048L,
			queuedEnvelopeBacklog = 9L,
		)
		val recorded = request.toRecordedEvent(
			operationScope = TrackingDiagnosticScopeOpaque.fixedForTest(1L),
			scopeSequence = TrackingDiagnosticScopeSequence.EVENT_01,
			coarseTimeBucket = TrackingDiagnosticCoarseTimeBucket.fromEpochMilliseconds(
				epochMilliseconds = 0L,
			),
			scopeDurationBucket = TrackingDiagnosticDurationBucket.UNDER_TEN_MILLISECONDS,
		) as EnqueueRecordedTrackingDiagnosticEvent

		recorded.source shouldBe TrackingDiagnosticSource.WIFI
		recorded.purpose shouldBe TrackingDiagnosticPurpose.AMBIENT_PRODUCT
		recorded.pipelineStage shouldBe TrackingDiagnosticPipelineStage.DURABLE_INGRESS
		recorded.operation shouldBe TrackingDiagnosticOperation.ENQUEUE
		recorded.result shouldBe TrackingDiagnosticResult.DEFERRED
		recorded.reason shouldBe TrackingDiagnosticDeferredReason.BACKLOG_LIMIT
		recorded.encodedEnvelopeSizeBucket shouldBe
			TrackingDiagnosticSizeBucket.UP_TO_FOUR_KIBIBYTES
		recorded.queueBacklogBucket shouldBe TrackingDiagnosticBacklogBucket.NINE_TO_THIRTY_TWO
		recorded.metrics shouldBe setOf(
			TrackingDiagnosticMetric.ENCODED_ENVELOPE_SIZE,
			TrackingDiagnosticMetric.QUEUE_BACKLOG,
		)
	}

	@Test
	fun `metric validation requires the exact set for every stage and operation`() {
		TrackingDiagnosticSource.entries.forEach { source ->
			TrackingDiagnosticPipelineStage.entries.forEach { stage ->
				TrackingDiagnosticOperation.entries.forEach { operation ->
					val expected = TrackingDiagnosticMetricPolicy.allowedMetrics(
						source,
						stage,
						operation,
					)
					TrackingDiagnosticPrivacyValidator.validateMetricSet(
						source,
						stage,
						operation,
						expected,
					) shouldBe TrackingDiagnosticPrivacyValidation.Allowed

					if (expected.isEmpty()) {
						TrackingDiagnosticPrivacyValidator.validateMetricSet(
							source,
							stage,
							operation,
							setOf(TrackingDiagnosticMetric.PERSISTED_ENVELOPE_COUNT),
						) shouldBe TrackingDiagnosticPrivacyValidation.Rejected(
							TrackingDiagnosticPrivacyRejectionReason.METRIC_OPERATION_MISMATCH,
						)
					} else {
						TrackingDiagnosticPrivacyValidator.validateMetricSet(
							source,
							stage,
							operation,
							emptySet(),
						) shouldBe TrackingDiagnosticPrivacyValidation.Rejected(
							TrackingDiagnosticPrivacyRejectionReason.METRIC_OPERATION_MISMATCH,
						)
						val wrongMetric = (TrackingDiagnosticMetric.entries.toSet() - expected).first()
						TrackingDiagnosticPrivacyValidator.validateMetricSet(
							source,
							stage,
							operation,
							expected + wrongMetric,
						) shouldBe TrackingDiagnosticPrivacyValidation.Rejected(
							TrackingDiagnosticPrivacyRejectionReason.METRIC_OPERATION_MISMATCH,
						)
					}
				}
			}
		}
	}

	@Test
	fun `unmetered factory rejects every operation that requires metrics`() {
		TrackingDiagnosticSource.entries.forEach { source ->
			TrackingDiagnosticPipelineStage.entries.forEach { stage ->
				TrackingDiagnosticOperation.entries.forEach { operation ->
					val create = {
						TrackingDiagnosticEvents.unmetered(
							source = source,
							purpose = TrackingDiagnosticPurpose.SESSION_CAPTURE,
							pipelineStage = stage,
							operation = operation,
							result = TrackingDiagnosticResult.SUCCEEDED,
							reason = TrackingDiagnosticSuccessReason.COMPLETED,
							lifecycle = TrackingDiagnosticEventLifecycle.PROGRESS,
						)
					}
					if (TrackingDiagnosticMetricPolicy.allowedMetrics(
							source,
							stage,
							operation,
						).isEmpty()
					) {
						create().operation shouldBe operation
					} else {
						shouldThrow<IllegalArgumentException> { create() }
					}
				}
			}
		}
	}

	@Test
	fun `metric policy enumerates every source stage and operation`() {
		TrackingDiagnosticSource.entries.forEach { source ->
			TrackingDiagnosticPipelineStage.entries.forEach { stage ->
				TrackingDiagnosticOperation.entries.forEach { operation ->
					val expected = when (stage to operation) {
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
						TrackingDiagnosticPipelineStage.PERSISTENCE to
							TrackingDiagnosticOperation.WRITE -> setOf(
							TrackingDiagnosticMetric.PERSISTED_ENVELOPE_COUNT,
						)
						else -> emptySet()
					}

					TrackingDiagnosticMetricPolicy.allowedMetrics(
						source,
						stage,
						operation,
					) shouldBe expected
				}
			}
		}
	}

	@Test
	fun `metric factories reject unapproved stage operation combinations`() {
		shouldThrow<IllegalArgumentException> {
			TrackingDiagnosticEvents.enqueue(
				source = TrackingDiagnosticSource.STEPS,
				purpose = TrackingDiagnosticPurpose.SESSION_CAPTURE,
				pipelineStage = TrackingDiagnosticPipelineStage.PERSISTENCE,
				result = TrackingDiagnosticResult.SUCCEEDED,
				reason = TrackingDiagnosticSuccessReason.COMPLETED,
				lifecycle = TrackingDiagnosticEventLifecycle.TERMINAL,
				encodedEnvelopeBytes = 1L,
				queuedEnvelopeBacklog = 0L,
			)
		}
		shouldThrow<IllegalArgumentException> {
			TrackingDiagnosticEvents.writeBatch(
				source = TrackingDiagnosticSource.PRESSURE,
				purpose = TrackingDiagnosticPurpose.SESSION_CAPTURE,
				pipelineStage = TrackingDiagnosticPipelineStage.DURABLE_INGRESS,
				result = TrackingDiagnosticResult.SUCCEEDED,
				reason = TrackingDiagnosticSuccessReason.COMPLETED,
				lifecycle = TrackingDiagnosticEventLifecycle.TERMINAL,
				persistedEnvelopeCount = 1L,
			)
		}
	}

	@Test
	fun `result rejects a reason from another typed reason family`() {
		shouldThrow<IllegalArgumentException> {
			TrackingDiagnosticEvents.unmetered(
				source = TrackingDiagnosticSource.STEPS,
				purpose = TrackingDiagnosticPurpose.SESSION_CAPTURE,
				pipelineStage = TrackingDiagnosticPipelineStage.LIFECYCLE,
				operation = TrackingDiagnosticOperation.START,
				result = TrackingDiagnosticResult.SUCCEEDED,
				reason = TrackingDiagnosticFailureReason.STORAGE_UNAVAILABLE,
				lifecycle = TrackingDiagnosticEventLifecycle.TERMINAL,
			)
		}
	}

	@Test
	fun `fixed dropping factory is explicit and terminal events invalidate their scope`() = runTest {
		val recorder = TrackingDiagnosticRecorder.droppingForTest()
		val scope = recorder.beginOperation(
			TrackingDiagnosticSource.LOCATION,
			TrackingDiagnosticPurpose.SESSION_CAPTURE,
			TrackingDiagnosticOperation.START,
		)
		val terminal = unmetered(lifecycle = TrackingDiagnosticEventLifecycle.TERMINAL)

		recorder.record(scope, terminal) shouldBe
			TrackingDiagnosticRecordResult.Storage(
				TrackingDiagnosticStorageResult.DROPPED_RATE_LIMIT,
			)
		recorder.record(scope, terminal) shouldBe
			TrackingDiagnosticRecordResult.Rejected(
				TrackingDiagnosticScopeRejectionReason.ALREADY_TERMINATED,
			)
		scope.toString() shouldBe "TrackingDiagnosticOperationScope(opaque)"
	}

	@Test
	fun `scope rejects recorder source purpose and operation reuse`() = runTest {
		val recorder = TrackingDiagnosticRecorder.droppingForTest()
		val otherRecorder = TrackingDiagnosticRecorder.droppingForTest()
		val scope = recorder.beginOperation(
			TrackingDiagnosticSource.LOCATION,
			TrackingDiagnosticPurpose.SESSION_CAPTURE,
			TrackingDiagnosticOperation.START,
		)

		otherRecorder.record(scope, unmetered()) shouldBe
			TrackingDiagnosticRecordResult.Rejected(
				TrackingDiagnosticScopeRejectionReason.RECORDER_MISMATCH,
			)
		recorder.record(
			scope,
			unmetered(source = TrackingDiagnosticSource.WIFI),
		) shouldBe TrackingDiagnosticRecordResult.Rejected(
			TrackingDiagnosticScopeRejectionReason.SOURCE_MISMATCH,
		)
		recorder.record(
			scope,
			unmetered(purpose = TrackingDiagnosticPurpose.AMBIENT_PRODUCT),
		) shouldBe TrackingDiagnosticRecordResult.Rejected(
			TrackingDiagnosticScopeRejectionReason.PURPOSE_MISMATCH,
		)
		recorder.record(
			scope,
			unmetered(operation = TrackingDiagnosticOperation.STOP),
		) shouldBe TrackingDiagnosticRecordResult.Rejected(
			TrackingDiagnosticScopeRejectionReason.OPERATION_MISMATCH,
		)
	}

	@Test
	fun `recorder owns in-memory scope sequence and coarse time bucket`() = runTest {
		val recordedEvents = mutableListOf<RecordedTrackingDiagnosticEvent>()
		var elapsedNanos = 0L
		val recorder = TrackingDiagnosticRecorder.recordingForTest(
			recordedEvents = recordedEvents,
			nanoTime = { elapsedNanos },
			epochMilliseconds = { 1_789_630_524_522L },
		)
		val scope = recorder.beginOperation(
			TrackingDiagnosticSource.LOCATION,
			TrackingDiagnosticPurpose.SESSION_CAPTURE,
			TrackingDiagnosticOperation.START,
		)

		recorder.record(scope, unmetered()) shouldBe TrackingDiagnosticRecordResult.Storage(
			TrackingDiagnosticStorageResult.STORED,
		)
		elapsedNanos = TimeUnit.MILLISECONDS.toNanos(12L)
		recorder.record(scope, unmetered()) shouldBe TrackingDiagnosticRecordResult.Storage(
			TrackingDiagnosticStorageResult.STORED,
		)

		recordedEvents.map { it.scopeSequence } shouldBe listOf(
			TrackingDiagnosticScopeSequence.EVENT_01,
			TrackingDiagnosticScopeSequence.EVENT_02,
		)
		recordedEvents.map { it.operationScope.opaqueValue }.distinct().size shouldBe 1
		recordedEvents.first().operationScope.opaqueValue.matches(
			Regex("""epoch_[0-9a-f]{16}_scope_[0-9a-f]{8}"""),
		) shouldBe true
		recordedEvents.map { it.coarseTimeBucket.epochQuarterHour }.distinct() shouldBe
			listOf(1_789_630_524_522L / TrackingDiagnosticCoarseTimeBucket.BUCKET_MILLISECONDS)
		recordedEvents.last().scopeDurationBucket shouldBe
			TrackingDiagnosticDurationBucket.TEN_TO_NINETY_NINE_MILLISECONDS
	}

	@Test
	fun `recorder rethrows cancellation instead of mapping it to storage failure`() = runTest {
		val recorder = TrackingDiagnosticRecorder.recordingForTest(
			recordedEvents = mutableListOf(),
			cancelWrites = true,
		)
		val scope = recorder.beginOperation(
			TrackingDiagnosticSource.LOCATION,
			TrackingDiagnosticPurpose.SESSION_CAPTURE,
			TrackingDiagnosticOperation.START,
		)

		runCatching { recorder.record(scope, unmetered()) }
			.exceptionOrNull()
			.shouldBeInstanceOf<CancellationException>()
		recorder.record(scope, unmetered()) shouldBe TrackingDiagnosticRecordResult.Rejected(
			TrackingDiagnosticScopeRejectionReason.ALREADY_TERMINATED,
		)
	}

	@Test
	fun `separate scopes receive distinct process-local opaque values`() = runTest {
		val recordedEvents = mutableListOf<RecordedTrackingDiagnosticEvent>()
		val recorder = TrackingDiagnosticRecorder.recordingForTest(recordedEvents)

		listOf(
			recorder.beginOperation(
				TrackingDiagnosticSource.STEPS,
				TrackingDiagnosticPurpose.SESSION_CAPTURE,
				TrackingDiagnosticOperation.READ,
			),
			recorder.beginOperation(
				TrackingDiagnosticSource.STEPS,
				TrackingDiagnosticPurpose.SESSION_CAPTURE,
				TrackingDiagnosticOperation.READ,
			),
		).forEach { scope ->
			recorder.record(
				scope,
				unmetered(
					source = TrackingDiagnosticSource.STEPS,
					operation = TrackingDiagnosticOperation.READ,
				),
			) shouldBe TrackingDiagnosticRecordResult.Storage(
				TrackingDiagnosticStorageResult.STORED,
			)
		}

		recordedEvents.map { it.operationScope.opaqueValue }.distinct().size shouldBe 2
	}

	@Test
	fun `scope has a bounded event count`() = runTest {
		val recorder = TrackingDiagnosticRecorder.droppingForTest()
		val scope = recorder.beginOperation(
			TrackingDiagnosticSource.LOCATION,
			TrackingDiagnosticPurpose.SESSION_CAPTURE,
			TrackingDiagnosticOperation.START,
		)
		repeat(16) {
			recorder.record(scope, unmetered()) shouldBe
				TrackingDiagnosticRecordResult.Storage(
					TrackingDiagnosticStorageResult.DROPPED_RATE_LIMIT,
				)
		}

		recorder.record(scope, unmetered()) shouldBe
			TrackingDiagnosticRecordResult.Rejected(
				TrackingDiagnosticScopeRejectionReason.EVENT_LIMIT_EXCEEDED,
			)
	}

	@Test
	fun `scope lifetime and store failures close without throwing into tracking`() = runTest {
		var nowNanos = 0L
		val expiringRecorder = TrackingDiagnosticRecorder.droppingForTest(
			nanoTime = { nowNanos },
		)
		val expiredScope = expiringRecorder.beginOperation(
			TrackingDiagnosticSource.LOCATION,
			TrackingDiagnosticPurpose.SESSION_CAPTURE,
			TrackingDiagnosticOperation.START,
		)
		nowNanos = TimeUnit.MINUTES.toNanos(5L) + 1L

		expiringRecorder.record(expiredScope, unmetered()) shouldBe
			TrackingDiagnosticRecordResult.Rejected(
				TrackingDiagnosticScopeRejectionReason.LIFETIME_EXCEEDED,
			)
		expiringRecorder.record(expiredScope, unmetered()) shouldBe
			TrackingDiagnosticRecordResult.Rejected(
				TrackingDiagnosticScopeRejectionReason.ALREADY_TERMINATED,
			)

		val failingRecorder = TrackingDiagnosticRecorder.recordingForTest(
			recordedEvents = mutableListOf(),
			failWrites = true,
		)
		val failingScope = failingRecorder.beginOperation(
			TrackingDiagnosticSource.LOCATION,
			TrackingDiagnosticPurpose.SESSION_CAPTURE,
			TrackingDiagnosticOperation.START,
		)

		failingRecorder.record(failingScope, unmetered()) shouldBe
			TrackingDiagnosticRecordResult.Storage(
				TrackingDiagnosticStorageResult.STORAGE_RETRYABLE,
			)
		failingRecorder.record(failingScope, unmetered()) shouldBe
			TrackingDiagnosticRecordResult.Rejected(
				TrackingDiagnosticScopeRejectionReason.ALREADY_TERMINATED,
			)

		val throwingRecorder = TrackingDiagnosticRecorder.recordingForTest(
			recordedEvents = mutableListOf(),
			throwWrites = true,
		)
		val throwingScope = throwingRecorder.beginOperation(
			TrackingDiagnosticSource.LOCATION,
			TrackingDiagnosticPurpose.SESSION_CAPTURE,
			TrackingDiagnosticOperation.START,
		)
		throwingRecorder.record(throwingScope, unmetered()) shouldBe
			TrackingDiagnosticRecordResult.Storage(
				TrackingDiagnosticStorageResult.STORAGE_RETRYABLE,
			)

		val clockFailingRecorder = TrackingDiagnosticRecorder.recordingForTest(
			recordedEvents = mutableListOf(),
			epochMilliseconds = { error("Clock unavailable") },
		)
		val clockFailingScope = clockFailingRecorder.beginOperation(
			TrackingDiagnosticSource.LOCATION,
			TrackingDiagnosticPurpose.SESSION_CAPTURE,
			TrackingDiagnosticOperation.START,
		)
		clockFailingRecorder.record(clockFailingScope, unmetered()) shouldBe
			TrackingDiagnosticRecordResult.Storage(
				TrackingDiagnosticStorageResult.STORAGE_RETRYABLE,
			)
	}

	@Test
	fun `process restart rotates in-memory operation scope epoch`() = runTest {
		val firstProcessEvents = mutableListOf<RecordedTrackingDiagnosticEvent>()
		val secondProcessEvents = mutableListOf<RecordedTrackingDiagnosticEvent>()
		val firstRecorder = TrackingDiagnosticRecorder.recordingForTest(
			recordedEvents = firstProcessEvents,
			processEpochSeed = 11L,
		)
		val secondRecorder = TrackingDiagnosticRecorder.recordingForTest(
			recordedEvents = secondProcessEvents,
			processEpochSeed = 12L,
		)

		listOf(firstRecorder to firstProcessEvents, secondRecorder to secondProcessEvents)
			.forEach { (recorder, _) ->
				val scope = recorder.beginOperation(
					TrackingDiagnosticSource.LOCATION,
					TrackingDiagnosticPurpose.SESSION_CAPTURE,
					TrackingDiagnosticOperation.START,
				)
				recorder.record(scope, unmetered()) shouldBe
					TrackingDiagnosticRecordResult.Storage(
						TrackingDiagnosticStorageResult.STORED,
					)
			}

		val first = firstProcessEvents.single().operationScope.opaqueValue
		val second = secondProcessEvents.single().operationScope.opaqueValue
		first.substringBefore("_scope_") == second.substringBefore("_scope_") shouldBe false
		first.substringAfter("_scope_") shouldBe second.substringAfter("_scope_")
	}

	private fun unmetered(
		source: TrackingDiagnosticSource = TrackingDiagnosticSource.LOCATION,
		purpose: TrackingDiagnosticPurpose = TrackingDiagnosticPurpose.SESSION_CAPTURE,
		operation: TrackingDiagnosticOperation = TrackingDiagnosticOperation.START,
		lifecycle: TrackingDiagnosticEventLifecycle = TrackingDiagnosticEventLifecycle.PROGRESS,
	): TrackingDiagnosticEventRequest = TrackingDiagnosticEvents.unmetered(
		source = source,
		purpose = purpose,
		pipelineStage = TrackingDiagnosticPipelineStage.LIFECYCLE,
		operation = operation,
		result = TrackingDiagnosticResult.SUCCEEDED,
		reason = TrackingDiagnosticSuccessReason.COMPLETED,
		lifecycle = lifecycle,
	)
}
