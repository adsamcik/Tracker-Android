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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Integration test: domain event round-trip with multiple processors emitting events.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DomainEventRoundTripTest {

	// region helpers

	private fun testSignal(timestampMs: Long = 1_000L) = TrackingSignal(
		timestampMs = EpochMs(timestampMs),
		activity = ActivitySignal(
			type = DetectedActivityType.STILL,
			confidence = ActivityConfidence(80),
		),
	)

	/** Processor that emits a SessionEnded event on stop. */
	private class SessionEndProcessor(
		override val descriptor: ProcessorDescriptor,
		private val sessionId: Long,
	) : SignalProcessor {
		override suspend fun onStart(context: ProcessorContext) {}
		override fun onSignal(signal: TrackingSignal) {}
		override suspend fun onFlush(): List<DomainEvent> = emptyList()

		override suspend fun onStop(): List<DomainEvent> = listOf(
			DomainEvent.SessionEnded(
				timestampMs = EpochMs(5000L),
				processorId = descriptor.id,
				sessionId = sessionId,
				totalDistance = DistanceM.coerced(100f),
				totalSteps = StepCount(500),
				duration = DurationMs(4000L),
			),
		)

		override fun checkpoint(): ByteArray? = null
		override fun restore(state: ByteArray) {}
	}

	/** Processor that emits a SessionStarted event on flush. */
	private class SessionStartProcessor(
		override val descriptor: ProcessorDescriptor,
	) : SignalProcessor {
		private var started = false

		override suspend fun onStart(context: ProcessorContext) {
			started = true
		}

		override fun onSignal(signal: TrackingSignal) {}

		override suspend fun onFlush(): List<DomainEvent> {
			if (!started) return emptyList()
			started = false
			return listOf(
				DomainEvent.SessionStarted(
					timestampMs = EpochMs(1000L),
					processorId = descriptor.id,
					isUserInitiated = true,
					initialTier = PolicyTier.AMBIENT,
				),
			)
		}

		override suspend fun onStop(): List<DomainEvent> = emptyList()
		override fun checkpoint(): ByteArray? = null
		override fun restore(state: ByteArray) {}
	}

	// endregion

	@Test
	fun `SessionEnded event has correct type and payload`() = runTest {
		val collectedEvents = mutableListOf<DomainEvent>()
		val processor = SessionEndProcessor(
			descriptor = ProcessorDescriptor(
				id = "session-end",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 30_000L,
				priority = 0,
			),
			sessionId = 42L,
		)
		val pipeline = ProcessorPipeline(
			processors = setOf(processor),
			scope = this,
			onDomainEvents = { events -> collectedEvents.addAll(events) },
		)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))
		pipeline.onSignal(testSignal(1000L))
		pipeline.stop()

		collectedEvents shouldHaveSize 1
		val event = collectedEvents.first()
		event.shouldBeInstanceOf<DomainEvent.SessionEnded>()
		event.processorId shouldBe "session-end"
		event.sessionId shouldBe 42L
		event.totalDistance.raw shouldBe 100f
		event.totalSteps.raw shouldBe 500
		event.duration.raw shouldBe 4000L
	}

	@Test
	fun `multiple processors emit events - all delivered to callback`() = runTest {
		val collectedEvents = mutableListOf<DomainEvent>()
		val endProcessor1 = SessionEndProcessor(
			descriptor = ProcessorDescriptor(
				id = "end-1",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 30_000L,
				priority = 0,
			),
			sessionId = 1L,
		)
		val endProcessor2 = SessionEndProcessor(
			descriptor = ProcessorDescriptor(
				id = "end-2",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 30_000L,
				priority = 10,
			),
			sessionId = 2L,
		)
		val pipeline = ProcessorPipeline(
			processors = setOf(endProcessor1, endProcessor2),
			scope = this,
			onDomainEvents = { events -> collectedEvents.addAll(events) },
		)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))
		pipeline.stop()

		collectedEvents shouldHaveSize 2
		val ids = collectedEvents.map { (it as DomainEvent.SessionEnded).sessionId }.toSet()
		ids shouldBe setOf(1L, 2L)
	}

	@Test
	fun `flush events from multiple processors are all delivered`() = runTest {
		val collectedEvents = mutableListOf<DomainEvent>()
		val startProcessor = SessionStartProcessor(
			descriptor = ProcessorDescriptor(
				id = "start-emitter",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 100L,
				priority = 0,
			),
		)
		val endProcessor = SessionEndProcessor(
			descriptor = ProcessorDescriptor(
				id = "end-emitter",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 30_000L,
				priority = 10,
			),
			sessionId = 99L,
		)
		val dispatcher = UnconfinedTestDispatcher(testScheduler)
		val pipeline = ProcessorPipeline(
			processors = setOf(startProcessor, endProcessor),
			scope = CoroutineScope(dispatcher + SupervisorJob()),
			onDomainEvents = { events -> collectedEvents.addAll(events) },
		)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))
		pipeline.onSignal(testSignal(200L)) // triggers flush on startProcessor
		pipeline.stop()

		// startProcessor emits SessionStarted on flush, endProcessor emits SessionEnded on stop
		val sessionStarted = collectedEvents.filterIsInstance<DomainEvent.SessionStarted>()
		val sessionEnded = collectedEvents.filterIsInstance<DomainEvent.SessionEnded>()

		sessionStarted shouldHaveSize 1
		sessionStarted.first().processorId shouldBe "start-emitter"

		sessionEnded shouldHaveSize 1
		sessionEnded.first().sessionId shouldBe 99L
	}
}
