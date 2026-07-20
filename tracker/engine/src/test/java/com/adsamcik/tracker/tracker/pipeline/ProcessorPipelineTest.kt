package com.adsamcik.tracker.tracker.pipeline

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.StepCount
import com.adsamcik.tracker.stats.api.processor.ProcessorDescriptor
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.ActivityConfidence
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessorPipelineTest {

	// region helpers

	private fun testSignal(timestampMs: Long = 1_000L) = TrackingSignal(
		timestampMs = EpochMs(timestampMs),
		activity = ActivitySignal(
			type = DetectedActivityType.STILL,
			confidence = ActivityConfidence(80),
		),
	)

	private fun testEvent(timestampMs: Long = 100L): DomainEvent =
		DomainEvent.SessionStarted(
			timestampMs = EpochMs(timestampMs),
			processorId = "test",
			isUserInitiated = true,
			initialTier = PolicyTier.AMBIENT,
		)

	private fun testStopEvent(timestampMs: Long = 5000L): DomainEvent =
		DomainEvent.SessionEnded(
			timestampMs = EpochMs(timestampMs),
			processorId = "test",
			sessionId = 42L,
			totalDistance = DistanceM(0f),
			totalSteps = StepCount(0),
			duration = DurationMs(0L),
		)

	/** A [SignalProcessor] fake that records every lifecycle call. */
	private open class RecordingProcessor(
		override val descriptor: ProcessorDescriptor,
	) : SignalProcessor {
		val signals = mutableListOf<TrackingSignal>()
		val startCalls = mutableListOf<ProcessorContext>()
		var stopCount = 0
		var flushCount = 0
		var flushEvents: () -> List<DomainEvent> = { emptyList() }
		var stopEvents: () -> List<DomainEvent> = { emptyList() }
		var onSignalAction: (TrackingSignal) -> Unit = {}
		var onFlushAction: suspend () -> Unit = {}
		var onStartAction: suspend () -> Unit = {}
		var onStopAction: suspend () -> Unit = {}

		override suspend fun onStart(context: ProcessorContext) {
			startCalls.add(context)
			onStartAction()
		}

		override fun onSignal(signal: TrackingSignal) {
			signals.add(signal)
			onSignalAction(signal)
		}

		override suspend fun onFlush(): List<DomainEvent> {
			flushCount++
			onFlushAction()
			return flushEvents()
		}

		override suspend fun onStop(): List<DomainEvent> {
			stopCount++
			onStopAction()
			return stopEvents()
		}

		override fun checkpoint(): ByteArray? = null
		override fun restore(state: ByteArray) {}
	}

	private class DurableRecordingProcessor(
		descriptor: ProcessorDescriptor = ProcessorDescriptor(
			id = "persistence",
			requiredTier = PolicyTier.AMBIENT,
			flushIntervalMs = 30_000L,
			priority = 0,
		),
	) : RecordingProcessor(descriptor), DurableSignalProcessor {
		var checkpointCount = 0
		var checkpointHealthy = true

		override suspend fun checkpointStagedSignals(): Boolean {
			checkpointCount++
			return checkpointHealthy
		}
	}

	private fun ambientProcessor(id: String = "ambient", priority: Int = 0) = RecordingProcessor(
		ProcessorDescriptor(
			id = id,
			requiredTier = PolicyTier.AMBIENT,
			flushIntervalMs = 30_000L,
			priority = priority,
		),
	)

	private fun activeProcessor(id: String = "active", priority: Int = 10) = RecordingProcessor(
		ProcessorDescriptor(
			id = id,
			requiredTier = PolicyTier.ACTIVE,
			flushIntervalMs = 30_000L,
			priority = priority,
		),
	)

	private fun precisionProcessor(id: String = "precision", priority: Int = 20) = RecordingProcessor(
		ProcessorDescriptor(
			id = id,
			requiredTier = PolicyTier.PRECISION,
			flushIntervalMs = 30_000L,
			priority = priority,
		),
	)

	private fun createPipeline(
		processors: Set<SignalProcessor>,
		scope: CoroutineScope,
		onDomainEvents: suspend (List<DomainEvent>) -> Unit = {},
	) = ProcessorPipeline(processors, scope, onDomainEvents)

	// endregion

	// region start / stop lifecycle

	@Test
	fun `start calls onStart on processors matching tier`() = runTest {
		val ambient = ambientProcessor()
		val active = activeProcessor()
		val pipeline = createPipeline(setOf(ambient, active), this)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(1000L))

		ambient.startCalls shouldHaveSize 1
		active.startCalls shouldHaveSize 0
	}

	@Test
	fun `start with ACTIVE tier starts both ambient and active processors`() = runTest {
		val ambient = ambientProcessor()
		val active = activeProcessor()
		val pipeline = createPipeline(setOf(ambient, active), this)

		pipeline.start(PolicyTier.ACTIVE, EpochMs(1000L))

		ambient.startCalls shouldHaveSize 1
		active.startCalls shouldHaveSize 1
	}

	@Test
	fun `failed processor start quarantines signal delivery but retains cleanup ownership`() = runTest {
		val processor = ambientProcessor().apply {
			onStartAction = { error("start failed") }
		}
		val pipeline = createPipeline(setOf(processor), this)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(1_000L))
		pipeline.onSignal(testSignal())
		pipeline.stop()

		processor.signals shouldHaveSize 0
		processor.stopCount shouldBe 1
	}

	@Test
	fun `tier changes do not restart a quarantined processor before cleanup`() = runTest {
		val processor = ambientProcessor().apply {
			onStartAction = { error("start failed") }
		}
		val pipeline = createPipeline(setOf(processor), this)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(1_000L))
		pipeline.escalate(PolicyTier.ACTIVE, EpochMs(2_000L))
		pipeline.escalate(PolicyTier.AMBIENT, EpochMs(3_000L))
		pipeline.stop()

		processor.startCalls shouldHaveSize 1
		processor.stopCount shouldBe 1
	}

	@Test
	fun `durability processor start failure aborts pipeline startup`() = runTest {
		val persistence = DurableRecordingProcessor().apply {
			onStartAction = { error("recovery failed") }
		}
		val healthy = ambientProcessor(id = "healthy", priority = 10)
		val pipeline = createPipeline(setOf(persistence, healthy), this)

		shouldThrow<IllegalStateException> {
			pipeline.start(PolicyTier.AMBIENT, EpochMs(1_000L))
		}
		pipeline.onSignal(testSignal())

		persistence.signals shouldHaveSize 0
		healthy.signals shouldHaveSize 0
		persistence.stopCount shouldBe 1
	}

	@Test
	fun `double start throws IllegalStateException`() = runTest {
		val pipeline = createPipeline(setOf(ambientProcessor()), this)
		pipeline.start(PolicyTier.AMBIENT, EpochMs(1000L))

		shouldThrow<IllegalStateException> {
			pipeline.start(PolicyTier.AMBIENT, EpochMs(2000L))
		}
	}

	@Test
	fun `stop calls onStop on all active processors`() = runTest {
		val a = ambientProcessor()
		val b = activeProcessor()
		val pipeline = createPipeline(setOf(a, b), this)

		pipeline.start(PolicyTier.ACTIVE, EpochMs(1000L))
		pipeline.stop()

		a.stopCount shouldBe 1
		b.stopCount shouldBe 1
	}

	@Test
	fun `stop failure blocks later final events and resumes from failed processor`() = runTest {
		val collectedEvents = mutableListOf<DomainEvent>()
		var persistenceHealthy = false
		val persistence = ambientProcessor(id = "persistence", priority = 0).apply {
			onStopAction = {
				if (!persistenceHealthy) error("final flush failed")
			}
		}
		val aggregator = ambientProcessor(id = "aggregator", priority = 10).apply {
			stopEvents = { listOf(testStopEvent()) }
		}
		val pipeline = createPipeline(setOf(aggregator, persistence), this) {
			collectedEvents.addAll(it)
		}
		pipeline.start(PolicyTier.AMBIENT, EpochMs(1000L))

		shouldThrow<IllegalStateException> { pipeline.stop() }

		persistence.stopCount shouldBe 1
		aggregator.stopCount shouldBe 0
		collectedEvents shouldHaveSize 0

		persistenceHealthy = true
		pipeline.stop()

		persistence.stopCount shouldBe 2
		aggregator.stopCount shouldBe 1
		collectedEvents shouldHaveSize 1
	}

	@Test
	fun `final event failure blocks restart and retries without stopping processors twice`() = runTest {
		val expectedEvent = testStopEvent()
		val processor = ambientProcessor().apply {
			stopEvents = { listOf(expectedEvent) }
		}
		var eventPersistenceHealthy = false
		val collectedEvents = mutableListOf<DomainEvent>()
		val pipeline = createPipeline(setOf(processor), this) { events ->
			if (!eventPersistenceHealthy) error("event persistence failed")
			collectedEvents.addAll(events)
		}
		pipeline.start(PolicyTier.AMBIENT, EpochMs(1000L))

		shouldThrow<IllegalStateException> { pipeline.stop() }
		shouldThrow<IllegalStateException> {
			pipeline.start(PolicyTier.AMBIENT, EpochMs(2000L))
		}

		eventPersistenceHealthy = true
		pipeline.stop()

		processor.stopCount shouldBe 1
		collectedEvents shouldContainExactly listOf(expectedEvent)
	}

	@Test
	fun `cancellation after processor stop does not repeat non-idempotent stop`() = runTest {
		val processor = ambientProcessor().apply {
			onStopAction = {
				currentCoroutineContext()[Job]?.cancel()
			}
		}
		val pipeline = createPipeline(setOf(processor), this)
		pipeline.start(PolicyTier.AMBIENT, EpochMs(1_000L))

		val cancelledStop = launch { pipeline.stop() }
		cancelledStop.join()
		pipeline.stop()

		processor.stopCount shouldBe 1
	}

	@Test
	fun `stop on idle pipeline is no-op`() = runTest {
		val pipeline = createPipeline(setOf(ambientProcessor()), this)
		pipeline.stop() // should not throw
	}

	@Test
	fun `restart after stop is allowed`() = runTest {
		val p = ambientProcessor()
		val pipeline = createPipeline(setOf(p), this)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(1000L))
		pipeline.stop()
		pipeline.start(PolicyTier.AMBIENT, EpochMs(2000L))

		p.startCalls shouldHaveSize 2
		p.stopCount shouldBe 1
	}

	// endregion

	// region signal fan-out

	@Test
	fun `onSignal fans out to all active processors in priority order`() = runTest {
		val order = mutableListOf<String>()
		val high = ambientProcessor(id = "high", priority = 0).apply {
			onSignalAction = { order.add("high") }
		}
		val low = ambientProcessor(id = "low", priority = 10).apply {
			onSignalAction = { order.add("low") }
		}
		val pipeline = createPipeline(setOf(low, high), this)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(1000L))
		pipeline.onSignal(testSignal())

		order shouldContainExactly listOf("high", "low")
	}

	@Test
	fun `onSignal skips processors above current tier`() = runTest {
		val ambient = ambientProcessor()
		val active = activeProcessor()
		val pipeline = createPipeline(setOf(ambient, active), this)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(1000L))
		pipeline.onSignal(testSignal())

		ambient.signals shouldHaveSize 1
		active.signals shouldHaveSize 0
	}

	@Test
	fun `onSignal does nothing when pipeline is not running`() = runTest {
		val p = ambientProcessor()
		val pipeline = createPipeline(setOf(p), this)

		pipeline.onSignal(testSignal()) // before start
		p.signals shouldHaveSize 0
	}

	@Test
	fun `processor signal failure is isolated`() = runTest {
		val failing = ambientProcessor(id = "failing", priority = 0).apply {
			onSignalAction = { throw RuntimeException("boom") }
		}
		val healthy = ambientProcessor(id = "healthy", priority = 10)
		val pipeline = createPipeline(setOf(failing, healthy), this)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(1000L))
		pipeline.onSignal(testSignal())

		healthy.signals shouldHaveSize 1
	}

	@Test
	fun `raw evidence is checkpointed only by the durability processor`() = runTest {
		val persistence = DurableRecordingProcessor()
		val analytics = ambientProcessor(id = "analytics", priority = 10)
		val pipeline = createPipeline(setOf(persistence, analytics), this)
		val rawSignals = listOf(testSignal(1_100L), testSignal(1_200L))
		pipeline.start(PolicyTier.AMBIENT, EpochMs(1_000L))

		pipeline.checkpointDurableSignals(rawSignals) shouldBe true

		persistence.signals shouldContainExactly rawSignals
		persistence.checkpointCount shouldBe 1
		analytics.signals shouldHaveSize 0
	}

	@Test
	fun `raw evidence checkpoint reports unavailable or unhealthy durability`() = runTest {
		val persistence = DurableRecordingProcessor().apply { checkpointHealthy = false }
		val pipeline = createPipeline(setOf(persistence), this)

		pipeline.checkpointDurableSignals(listOf(testSignal())) shouldBe false
		pipeline.start(PolicyTier.AMBIENT, EpochMs(1_000L))
		pipeline.checkpointDurableSignals(listOf(testSignal())) shouldBe false
	}

	// endregion

	// region flush

	@Test
	fun `flush is triggered when interval elapses`() = runTest {
		val p = RecordingProcessor(
			ProcessorDescriptor(
				id = "fast-flush",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 100L,
				priority = 0,
			),
		)
		val pipeline = createPipeline(setOf(p), this)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))
		pipeline.onSignal(testSignal(50L))  // 50ms since start — no flush
		pipeline.onSignal(testSignal(150L)) // 150ms since start — flush

		p.flushCount shouldBe 1
	}

	@Test
	fun `flush is not triggered before interval elapses`() = runTest {
		val p = RecordingProcessor(
			ProcessorDescriptor(
				id = "slow-flush",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 60_000L,
				priority = 0,
			),
		)
		val pipeline = createPipeline(setOf(p), this)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))
		pipeline.onSignal(testSignal(1000L))
		pipeline.onSignal(testSignal(2000L))
		pipeline.onSignal(testSignal(3000L))

		p.flushCount shouldBe 0
	}

	// endregion

	// region domain events

	@Test
	fun `domain events from flush are dispatched`() = runTest {
		val collectedEvents = mutableListOf<DomainEvent>()
		val p = RecordingProcessor(
			ProcessorDescriptor(
				id = "event-emitter",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 100L,
				priority = 0,
			),
		).apply {
			flushEvents = { listOf(testEvent(100L)) }
		}
		val dispatcher = UnconfinedTestDispatcher(testScheduler)
		val pipeline = createPipeline(
			setOf(p),
			CoroutineScope(dispatcher + SupervisorJob()),
		) { events -> collectedEvents.addAll(events) }

		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))
		pipeline.onSignal(testSignal(200L)) // triggers flush

		collectedEvents shouldHaveSize 1
	}

	@Test
	fun `failed runtime events retry before final events`() = runTest {
		val runtimeEvent = testEvent(100L)
		val finalEvent = testStopEvent(5000L)
		val processor = RecordingProcessor(
			ProcessorDescriptor(
				id = "ordered-events",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 100L,
				priority = 0,
			),
		).apply {
			flushEvents = { listOf(runtimeEvent) }
			stopEvents = { listOf(finalEvent) }
		}
		var persistenceHealthy = false
		val collectedEvents = mutableListOf<DomainEvent>()
		val pipeline = createPipeline(setOf(processor), this) { events ->
			if (!persistenceHealthy) error("event persistence failed")
			collectedEvents.addAll(events)
		}
		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))

		shouldThrow<IllegalStateException> {
			pipeline.onSignal(testSignal(200L))
		}

		persistenceHealthy = true
		pipeline.stop()

		collectedEvents shouldContainExactly listOf(runtimeEvent, finalEvent)
	}

	@Test
	fun `runtime events checkpoint raw signals before first persistence attempt`() = runTest {
		val persistence = DurableRecordingProcessor()
		val eventProcessor = RecordingProcessor(
			ProcessorDescriptor(
				id = "event-emitter",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 100L,
				priority = 10,
			),
		).apply {
			flushEvents = { listOf(testEvent()) }
		}
		val pipeline = createPipeline(setOf(persistence, eventProcessor), this)
		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))

		pipeline.onSignal(testSignal(200L))

		persistence.checkpointCount shouldBe 1
	}

	@Test
	fun `pending event failure checkpoints newer raw signals before retrying events`() = runTest {
		val persistence = DurableRecordingProcessor()
		val eventProcessor = RecordingProcessor(
			ProcessorDescriptor(
				id = "event-emitter",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 100L,
				priority = 10,
			),
		).apply {
			flushEvents = { listOf(testEvent()) }
		}
		var eventPersistenceHealthy = false
		val pipeline = createPipeline(setOf(persistence, eventProcessor), this) {
			if (!eventPersistenceHealthy) error("event persistence failed")
		}
		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))

		shouldThrow<IllegalStateException> {
			pipeline.onSignal(testSignal(200L))
		}
		shouldThrow<IllegalStateException> {
			pipeline.onSignal(testSignal(300L))
		}

		persistence.signals shouldHaveSize 2
		persistence.checkpointCount shouldBe 2

		eventPersistenceHealthy = true
		pipeline.stop()
	}

	@Test
	fun `tier transition checkpoints raw signals before retrying pending events`() = runTest {
		val persistence = DurableRecordingProcessor()
		val eventProcessor = RecordingProcessor(
			ProcessorDescriptor(
				id = "event-emitter",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 100L,
				priority = 10,
			),
		).apply {
			flushEvents = { listOf(testEvent()) }
		}
		var eventPersistenceHealthy = false
		val pipeline = createPipeline(setOf(persistence, eventProcessor), this) {
			if (!eventPersistenceHealthy) error("event persistence failed")
		}
		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))
		shouldThrow<IllegalStateException> {
			pipeline.onSignal(testSignal(200L))
		}

		pipeline.escalate(PolicyTier.ACTIVE, EpochMs(300L))

		persistence.checkpointCount shouldBe 2
		eventPersistenceHealthy = true
		pipeline.stop()
	}

	@Test
	fun `stalled final event persistence times out and retries without stopping twice`() = runTest {
		val finalEvent = testStopEvent()
		val processor = ambientProcessor().apply {
			stopEvents = { listOf(finalEvent) }
		}
		var persistenceHealthy = false
		val pipeline = createPipeline(setOf(processor), this) {
			if (!persistenceHealthy) awaitCancellation()
		}
		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))

		shouldThrow<IllegalStateException> { pipeline.stop() }
		persistenceHealthy = true
		pipeline.stop()

		processor.stopCount shouldBe 1
	}

	@Test
	fun `domain events from stop are dispatched`() = runTest {
		val collectedEvents = mutableListOf<DomainEvent>()
		val p = ambientProcessor().apply {
			stopEvents = { listOf(testStopEvent(5000L)) }
		}
		val pipeline = createPipeline(setOf(p), this) { events ->
			collectedEvents.addAll(events)
		}

		pipeline.start(PolicyTier.AMBIENT, EpochMs(1000L))
		pipeline.stop()

		collectedEvents shouldHaveSize 1
	}

	@Test
	fun `stop delivers final events even when no flush events pending`() = runTest {
		val collected = mutableListOf<DomainEvent>()
		val expectedEvent = testStopEvent(999L)
		val p = ambientProcessor().apply {
			stopEvents = { listOf(expectedEvent) }
		}
		val pipeline = createPipeline(setOf(p), this) { collected.addAll(it) }

		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))
		pipeline.onSignal(testSignal(1L))
		pipeline.stop()

		collected shouldHaveSize 1
		collected.first() shouldBe expectedEvent
	}

	// endregion

	// region escalation

	@Test
	fun `escalate from AMBIENT to ACTIVE starts active processors`() = runTest {
		val ambient = ambientProcessor()
		val active = activeProcessor()
		val pipeline = createPipeline(setOf(ambient, active), this)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(1000L))
		ambient.startCalls shouldHaveSize 1
		active.startCalls shouldHaveSize 0

		pipeline.escalate(PolicyTier.ACTIVE, EpochMs(2000L))
		active.startCalls shouldHaveSize 1
		ambient.startCalls shouldHaveSize 1 // not re-started
	}

	@Test
	fun `de-escalate from ACTIVE to AMBIENT stops active processors`() = runTest {
		val ambient = ambientProcessor()
		val active = activeProcessor()
		val pipeline = createPipeline(setOf(ambient, active), this)

		pipeline.start(PolicyTier.ACTIVE, EpochMs(1000L))
		pipeline.escalate(PolicyTier.AMBIENT, EpochMs(2000L))

		active.stopCount shouldBe 1
		ambient.stopCount shouldBe 0 // still running
	}

	@Test
	fun `failed de-escalation stop remains active and retries on the next transition`() = runTest {
		var stopHealthy = false
		val ambient = ambientProcessor()
		val active = activeProcessor().apply {
			onStopAction = {
				if (!stopHealthy) error("stop failed")
			}
		}
		val pipeline = createPipeline(setOf(ambient, active), this)
		pipeline.start(PolicyTier.ACTIVE, EpochMs(1000L))

		pipeline.escalate(PolicyTier.AMBIENT, EpochMs(2000L))
		pipeline.onSignal(testSignal(2500L))

		active.stopCount shouldBe 1
		active.signals shouldHaveSize 1

		stopHealthy = true
		pipeline.escalate(PolicyTier.AMBIENT, EpochMs(3000L))
		pipeline.onSignal(testSignal(3500L))

		active.stopCount shouldBe 2
		active.signals shouldHaveSize 1
	}

	@Test
	fun `escalate does nothing when pipeline is not running`() = runTest {
		val p = ambientProcessor()
		val pipeline = createPipeline(setOf(p), this)

		pipeline.escalate(PolicyTier.PRECISION, EpochMs(1000L))
		p.startCalls shouldHaveSize 0
	}

	@Test
	fun `after escalation onSignal reaches newly active processors`() = runTest {
		val ambient = ambientProcessor()
		val active = activeProcessor()
		val pipeline = createPipeline(setOf(ambient, active), this)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(1000L))
		pipeline.onSignal(testSignal(1500L))
		active.signals shouldHaveSize 0

		pipeline.escalate(PolicyTier.ACTIVE, EpochMs(2000L))
		pipeline.onSignal(testSignal(2500L))
		active.signals shouldHaveSize 1
		ambient.signals shouldHaveSize 2
	}

	// endregion

	// region processor failure isolation

	@Test
	fun `processor start failure does not prevent other processors from starting`() = runTest {
		val failing = ambientProcessor(id = "failing", priority = 0).apply {
			onStartAction = { throw RuntimeException("start failed") }
		}
		val healthy = ambientProcessor(id = "healthy", priority = 10)
		val pipeline = createPipeline(setOf(failing, healthy), this)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(1000L))
		healthy.startCalls shouldHaveSize 1
	}

	@Test
	fun `processor flush failure does not prevent other processor flushes`() = runTest {
		val failing = RecordingProcessor(
			ProcessorDescriptor(
				id = "failing",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 100L,
				priority = 0,
			),
		).apply {
			onFlushAction = { throw RuntimeException("flush failed") }
		}
		val healthy = RecordingProcessor(
			ProcessorDescriptor(
				id = "healthy",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 100L,
				priority = 10,
			),
		)
		val pipeline = createPipeline(setOf(failing, healthy), this)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))
		pipeline.onSignal(testSignal(200L)) // triggers flush on both

		healthy.flushCount shouldBe 1
	}

	@Test
	fun `disabled processor remains lifecycle-owned until final stop`() = runTest {
		val failing = ambientProcessor(id = "failing").apply {
			onSignalAction = { throw RuntimeException("boom") }
		}
		val pipeline = createPipeline(setOf(failing), this)
		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))

		repeat(5) { pipeline.onSignal(testSignal(it.toLong())) }
		pipeline.stop()

		failing.stopCount shouldBe 1
	}

	// endregion

	// region multiple signals

	@Test
	fun `multiple signals delivered in order`() = runTest {
		val p = ambientProcessor()
		val pipeline = createPipeline(setOf(p), this)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))

		val signals = (1L..100L).map { testSignal(it * 1000L) }
		for (s in signals) {
			pipeline.onSignal(s)
		}

		p.signals shouldHaveSize 100
		p.signals.map { it.timestampMs.raw } shouldContainExactly (1L..100L).map { it * 1000L }
	}

	// endregion

	// region tier filtering

	@Test
	fun `tier filtering - processors below current tier are not signaled`() = runTest {
		val ambient = ambientProcessor()
		val precision = precisionProcessor()
		val pipeline = createPipeline(setOf(ambient, precision), this)

		pipeline.start(PolicyTier.ACTIVE, EpochMs(1000L))
		pipeline.onSignal(testSignal())

		ambient.signals shouldHaveSize 1
		precision.signals shouldHaveSize 0
	}

	@Test
	fun `escalate changes active processor set`() = runTest {
		val ambient = ambientProcessor()
		val precision = precisionProcessor()
		val pipeline = createPipeline(setOf(ambient, precision), this)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(1000L))
		pipeline.onSignal(testSignal(1500L))
		precision.signals shouldHaveSize 0

		pipeline.escalate(PolicyTier.PRECISION, EpochMs(2000L))
		pipeline.onSignal(testSignal(2500L))

		precision.signals shouldHaveSize 1
		ambient.signals shouldHaveSize 2
	}

	// endregion

	// region additional failure isolation

	@Test
	fun `error in one processor does not affect others`() = runTest {
		val failing = activeProcessor(id = "failing", priority = 0).apply {
			onSignalAction = { throw IllegalStateException("processor error") }
		}
		val healthy = activeProcessor(id = "healthy", priority = 10)
		val pipeline = createPipeline(setOf(failing, healthy), this)

		pipeline.start(PolicyTier.ACTIVE, EpochMs(1000L))
		pipeline.onSignal(testSignal())

		healthy.signals shouldHaveSize 1
	}

	// endregion

	// region combined events

	@Test
	fun `flush returns events from all processors`() = runTest {
		val collectedEvents = mutableListOf<DomainEvent>()
		val eventA = testEvent(100L)
		val eventB = testEvent(200L)
		val a = RecordingProcessor(
			ProcessorDescriptor(
				id = "emitter-a",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 100L,
				priority = 0,
			),
		).apply { flushEvents = { listOf(eventA) } }
		val b = RecordingProcessor(
			ProcessorDescriptor(
				id = "emitter-b",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 100L,
				priority = 10,
			),
		).apply { flushEvents = { listOf(eventB) } }
		val dispatcher = UnconfinedTestDispatcher(testScheduler)
		val pipeline = createPipeline(
			setOf(a, b),
			CoroutineScope(dispatcher + SupervisorJob()),
		) { events -> collectedEvents.addAll(events) }

		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))
		pipeline.onSignal(testSignal(200L)) // triggers flush

		collectedEvents shouldHaveSize 2
	}

	@Test
	fun `stop returns combined events from all processors`() = runTest {
		val collectedEvents = mutableListOf<DomainEvent>()
		val stopA = testStopEvent(5000L)
		val stopB = testStopEvent(6000L)
		val a = ambientProcessor(id = "stop-a").apply {
			stopEvents = { listOf(stopA) }
		}
		val b = ambientProcessor(id = "stop-b").apply {
			stopEvents = { listOf(stopB) }
		}
		val pipeline = createPipeline(setOf(a, b), this) { events ->
			collectedEvents.addAll(events)
		}

		pipeline.start(PolicyTier.AMBIENT, EpochMs(1000L))
		pipeline.stop()

		collectedEvents shouldHaveSize 2
	}

	// endregion
}
