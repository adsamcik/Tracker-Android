package com.adsamcik.tracker.stats.engine.processor

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.LocationSignal
import com.adsamcik.tracker.stats.api.signal.StepSignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.ActivityConfidence
import com.adsamcik.tracker.stats.api.value.CoordinateE7
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import com.adsamcik.tracker.stats.api.value.SpeedMps
import com.adsamcik.tracker.stats.api.value.StepCount
import com.adsamcik.tracker.stats.engine.aggregator.StreamingAggregator
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AggregatorProcessorTest {

	private fun movingSignal(timestampMs: Long, distanceM: Float = 10f, steps: Int = 5) =
		TrackingSignal(
			timestampMs = EpochMs(timestampMs),
			location = LocationSignal(
				coordinate = CoordinateE7(
					lat = LatE7.fromDegrees(49.2),
					lon = LonE7.fromDegrees(16.6),
				),
				horizontalAccuracyM = 5.0f,
				speed = SpeedMps.coerced(1.4f),
				altitudeM = 200f,
				distanceDelta = DistanceM.coerced(distanceM),
			),
			activity = ActivitySignal(
				type = DetectedActivityType.WALKING,
				confidence = ActivityConfidence(85),
			),
			steps = StepSignal(
				stepDelta = StepCount(steps),
				totalStepsSinceBoot = 100L,
			),
		)

	@Test
	fun `start + multiple signals + flush produces DailySummaryUpdated`() = runTest {
		val processor = AggregatorProcessor(StreamingAggregator())
		val ctx = ProcessorContext(startTimestamp = EpochMs(1000L))

		processor.onStart(ctx)
		processor.onSignal(movingSignal(timestampMs = 2000L, distanceM = 10f, steps = 5))
		processor.onSignal(movingSignal(timestampMs = 3000L, distanceM = 15f, steps = 8))

		val events = processor.onFlush()

		events shouldHaveSize 1
		val event = events.first()
		event.shouldBeInstanceOf<DomainEvent.DailySummaryUpdated>()
		event.totalDistance.raw shouldBe 25f
		event.totalSteps.raw shouldBe 13
		event.processorId shouldBe "aggregator"
	}

	@Test
	fun `start + signals + stop produces SessionEnded`() = runTest {
		val processor = AggregatorProcessor(StreamingAggregator())
		val ctx = ProcessorContext(startTimestamp = EpochMs(1000L))

		processor.onStart(ctx)
		processor.onSignal(movingSignal(timestampMs = 2000L, distanceM = 20f, steps = 10))
		processor.onSignal(movingSignal(timestampMs = 4000L, distanceM = 30f, steps = 15))

		val events = processor.onStop()

		events shouldHaveSize 1
		val event = events.first()
		event.shouldBeInstanceOf<DomainEvent.SessionEnded>()
		event.totalDistance.raw shouldBe 50f
		event.totalSteps.raw shouldBe 25
		event.duration.raw shouldBe 3000L
		event.processorId shouldBe "aggregator"
	}

	@Test
	fun `flush after start with no signals returns DailySummaryUpdated with zero values`() = runTest {
		val processor = AggregatorProcessor(StreamingAggregator())
		val ctx = ProcessorContext(startTimestamp = EpochMs(1000L))

		processor.onStart(ctx)

		val events = processor.onFlush()

		events shouldHaveSize 1
		val event = events.first()
		event.shouldBeInstanceOf<DomainEvent.DailySummaryUpdated>()
		event.totalDistance.raw shouldBe 0f
		event.totalSteps.raw shouldBe 0
	}
}
