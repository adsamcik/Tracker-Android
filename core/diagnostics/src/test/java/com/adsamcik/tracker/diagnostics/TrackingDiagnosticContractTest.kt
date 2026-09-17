package com.adsamcik.tracker.diagnostics

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

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
			coarseLocalTimestamp = TrackingDiagnosticCoarseLocalTimestamp.fromEpochMilliseconds(
				epochMilliseconds = 0L,
				zoneId = ZoneOffset.UTC,
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
				pipelineStage = TrackingDiagnosticPipelineStage.PERSISTENCE,
				operation = TrackingDiagnosticOperation.WRITE,
				result = TrackingDiagnosticResult.SUCCEEDED,
				reason = TrackingDiagnosticFailureReason.STORAGE_UNAVAILABLE,
				lifecycle = TrackingDiagnosticEventLifecycle.TERMINAL,
			)
		}
	}

	@Test
	fun `fixed no-op factory is explicit and terminal events invalidate their scope`() {
		val recorder = TrackingDiagnosticRecorder.noOpForTest()
		val scope = recorder.beginOperation(
			TrackingDiagnosticSource.LOCATION,
			TrackingDiagnosticPurpose.SESSION_CAPTURE,
			TrackingDiagnosticOperation.START,
		)
		val terminal = unmetered(lifecycle = TrackingDiagnosticEventLifecycle.TERMINAL)

		recorder.record(scope, terminal) shouldBe
			TrackingDiagnosticRecordResult.IgnoredByNoOpRecorder
		recorder.record(scope, terminal) shouldBe
			TrackingDiagnosticRecordResult.Rejected(
				TrackingDiagnosticScopeRejectionReason.ALREADY_TERMINATED,
			)
		scope.toString() shouldBe "TrackingDiagnosticOperationScope(opaque)"
	}

	@Test
	fun `scope rejects recorder source purpose and operation reuse`() {
		val recorder = TrackingDiagnosticRecorder.noOpForTest()
		val otherRecorder = TrackingDiagnosticRecorder.noOpForTest()
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
	fun `recorder owns opaque scope sequence and coarse timestamp`() {
		val recordedEvents = mutableListOf<RecordedTrackingDiagnosticEvent>()
		var elapsedNanos = 0L
		val recorder = TrackingDiagnosticRecorder.recordingForTest(
			recordedEvents = recordedEvents,
			nanoTime = { elapsedNanos },
			epochMilliseconds = { 1_789_630_524_522L },
			zoneId = ZoneOffset.ofHours(2),
		)
		val scope = recorder.beginOperation(
			TrackingDiagnosticSource.LOCATION,
			TrackingDiagnosticPurpose.SESSION_CAPTURE,
			TrackingDiagnosticOperation.START,
		)

		recorder.record(scope, unmetered()) shouldBe TrackingDiagnosticRecordResult.RecordedLocally
		elapsedNanos = TimeUnit.MILLISECONDS.toNanos(12L)
		recorder.record(scope, unmetered()) shouldBe TrackingDiagnosticRecordResult.RecordedLocally

		recordedEvents.map { it.scopeSequence } shouldBe listOf(
			TrackingDiagnosticScopeSequence.EVENT_01,
			TrackingDiagnosticScopeSequence.EVENT_02,
		)
		recordedEvents.map { it.operationScope.wireValue }.distinct().size shouldBe 1
		recordedEvents.first().operationScope.wireValue.matches(
			Regex("""scope_[0-9a-f]{24}"""),
		) shouldBe true
		recordedEvents.map { it.coarseLocalTimestamp.wireValue }.distinct() shouldBe
			listOf("2026-09-17T09:30+02:00")
		recordedEvents.last().scopeDurationBucket shouldBe
			TrackingDiagnosticDurationBucket.TEN_TO_NINETY_NINE_MILLISECONDS
	}

	@Test
	fun `separate scopes receive distinct process-local opaque values`() {
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
			) shouldBe TrackingDiagnosticRecordResult.RecordedLocally
		}

		recordedEvents.map { it.operationScope.wireValue }.distinct().size shouldBe 2
	}

	@Test
	fun `scope has a bounded event count`() {
		val recorder = TrackingDiagnosticRecorder.noOpForTest()
		val scope = recorder.beginOperation(
			TrackingDiagnosticSource.LOCATION,
			TrackingDiagnosticPurpose.SESSION_CAPTURE,
			TrackingDiagnosticOperation.START,
		)
		repeat(16) {
			recorder.record(scope, unmetered()) shouldBe
				TrackingDiagnosticRecordResult.IgnoredByNoOpRecorder
		}

		recorder.record(scope, unmetered()) shouldBe
			TrackingDiagnosticRecordResult.Rejected(
				TrackingDiagnosticScopeRejectionReason.EVENT_LIMIT_EXCEEDED,
			)
	}

	@Test
	fun `scope lifetime and store failures close without throwing into tracking`() {
		var nowNanos = 0L
		val expiringRecorder = TrackingDiagnosticRecorder.noOpForTest(
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
			TrackingDiagnosticRecordResult.RecorderFailed
		failingRecorder.record(failingScope, unmetered()) shouldBe
			TrackingDiagnosticRecordResult.Rejected(
				TrackingDiagnosticScopeRejectionReason.ALREADY_TERMINATED,
			)
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
