package com.adsamcik.tracker.diagnostics

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
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
			scopeEventCountBucket = TrackingDiagnosticCountBucket.ONE,
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
							TrackingDiagnosticOperation.WRITE ->
							setOf(TrackingDiagnosticMetric.PERSISTED_ENVELOPE_COUNT)
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
	fun `no-op recorder is explicit and terminal events invalidate their scope`() {
		val scope = TrackingDiagnosticRecorder.NO_OP.beginOperation(
			TrackingDiagnosticSource.LOCATION,
			TrackingDiagnosticPurpose.SESSION_CAPTURE,
			TrackingDiagnosticOperation.START,
		)
		val terminal = unmetered(lifecycle = TrackingDiagnosticEventLifecycle.TERMINAL)

		TrackingDiagnosticRecorder.NO_OP.record(scope, terminal) shouldBe
			TrackingDiagnosticRecordResult.IgnoredByNoOpRecorder
		TrackingDiagnosticRecorder.NO_OP.record(scope, terminal) shouldBe
			TrackingDiagnosticRecordResult.Rejected(
				TrackingDiagnosticScopeRejectionReason.ALREADY_TERMINATED,
			)
		scope.toString() shouldBe "TrackingDiagnosticOperationScope(opaque)"
	}

	@Test
	fun `scope rejects recorder source purpose and operation reuse`() {
		val scope = TrackingDiagnosticRecorder.NO_OP.beginOperation(
			TrackingDiagnosticSource.LOCATION,
			TrackingDiagnosticPurpose.SESSION_CAPTURE,
			TrackingDiagnosticOperation.START,
		)

		TrackingDiagnosticRecorder.LOCAL.record(scope, unmetered()) shouldBe
			TrackingDiagnosticRecordResult.Rejected(
				TrackingDiagnosticScopeRejectionReason.RECORDER_MISMATCH,
			)
		TrackingDiagnosticRecorder.NO_OP.record(
			scope,
			unmetered(source = TrackingDiagnosticSource.WIFI),
		) shouldBe TrackingDiagnosticRecordResult.Rejected(
			TrackingDiagnosticScopeRejectionReason.SOURCE_MISMATCH,
		)
		TrackingDiagnosticRecorder.NO_OP.record(
			scope,
			unmetered(purpose = TrackingDiagnosticPurpose.AMBIENT_PRODUCT),
		) shouldBe TrackingDiagnosticRecordResult.Rejected(
			TrackingDiagnosticScopeRejectionReason.PURPOSE_MISMATCH,
		)
		TrackingDiagnosticRecorder.NO_OP.record(
			scope,
			unmetered(operation = TrackingDiagnosticOperation.STOP),
		) shouldBe TrackingDiagnosticRecordResult.Rejected(
			TrackingDiagnosticScopeRejectionReason.OPERATION_MISMATCH,
		)
	}

	@Test
	fun `scope has a bounded event count`() {
		val scope = TrackingDiagnosticRecorder.NO_OP.beginOperation(
			TrackingDiagnosticSource.LOCATION,
			TrackingDiagnosticPurpose.SESSION_CAPTURE,
			TrackingDiagnosticOperation.START,
		)
		repeat(16) {
			TrackingDiagnosticRecorder.NO_OP.record(scope, unmetered()) shouldBe
				TrackingDiagnosticRecordResult.IgnoredByNoOpRecorder
		}

		TrackingDiagnosticRecorder.NO_OP.record(scope, unmetered()) shouldBe
			TrackingDiagnosticRecordResult.Rejected(
				TrackingDiagnosticScopeRejectionReason.EVENT_LIMIT_EXCEEDED,
			)
	}

	@Test
	fun `scope lifetime and sink failures close without throwing into tracking`() {
		var nowNanos = 0L
		val expiringRecorder = reflectiveRecorder(
			sink = { true },
			nanoClock = { nowNanos },
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

		val failingRecorder = reflectiveRecorder(
			sink = { error("local adapter unavailable") },
			nanoClock = { 0L },
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

	private fun reflectiveRecorder(
		sink: () -> Boolean,
		nanoClock: () -> Long,
	): TrackingDiagnosticRecorder {
		val recorderClass = TrackingDiagnosticRecorder::class.java
		val sinkClass = recorderClass.declaredClasses.single { type -> type.simpleName == "Sink" }
		val clockClass = recorderClass.declaredClasses.single { type -> type.simpleName == "NanoClock" }
		val sinkProxy = Proxy.newProxyInstance(
			sinkClass.classLoader,
			arrayOf(sinkClass),
		) { _, method, _ ->
			when (method.name) {
				"record" -> sink()
				else -> error("Unexpected sink method ${method.name}")
			}
		}
		val clockProxy = Proxy.newProxyInstance(
			clockClass.classLoader,
			arrayOf(clockClass),
		) { _, method, _ ->
			when (method.name) {
				"read" -> nanoClock()
				else -> error("Unexpected clock method ${method.name}")
			}
		}
		return recorderClass.getDeclaredConstructor(sinkClass, clockClass).run {
			isAccessible = true
			newInstance(sinkProxy, clockProxy) as TrackingDiagnosticRecorder
		}
	}
}
