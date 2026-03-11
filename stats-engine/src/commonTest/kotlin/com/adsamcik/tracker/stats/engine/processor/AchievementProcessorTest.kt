package com.adsamcik.tracker.stats.engine.processor

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.ActivityConfidence
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.engine.achievement.AchievementEvaluator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
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
	fun `flush with real metrics produces achievement events`() = runTest {
		val processor = AchievementProcessor(
			evaluator = AchievementEvaluator(),
			metricsProvider = {
				mapOf("total_steps" to 15_000L)
			},
		)
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(1000L)))

		val events = processor.onFlush()
		// total_steps=15_000 should trigger BRONZE tier (10_000) on "steps_total" achievement
		events.shouldHaveSize(1)
	}
}
