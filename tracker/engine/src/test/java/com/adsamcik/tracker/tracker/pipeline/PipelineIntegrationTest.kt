package com.adsamcik.tracker.tracker.pipeline

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.processor.ProcessorDescriptor
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.ActivityConfidence
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Integration test: signal → processor.onSignal → processor.onFlush → DomainEvent → callback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineIntegrationTest {

	// region helpers

	private fun testSignal(timestampMs: Long = 1_000L) = TrackingSignal(
		timestampMs = EpochMs(timestampMs),
		activity = ActivitySignal(
			type = DetectedActivityType.WALKING,
			confidence = ActivityConfidence(80),
		),
	)

	/**
	 * A processor that accumulates signal count and emits a [DomainEvent.DailySummaryUpdated]
	 * on flush with the accumulated count encoded as totalSteps.
	 */
	private class EventEmittingProcessor(
		override val descriptor: ProcessorDescriptor,
	) : SignalProcessor {
		var signalCount = 0

		override suspend fun onStart(context: ProcessorContext) {}

		override fun onSignal(signal: TrackingSignal) {
			signalCount++
		}

		override suspend fun onFlush(): List<DomainEvent> {
			if (signalCount == 0) return emptyList()
			val count = signalCount
			signalCount = 0
			return listOf(
				DomainEvent.DailySummaryUpdated(
					timestampMs = EpochMs(System.currentTimeMillis()),
					processorId = descriptor.id,
					dayEpoch = 0L,
					totalDistance = DistanceM.coerced(0f),
					totalSteps = StepCount(count),
					totalDuration = DurationMs(0L),
					tripCount = 0,
				),
			)
		}

		override suspend fun onStop(): List<DomainEvent> = onFlush()
	}

	// endregion

	@Test
	fun `signal to domain event round-trip through pipeline`() = runTest {
		val collectedEvents = mutableListOf<DomainEvent>()
		val processor = EventEmittingProcessor(
			ProcessorDescriptor(
				id = "event-emitter",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 100L,
				priority = 0,
			),
		)
		val pipeline = ProcessorPipeline(
			processors = setOf(processor),
			onDomainEvents = { events -> collectedEvents.addAll(events) },
		)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))

		// Feed 3 signals — the 3rd triggers flush (200ms > 100ms interval)
		pipeline.onSignal(testSignal(30L))
		pipeline.onSignal(testSignal(60L))
		pipeline.onSignal(testSignal(200L))

		collectedEvents shouldHaveSize 1
		val event = collectedEvents.first()
		event.shouldBeInstanceOf<DomainEvent.DailySummaryUpdated>()
		event.totalSteps.raw shouldBe 3
		event.processorId shouldBe "event-emitter"
	}

	@Test
	fun `stop delivers final domain events from processor`() = runTest {
		val collectedEvents = mutableListOf<DomainEvent>()
		val processor = EventEmittingProcessor(
			ProcessorDescriptor(
				id = "stop-emitter",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 60_000L, // long interval — no flush before stop
				priority = 0,
			),
		)
		val pipeline = ProcessorPipeline(
			processors = setOf(processor),
			onDomainEvents = { events -> collectedEvents.addAll(events) },
		)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))
		pipeline.onSignal(testSignal(1000L))
		pipeline.onSignal(testSignal(2000L))
		pipeline.stop()

		collectedEvents shouldHaveSize 1
		val event = collectedEvents.first()
		event.shouldBeInstanceOf<DomainEvent.DailySummaryUpdated>()
		event.totalSteps.raw shouldBe 2
	}
}
