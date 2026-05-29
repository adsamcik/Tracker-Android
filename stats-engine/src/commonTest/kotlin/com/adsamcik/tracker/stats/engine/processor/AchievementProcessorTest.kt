package com.adsamcik.tracker.stats.engine.processor

import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.metric.TimeWindow
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.rule.Rule
import com.adsamcik.tracker.stats.api.rule.RuleInstance
import com.adsamcik.tracker.stats.api.rule.RuleKind
import com.adsamcik.tracker.stats.api.rule.RuleRegistry
import com.adsamcik.tracker.stats.api.rule.RuleTarget
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.ActivityConfidence
import com.adsamcik.tracker.stats.api.value.EpochMs
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

	private fun processor(
		metricsProvider: () -> Map<String, Long>,
		dirtyTracker: MetricDirtyTracker? = null,
		definitions: List<AchievementDefinition> = AchievementCatalog.definitions,
		previous: Map<String, Pair<Long, AchievementTier?>> = emptyMap(),
	): AchievementProcessor = AchievementProcessor(
		registry = StaticAchievementRegistry(definitions, previous),
		metricsProvider = metricsProvider,
		dirtyTracker = dirtyTracker,
	)

	private class StaticAchievementRegistry(
		private val definitions: List<AchievementDefinition>,
		private val previous: Map<String, Pair<Long, AchievementTier?>>,
	) : RuleRegistry {
		override suspend fun allInstances(): List<RuleInstance> = definitions.map(::toInstance)

		override suspend fun instancesAffectedByTables(
			dirtyTables: Set<String>,
		): List<RuleInstance> {
			if (dirtyTables.isEmpty()) return emptyList()
			return definitions
				.filter { definition -> MetricKeys.sourceTables(definition.metric).any { it in dirtyTables } }
				.map(::toInstance)
		}

		private fun toInstance(definition: AchievementDefinition): RuleInstance {
			val previousProgress = previous[definition.id]
			return RuleInstance(
				rule = Rule(
					id = definition.id,
					kind = RuleKind.Achievement,
					metric = definition.metric,
					target = RuleTarget.Tiered(definition.tiers),
				),
				window = TimeWindow.Cumulative,
				previousValue = previousProgress?.first,
				previousTier = previousProgress?.second,
				attachment = definition,
			)
		}
	}

	@Test
	fun `raw signals are ignored - onSignal is a no-op`() = runTest {
		val processor = processor(metricsProvider = { emptyMap() })
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		processor.onSignal(testSignal(2000L))
		processor.onSignal(testSignal(3000L))

		// Flush should produce no events since metrics are empty
		val events = processor.onFlush()
		events.shouldBeEmpty()
	}

	@Test
	fun `flush with empty metrics produces no events`() = runTest {
		val processor = processor(metricsProvider = { emptyMap() })
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		val events = processor.onFlush()
		events.shouldBeEmpty()
	}

	@Test
	fun `onStop delegates to onFlush`() = runTest {
		val processor = processor(metricsProvider = { emptyMap() })
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
		val processor = processor(
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

	@Test
	fun `progress event uses next tier target from rule evaluator`() = runTest {
		val processor = processor(
			metricsProvider = { mapOf("total_steps" to 5_000L) },
		)
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		val events = processor.onFlush()
		events.shouldHaveSize(1)
		val progress = events.single() as DomainEvent.AchievementProgress
		progress.achievementId shouldBe "steps_total"
		progress.currentValue shouldBe 5_000L
		progress.targetValue shouldBe 10_000L
	}

	// --- Dirty-aware flush short-circuit ---

	private class StubDirtyTracker(initialDirty: Set<String> = emptySet()) : MetricDirtyTracker {
		private var dirty = initialDirty
		var consumeCalls = 0
		override fun markDirty(table: String) { dirty = dirty + table }
		override fun markDirty(tables: Set<String>) { dirty = dirty + tables }
		override fun consumeDirty(): Set<String> {
			consumeCalls += 1
			val out = dirty
			dirty = emptySet()
			return out
		}
	}

	@Test
	fun `flush short-circuits when dirty tracker reports nothing changed`() = runTest {
		var providerCalls = 0
		val tracker = StubDirtyTracker()
		val processor = processor(
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
		val processor = processor(
			metricsProvider = {
				providerCalls += 1
				mapOf("total_steps" to 15_000L)
			},
			dirtyTracker = tracker,
		)
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		// Mark a table dirty; the processor should evaluate fully.
		tracker.markDirty(MetricKeys.TABLE_DAILY_SUMMARY)
		val events = processor.onFlush()
		providerCalls shouldBe 1
		events.shouldHaveSize(1)
		assert(events.single() is DomainEvent.AchievementUnlocked)
	}

	@Test
	fun `consumed dirty does not leak into next flush`() = runTest {
		val tracker = StubDirtyTracker()
		var metricValue = 15_000L
		val processor = processor(
			metricsProvider = { mapOf("total_steps" to metricValue) },
			dirtyTracker = tracker,
		)
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		tracker.markDirty(MetricKeys.TABLE_DAILY_SUMMARY)
		processor.onFlush() // consumes the dirty set
		// On the next flush nothing has been marked since — short-circuit.
		metricValue = 25_000L // would unlock another tier if evaluated
		val events = processor.onFlush()
		events.shouldBeEmpty()
	}

	@Test
	fun `consumed dirty is re-marked if metricsProvider throws — battery vs correctness`() = runTest {
		// Dirty-loss bug regression test.
		// Without the catch-and-restore in onFlush, a metricsProvider exception would
		// "swallow" the dirty mark and the NEXT flush would short-circuit even though
		// the underlying table had genuinely changed.
		val tracker = StubDirtyTracker()
		var providerCalls = 0
		val processor = processor(
			metricsProvider = {
				providerCalls += 1
				if (providerCalls == 1) throw RuntimeException("transient DB error")
				mapOf("total_steps" to 15_000L)
			},
			dirtyTracker = tracker,
		)
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		tracker.markDirty(MetricKeys.TABLE_DAILY_SUMMARY)
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
