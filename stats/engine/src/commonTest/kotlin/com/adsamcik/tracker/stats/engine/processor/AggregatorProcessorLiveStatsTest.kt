package com.adsamcik.tracker.stats.engine.processor

import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.repository.LiveStats
import com.adsamcik.tracker.stats.api.signal.LocationSignal
import com.adsamcik.tracker.stats.api.signal.StepSignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.CoordinateE7
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import com.adsamcik.tracker.stats.api.value.StepCount
import com.adsamcik.tracker.stats.engine.FakeLiveStatsRepository
import com.adsamcik.tracker.stats.engine.aggregator.StreamingAggregator
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AggregatorProcessorLiveStatsTest {

	@Test
	fun `flush persists the exact supported aggregator snapshot`() = runTest {
		val repository = FakeLiveStatsRepository()
		val processor = AggregatorProcessor(
			liveStatsRepository = repository,
			aggregator = StreamingAggregator(),
		)
		val sessionStartMs = DATE_EPOCH_DAY * MILLIS_PER_DAY
		val lastUpdateMs = sessionStartMs + 5_000L
		processor.onStart(
			ProcessorContext(
				startTimestamp = EpochMs(sessionStartMs),
				sessionId = 42L,
			),
		)
		processor.seedDayTotals(
			distanceM = 100f,
			steps = 200,
			durationMs = 3_000L,
			trips = 2,
		)
		processor.onSignal(
			TrackingSignal(
				timestampMs = EpochMs(lastUpdateMs),
				location = LocationSignal(
					coordinate = CoordinateE7(LatE7(0), LonE7(0)),
					horizontalAccuracyM = 5f,
					speed = null,
					distanceDelta = DistanceM(7.5f),
				),
				steps = StepSignal(
					stepDelta = StepCount(12),
					totalStepsSinceBoot = 12L,
				),
			),
		)

		processor.onFlush()

		repository.updates.single() shouldBe LiveStats(
			dateEpochDay = DATE_EPOCH_DAY,
			sessionDistance = DistanceM(7.5f),
			sessionSteps = StepCount(12),
			sessionDuration = DurationMs(5_000L),
			dayTotalDistance = DistanceM(107.5f),
			dayTotalSteps = StepCount(212),
			dayTotalDuration = DurationMs(8_000L),
			lastUpdatedMs = lastUpdateMs,
		)
	}

	@Test
	fun `stop clears persisted live stats after the final snapshot is captured`() = runTest {
		val repository = FakeLiveStatsRepository()
		val processor = AggregatorProcessor(
			liveStatsRepository = repository,
			aggregator = StreamingAggregator(),
		)
		processor.onStart(ProcessorContext(startTimestamp = EpochMs(MILLIS_PER_DAY)))
		processor.onSignal(TrackingSignal(timestampMs = EpochMs(MILLIS_PER_DAY + 1_000L)))
		processor.onFlush()

		processor.onStop()

		repository.clearCount shouldBe 1
		repository.calls.shouldContainExactly("update", "clear")
	}

	private companion object {
		const val DATE_EPOCH_DAY = 20_000L
		const val MILLIS_PER_DAY = 86_400_000L
	}
}
