package com.adsamcik.tracker.stats.engine.processor

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.ActivityConfidence
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.achievement.AchievementEvaluator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AchievementProcessorTest {

	private fun testSignal(timestampMs: Long = 1000L) = TrackingSignal(
		timestampMs = EpochMs(timestampMs),
		activity = ActivitySignal(
			type = DetectedActivityType.WALKING,
			confidence = ActivityConfidence(80),
		),
	)

	@Test
	fun `raw signals are ignored - onSignal is a no-op`() = runTest {
		val processor = AchievementProcessor(
			evaluator = AchievementEvaluator(),
			metricsProvider = { emptyMap() },
		)
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		processor.onSignal(testSignal(2000L))
		processor.onSignal(testSignal(3000L))

		// Flush should produce no events since metrics are empty
		val events = processor.onFlush()
		events.shouldBeEmpty()
	}

	@Test
	fun `flush with empty metrics produces no events`() = runTest {
		val processor = AchievementProcessor(
			evaluator = AchievementEvaluator(),
			metricsProvider = { emptyMap() },
		)
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		val events = processor.onFlush()
		events.shouldBeEmpty()
	}

	@Test
	fun `onStop delegates to onFlush`() = runTest {
		val processor = AchievementProcessor(
			evaluator = AchievementEvaluator(),
			metricsProvider = { emptyMap() },
		)
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		// Feed a signal (should be ignored)
		processor.onSignal(testSignal(2000L))

		// onStop delegates to onFlush, which with empty metrics returns empty
		val stopEvents = processor.onStop()
		stopEvents.shouldBeEmpty()

		// After stop, subsequent flush should also be empty
		val postStopEvents = processor.onFlush()
		postStopEvents.shouldBeEmpty()
	}

	@Test
	fun `flush with real metrics emits unlock when tier increases`() = runTest {
		val processor = AchievementProcessor(
			evaluator = AchievementEvaluator(),
			metricsProvider = {
				mapOf("total_steps" to 15_000L)
			},
		)
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		val events = processor.onFlush()
		// total_steps=15_000 crosses the first tier for steps_total.
		events.shouldHaveSize(1)
		assert(events.single() is DomainEvent.AchievementUnlocked)
	}

	// --- p6-3 / p6-7: dirty-aware flush short-circuit ---

	private class StubDirtyTracker(initialDirty: Set<String> = emptySet()) : MetricDirtyTracker {
		private var dirty = initialDirty
		var consumeCalls = 0
		override fun markDirty(table: String) { dirty = dirty + table }
		override fun markDirty(tables: Set<String>) { dirty = dirty + tables }
		override fun consumeDirty(): Set<String> {
			consumeCalls += 1
			val out = dirty; dirty = emptySet(); return out
		}
	}

	@Test
	fun `flush short-circuits when dirty tracker reports nothing changed`() = runTest {
		var providerCalls = 0
		val tracker = StubDirtyTracker()
		val processor = AchievementProcessor(
			evaluator = AchievementEvaluator(),
			metricsProvider = {
				providerCalls += 1
				mapOf("total_steps" to 50_000L)
			},
			dirtyTracker = tracker,
		)
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		val events = processor.onFlush()
		events.shouldBeEmpty()
		// metricsProvider must NOT be called when nothing is dirty — that's the battery win
		providerCalls shouldBe 0
		tracker.consumeCalls shouldBe 1
	}

	@Test
	fun `flush proceeds normally when dirty tracker reports a change`() = runTest {
		var providerCalls = 0
		val tracker = StubDirtyTracker()
		val processor = AchievementProcessor(
			evaluator = AchievementEvaluator(),
			metricsProvider = {
				providerCalls += 1
				mapOf("total_steps" to 15_000L)
			},
			dirtyTracker = tracker,
		)
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		// Mark a table dirty; the processor should evaluate fully.
		tracker.markDirty("daily_summary")
		val events = processor.onFlush()
		providerCalls shouldBe 1
		events.shouldHaveSize(1)
		assert(events.single() is DomainEvent.AchievementUnlocked)
	}

	@Test
	fun `consumed dirty does not leak into next flush`() = runTest {
		val tracker = StubDirtyTracker()
		var metricValue = 15_000L
		val processor = AchievementProcessor(
			evaluator = AchievementEvaluator(),
			metricsProvider = { mapOf("total_steps" to metricValue) },
			dirtyTracker = tracker,
		)
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		tracker.markDirty("daily_summary")
		processor.onFlush()  // consumes the dirty set
		// On the next flush nothing has been marked since — short-circuit.
		metricValue = 25_000L  // would unlock another tier if evaluated
		val events = processor.onFlush()
		events.shouldBeEmpty()
	}

	@Test
	fun `consumed dirty is re-marked if metricsProvider throws — battery vs correctness`() = runTest {
		// p6-7 dirty-loss bug regression test.
		// Without the catch-and-restore in onFlush, a metricsProvider exception would
		// "swallow" the dirty mark and the NEXT flush would short-circuit even though
		// the underlying table had genuinely changed.
		val tracker = StubDirtyTracker()
		var providerCalls = 0
		val processor = AchievementProcessor(
			evaluator = AchievementEvaluator(),
			metricsProvider = {
				providerCalls += 1
				if (providerCalls == 1) throw RuntimeException("transient DB error")
				mapOf("total_steps" to 15_000L)
			},
			dirtyTracker = tracker,
		)
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		tracker.markDirty("daily_summary")
		// First flush throws — dirty mark MUST be restored.
		try {
			processor.onFlush()
			error("expected the provider exception to propagate")
		} catch (e: RuntimeException) {
			e.message shouldBe "transient DB error"
		}
		// Verify the dirty mark survived the failed flush.
		// (Without the fix the next flush would short-circuit and miss the achievement.)
		val events = processor.onFlush()
		providerCalls shouldBe 2
		events.shouldHaveSize(1)
		assert(events.single() is DomainEvent.AchievementUnlocked)
	}
}
