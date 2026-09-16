package com.adsamcik.tracker.diagnostics

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrackingDiagnosticContractTest {
	@Test
	fun `event construction retains only fixed enums buckets and operation correlation`() {
		val correlation = TrackingDiagnosticCorrelationToken.create()

		val event = TrackingDiagnosticEvent.create(
			source = TrackingDiagnosticSource.WIFI,
			purpose = TrackingDiagnosticPurpose.AMBIENT_PRODUCT,
			pipelineStage = TrackingDiagnosticPipelineStage.DURABLE_INGRESS,
			operation = TrackingDiagnosticOperation.ENQUEUE,
			result = TrackingDiagnosticResult.DEFERRED,
			reason = TrackingDiagnosticDeferredReason.BACKLOG_LIMIT,
			correlationToken = correlation,
			countBucket = TrackingDiagnosticCountBucket.TWO_TO_FOUR,
			durationBucket = TrackingDiagnosticDurationBucket.TEN_TO_NINETY_NINE_MILLISECONDS,
			backlogBucket = TrackingDiagnosticBacklogBucket.NINE_TO_THIRTY_TWO,
			sizeBucket = TrackingDiagnosticSizeBucket.UP_TO_FOUR_KIBIBYTES,
		)

		event.source shouldBe TrackingDiagnosticSource.WIFI
		event.purpose shouldBe TrackingDiagnosticPurpose.AMBIENT_PRODUCT
		event.pipelineStage shouldBe TrackingDiagnosticPipelineStage.DURABLE_INGRESS
		event.operation shouldBe TrackingDiagnosticOperation.ENQUEUE
		event.result shouldBe TrackingDiagnosticResult.DEFERRED
		event.reason shouldBe TrackingDiagnosticDeferredReason.BACKLOG_LIMIT
		(event.correlationToken === correlation) shouldBe true
		event.countBucket shouldBe TrackingDiagnosticCountBucket.TWO_TO_FOUR
		event.durationBucket shouldBe
			TrackingDiagnosticDurationBucket.TEN_TO_NINETY_NINE_MILLISECONDS
		event.backlogBucket shouldBe TrackingDiagnosticBacklogBucket.NINE_TO_THIRTY_TWO
		event.sizeBucket shouldBe TrackingDiagnosticSizeBucket.UP_TO_FOUR_KIBIBYTES
	}

	@Test
	fun `result rejects a reason from another typed reason family`() {
		shouldThrow<IllegalArgumentException> {
			TrackingDiagnosticEvent.create(
				source = TrackingDiagnosticSource.STEPS,
				purpose = TrackingDiagnosticPurpose.SESSION_CAPTURE,
				pipelineStage = TrackingDiagnosticPipelineStage.PERSISTENCE,
				operation = TrackingDiagnosticOperation.WRITE,
				result = TrackingDiagnosticResult.SUCCEEDED,
				reason = TrackingDiagnosticFailureReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	@Test
	fun `correlation tokens are opaque input-free and non-stable`() {
		val first = TrackingDiagnosticCorrelationToken.create()
		val second = TrackingDiagnosticCorrelationToken.create()

		(first === second) shouldBe false
		first.toString() shouldBe "TrackingDiagnosticCorrelationToken(opaque)"
		TrackingDiagnosticCorrelationToken::class.java.constructors.toList().shouldBeEmpty()
		TrackingDiagnosticCorrelationToken.Companion::class.java.declaredMethods
			.single { method -> method.name == "create" }
			.parameterCount shouldBe 0
		TrackingDiagnosticCorrelationToken::class.java.declaredFields
			.filterNot { field -> java.lang.reflect.Modifier.isStatic(field.modifiers) }
			.shouldBeEmpty()
	}

	@Test
	fun `raw measurements collapse into bounded buckets`() {
		TrackingDiagnosticCountBucket.fromCount(64L) shouldBe
			TrackingDiagnosticCountBucket.SEVENTEEN_TO_SIXTY_FOUR
		TrackingDiagnosticCountBucket.fromCount(Long.MAX_VALUE) shouldBe
			TrackingDiagnosticCountBucket.SIXTY_FIVE_OR_MORE
		TrackingDiagnosticDurationBucket.fromMilliseconds(30_000L) shouldBe
			TrackingDiagnosticDurationBucket.THIRTY_SECONDS_OR_MORE
		TrackingDiagnosticBacklogBucket.fromItemCount(129L) shouldBe
			TrackingDiagnosticBacklogBucket.ONE_HUNDRED_TWENTY_NINE_OR_MORE
		TrackingDiagnosticSizeBucket.fromBytes(65_537L) shouldBe
			TrackingDiagnosticSizeBucket.OVER_SIXTY_FOUR_KIBIBYTES
		shouldThrow<IllegalArgumentException> {
			TrackingDiagnosticCountBucket.fromCount(-1L)
		}
	}

	@Test
	fun `recorder defaults to no-op and contains backend failure`() {
		val event = successfulEvent()
		var attempted = 0
		val failingRecorder = object : TrackingDiagnosticRecorder() {
			override fun recordLocally(event: TrackingDiagnosticEvent) {
				attempted += 1
				error("local backend unavailable")
			}
		}

		TrackingDiagnosticRecorder.NO_OP.record(event)
		failingRecorder.record(event)

		attempted shouldBe 1
	}

	private fun successfulEvent(): TrackingDiagnosticEvent =
		TrackingDiagnosticEvent.create(
			source = TrackingDiagnosticSource.LOCATION,
			purpose = TrackingDiagnosticPurpose.SESSION_CAPTURE,
			pipelineStage = TrackingDiagnosticPipelineStage.QUALIFICATION,
			operation = TrackingDiagnosticOperation.VALIDATE,
			result = TrackingDiagnosticResult.SUCCEEDED,
			reason = TrackingDiagnosticSuccessReason.COMPLETED,
		)
}
