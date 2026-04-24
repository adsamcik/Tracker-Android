package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.processor.ProcessorDescriptor
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import com.adsamcik.tracker.tracker.pipeline.ProcessorPipeline
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Verifies the shutdown ordering contract: session data must be persisted
 * (simulated via a callback) BEFORE [ProcessorPipeline.stop] emits
 * [DomainEvent.SessionEnded].
 *
 * This mirrors the fix in [TrackingOrchestrator.destroyComponents] where
 * `SessionTrackerComponent.onDisable()` is called before
 * `ProcessorPipeline.stop()` so that downstream consumers see finalized data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShutdownOrderingTest {

	/**
	 * Simulates the shutdown sequence: a "session finalizer" callback runs
	 * first, then the pipeline emits SessionEnded. The test asserts that
	 * when SessionEnded arrives, the session was already finalized.
	 */
	@Test
	fun `session data is finalized before SessionEnded is emitted`() = runTest {
		val sessionFinalized = AtomicBoolean(false)
		var sessionWasFinalizedWhenEventEmitted = false

		val processor = object : SignalProcessor {
			override val descriptor = ProcessorDescriptor(
				id = "test-aggregator",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 60_000L,
				priority = 10,
			)

			override suspend fun onStart(context: ProcessorContext) {}
			override fun onSignal(signal: TrackingSignal) {}
			override suspend fun onFlush(): List<DomainEvent> = emptyList()

			override suspend fun onStop(): List<DomainEvent> = listOf(
				DomainEvent.SessionEnded(
					timestampMs = EpochMs(5000L),
					processorId = descriptor.id,
					sessionId = 1L,
					totalDistance = DistanceM.coerced(500f),
					totalSteps = StepCount(1000),
					duration = DurationMs(3000L),
				),
			)

			override fun checkpoint(): ByteArray? = null
			override fun restore(state: ByteArray) {}
		}

		val pipeline = ProcessorPipeline(
			processors = setOf(processor),
			scope = this,
			onDomainEvents = { events ->
				// This callback fires when SessionEnded is delivered.
				// At this point, the session should already be finalized.
				if (events.any { it is DomainEvent.SessionEnded }) {
					sessionWasFinalizedWhenEventEmitted = sessionFinalized.get()
				}
			},
		)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))

		// Simulate the corrected shutdown ordering from TrackingOrchestrator:
		// 1. Finalize session data (SessionTrackerComponent.onDisable)
		sessionFinalized.set(true)

		// 2. Stop pipeline (emits SessionEnded)
		pipeline.stop()

		// Verify that when SessionEnded was delivered, session was already finalized
		sessionWasFinalizedWhenEventEmitted shouldBe true
	}

	/**
	 * Proves the bug scenario: if pipeline.stop() runs BEFORE session
	 * finalization, SessionEnded sees un-finalized data.
	 */
	@Test
	fun `reversed ordering causes SessionEnded before finalization`() = runTest {
		val sessionFinalized = AtomicBoolean(false)
		var sessionWasFinalizedWhenEventEmitted = false

		val processor = object : SignalProcessor {
			override val descriptor = ProcessorDescriptor(
				id = "test-aggregator",
				requiredTier = PolicyTier.AMBIENT,
				flushIntervalMs = 60_000L,
				priority = 10,
			)

			override suspend fun onStart(context: ProcessorContext) {}
			override fun onSignal(signal: TrackingSignal) {}
			override suspend fun onFlush(): List<DomainEvent> = emptyList()

			override suspend fun onStop(): List<DomainEvent> = listOf(
				DomainEvent.SessionEnded(
					timestampMs = EpochMs(5000L),
					processorId = descriptor.id,
					sessionId = 1L,
					totalDistance = DistanceM.coerced(500f),
					totalSteps = StepCount(1000),
					duration = DurationMs(3000L),
				),
			)

			override fun checkpoint(): ByteArray? = null
			override fun restore(state: ByteArray) {}
		}

		val pipeline = ProcessorPipeline(
			processors = setOf(processor),
			scope = this,
			onDomainEvents = { events ->
				if (events.any { it is DomainEvent.SessionEnded }) {
					sessionWasFinalizedWhenEventEmitted = sessionFinalized.get()
				}
			},
		)

		pipeline.start(PolicyTier.AMBIENT, EpochMs(0L))

		// Simulate the BROKEN ordering (old code):
		// 1. Stop pipeline first (emits SessionEnded)
		pipeline.stop()

		// 2. Finalize session data after (too late!)
		sessionFinalized.set(true)

		// Verify that SessionEnded arrived before finalization
		sessionWasFinalizedWhenEventEmitted shouldBe false
	}
}
