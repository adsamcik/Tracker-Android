package com.adsamcik.tracker.stats.engine.aggregator

import com.adsamcik.tracker.stats.api.AggregatorSignal
import com.adsamcik.tracker.stats.api.DetectedActivityType
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StreamingAggregatorTest {

	private var currentTimeMs: Long = 1000000L

	private fun createAggregator() = StreamingAggregator(clock = { currentTimeMs })

	private fun movingSignal(
		timestampMs: Long,
		distanceDeltaM: Float? = 100f,
		speedMps: Float? = 5f,
		stepDelta: Int = 10,
		activityType: DetectedActivityType? = DetectedActivityType.WALKING,
		activityConfidence: Int? = 85,
	) = AggregatorSignal(
		timestampMs = timestampMs,
		distanceDeltaM = distanceDeltaM,
		speedMps = speedMps,
		stepDelta = stepDelta,
		activityType = activityType,
		activityConfidence = activityConfidence,
	)

	@Nested
	inner class LifecycleTests {

		@Test
		fun `initial state is inactive`() {
			val aggregator = createAggregator()
			aggregator.isActive.shouldBeFalse()
		}

		@Test
		fun `start activates aggregator`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)
			aggregator.isActive.shouldBeTrue()
		}

		@Test
		fun `stop deactivates aggregator`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)
			aggregator.stop()
			aggregator.isActive.shouldBeFalse()
		}

		@Test
		fun `double start throws IllegalArgumentException`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)
			assertThrows<IllegalArgumentException> {
				aggregator.start(currentTimeMs + 1000)
			}
		}

		@Test
		fun `stop without start throws IllegalStateException`() {
			val aggregator = createAggregator()
			assertThrows<IllegalStateException> {
				aggregator.stop()
			}
		}

		@Test
		fun `reset clears all state`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)
			aggregator.seedDayTotals(distanceM = 5000f, steps = 1000, durationMs = 60000, trips = 2)
			aggregator.onSignal(movingSignal(currentTimeMs + 1000))
			aggregator.reset()

			aggregator.isActive.shouldBeFalse()
			val snapshot = aggregator.snapshot()
			snapshot.sessionDistanceM shouldBe 0f
			snapshot.sessionSteps shouldBe 0
			snapshot.dayTotalDistanceM shouldBe 0f
			snapshot.dayTotalSteps shouldBe 0
			snapshot.tripCount shouldBe 0
		}

		@Test
		fun `can restart after stop`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)
			aggregator.stop()
			aggregator.start(currentTimeMs + 10000)
			aggregator.isActive.shouldBeTrue()
		}
	}

	@Nested
	inner class SignalAccumulation {

		@Test
		fun `distance accumulates across signals`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)

			aggregator.onSignal(movingSignal(currentTimeMs + 1000, distanceDeltaM = 100f))
			aggregator.onSignal(movingSignal(currentTimeMs + 2000, distanceDeltaM = 150f))
			aggregator.onSignal(movingSignal(currentTimeMs + 3000, distanceDeltaM = 200f))

			val snapshot = aggregator.snapshot()
			snapshot.sessionDistanceM shouldBe 450f
		}

		@Test
		fun `steps accumulate across signals`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)

			aggregator.onSignal(movingSignal(currentTimeMs + 1000, stepDelta = 10))
			aggregator.onSignal(movingSignal(currentTimeMs + 2000, stepDelta = 15))
			aggregator.onSignal(movingSignal(currentTimeMs + 3000, stepDelta = 20))

			val snapshot = aggregator.snapshot()
			snapshot.sessionSteps shouldBe 45
		}

		@Test
		fun `duration tracks time between signals`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)

			aggregator.onSignal(movingSignal(currentTimeMs + 1000))
			aggregator.onSignal(movingSignal(currentTimeMs + 3000))
			aggregator.onSignal(movingSignal(currentTimeMs + 6000))

			val snapshot = aggregator.snapshot()
			snapshot.sessionDurationMs shouldBe 6000L // (1000-0) + (3000-1000) + (6000-3000)
		}

		@Test
		fun `speed tracking updates current and max from instantaneous readings`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)

			aggregator.onSignal(movingSignal(currentTimeMs + 1000, speedMps = 5f))
			aggregator.onSignal(movingSignal(currentTimeMs + 2000, speedMps = 10f))
			aggregator.onSignal(movingSignal(currentTimeMs + 3000, speedMps = 3f))

			val snapshot = aggregator.snapshot()
			snapshot.currentSpeedMps shouldBe 3f // last value
			snapshot.maxSpeedMps shouldBe 10f
		}

		@Test
		fun `average speed is total distance over total duration`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)

			// 1 second per signal, distance matches the instantaneous speed reading exactly,
			// so distance-based and naive-mean averages coincide here (equal intervals).
			aggregator.onSignal(movingSignal(currentTimeMs + 1000, distanceDeltaM = 5f, speedMps = 5f))
			aggregator.onSignal(movingSignal(currentTimeMs + 2000, distanceDeltaM = 10f, speedMps = 10f))
			aggregator.onSignal(movingSignal(currentTimeMs + 3000, distanceDeltaM = 3f, speedMps = 3f))

			val snapshot = aggregator.snapshot()
			snapshot.sessionDistanceM shouldBe 18f
			snapshot.sessionDurationMs shouldBe 3000L
			snapshot.avgSpeedMps shouldBe 6f // 18m / 3s
		}

		@Test
		fun `average speed is not skewed by uneven sampling intervals`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)

			// A long slow segment (100s @ 2 m/s = 200m) followed by a brief fast burst
			// (1s @ 20 m/s = 20m). A naive mean of the two readings would wrongly report
			// 11 m/s; the true time-weighted average is far closer to the slow segment.
			aggregator.onSignal(movingSignal(currentTimeMs + 100_000, distanceDeltaM = 200f, speedMps = 2f))
			aggregator.onSignal(movingSignal(currentTimeMs + 101_000, distanceDeltaM = 20f, speedMps = 20f))

			val snapshot = aggregator.snapshot()
			val expectedAverage = 220f / 101f
			snapshot.avgSpeedMps shouldBe expectedAverage
			snapshot.avgSpeedMps shouldBeLessThan 11f // naive mean of (2+20)/2 would have been 11
		}

		@Test
		fun `null distance is ignored`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)

			aggregator.onSignal(movingSignal(currentTimeMs + 1000, distanceDeltaM = 100f))
			aggregator.onSignal(movingSignal(currentTimeMs + 2000, distanceDeltaM = null))
			aggregator.onSignal(movingSignal(currentTimeMs + 3000, distanceDeltaM = 200f))

			val snapshot = aggregator.snapshot()
			snapshot.sessionDistanceM shouldBe 300f
		}

		@Test
		fun `null speed does not affect current or distance-based average speed`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)

			aggregator.onSignal(movingSignal(currentTimeMs + 1000, distanceDeltaM = 10f, speedMps = 5f))
			aggregator.onSignal(movingSignal(currentTimeMs + 2000, distanceDeltaM = 10f, speedMps = null))
			aggregator.onSignal(movingSignal(currentTimeMs + 3000, distanceDeltaM = 10f, speedMps = 10f))

			val snapshot = aggregator.snapshot()
			snapshot.currentSpeedMps shouldBe 10f // last non-null reading
			snapshot.avgSpeedMps shouldBe 10f // 30m / 3s, unaffected by the null reading
		}

		@Test
		fun `activity votes are tracked`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)

			aggregator.onSignal(movingSignal(currentTimeMs + 1000, activityType = DetectedActivityType.WALKING))
			aggregator.onSignal(movingSignal(currentTimeMs + 2000, activityType = DetectedActivityType.WALKING))
			aggregator.onSignal(movingSignal(currentTimeMs + 3000, activityType = DetectedActivityType.RUNNING))

			val snapshot = aggregator.snapshot()
			snapshot.dominantActivity shouldBe DetectedActivityType.WALKING
		}

		@Test
		fun `sample count tracks GPS signals`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)

			aggregator.onSignal(movingSignal(currentTimeMs + 1000, distanceDeltaM = 100f, speedMps = 5f))
			aggregator.onSignal(movingSignal(currentTimeMs + 2000, distanceDeltaM = null, speedMps = null, stepDelta = 10))
			aggregator.onSignal(movingSignal(currentTimeMs + 3000, distanceDeltaM = 150f, speedMps = null))
			aggregator.onSignal(movingSignal(currentTimeMs + 4000, distanceDeltaM = null, speedMps = 6f))

			val snapshot = aggregator.snapshot()
			snapshot.sampleCount shouldBe 3 // signals with distance or speed
		}
	}

	@Nested
	inner class DayTotals {

		@Test
		fun `seed day totals reflected in snapshot`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)
			aggregator.seedDayTotals(distanceM = 5000f, steps = 1000, durationMs = 60000, trips = 2)

			val snapshot = aggregator.snapshot()
			snapshot.dayTotalDistanceM shouldBe 5000f
			snapshot.dayTotalSteps shouldBe 1000
			snapshot.dayTotalDurationMs shouldBe 60000L
			snapshot.tripCount shouldBe 2
		}

		@Test
		fun `session plus prior day totals sum correctly`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)
			aggregator.seedDayTotals(distanceM = 5000f, steps = 1000, durationMs = 60000, trips = 2)

			aggregator.onSignal(movingSignal(currentTimeMs + 1000, distanceDeltaM = 200f, stepDelta = 50))
			aggregator.onSignal(movingSignal(currentTimeMs + 3000, distanceDeltaM = 300f, stepDelta = 75))

			val snapshot = aggregator.snapshot()
			snapshot.sessionDistanceM shouldBe 500f
			snapshot.sessionSteps shouldBe 125
			snapshot.sessionDurationMs shouldBe 3000L
			snapshot.dayTotalDistanceM shouldBe 5500f // 5000 + 500
			snapshot.dayTotalSteps shouldBe 1125 // 1000 + 125
			snapshot.dayTotalDurationMs shouldBe 63000L // 60000 + 3000
		}

		@Test
		fun `trip count incremented`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)
			aggregator.seedDayTotals(distanceM = 0f, steps = 0, durationMs = 0, trips = 1)

			aggregator.onTripCompleted()
			aggregator.onTripCompleted()

			val snapshot = aggregator.snapshot()
			snapshot.tripCount shouldBe 3 // 1 prior + 2 new
		}

		@Test
		fun `multiple sessions accumulate day totals`() {
			val aggregator = createAggregator()

			// First session
			aggregator.start(currentTimeMs)
			aggregator.onSignal(movingSignal(currentTimeMs + 1000, distanceDeltaM = 100f, stepDelta = 20))
			val snapshot1 = aggregator.stop()

			// Second session - seed with first session's totals
			aggregator.start(currentTimeMs + 10000)
			aggregator.seedDayTotals(
				distanceM = snapshot1.sessionDistanceM,
				steps = snapshot1.sessionSteps,
				durationMs = snapshot1.sessionDurationMs,
				trips = 1
			)
			aggregator.onSignal(movingSignal(currentTimeMs + 11000, distanceDeltaM = 200f, stepDelta = 30))
			val snapshot2 = aggregator.snapshot()

			snapshot2.dayTotalDistanceM shouldBe 300f // 100 + 200
			snapshot2.dayTotalSteps shouldBe 50 // 20 + 30
		}

		@Test
		fun `seed with zero values works correctly`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)
			aggregator.seedDayTotals(distanceM = 0f, steps = 0, durationMs = 0L, trips = 0)

			aggregator.onSignal(movingSignal(currentTimeMs + 1000, distanceDeltaM = 100f, stepDelta = 10))

			val snapshot = aggregator.snapshot()
			snapshot.dayTotalDistanceM shouldBe 100f
			snapshot.dayTotalSteps shouldBe 10
			snapshot.tripCount shouldBe 0
		}
	}

	@Nested
	inner class SnapshotBehavior {

		@Test
		fun `snapshot is non-destructive`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)
			aggregator.onSignal(movingSignal(currentTimeMs + 1000, distanceDeltaM = 100f))

			val snapshot1 = aggregator.snapshot()
			val snapshot2 = aggregator.snapshot()

			snapshot1.sessionDistanceM shouldBe snapshot2.sessionDistanceM
			snapshot1.sessionSteps shouldBe snapshot2.sessionSteps
			snapshot1.sampleCount shouldBe snapshot2.sampleCount
			aggregator.isActive.shouldBeTrue()
		}

		@Test
		fun `snapshot after signals is correct`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)

			aggregator.onSignal(movingSignal(
				timestampMs = currentTimeMs + 1000,
				distanceDeltaM = 100f,
				speedMps = 5f,
				stepDelta = 20,
				activityType = DetectedActivityType.WALKING
			))

			val snapshot = aggregator.snapshot()
			snapshot.sessionStartMs shouldBe currentTimeMs
			snapshot.lastUpdateMs shouldBe currentTimeMs + 1000
			snapshot.sessionDistanceM shouldBe 100f
			snapshot.sessionSteps shouldBe 20
			snapshot.currentSpeedMps shouldBe 5f
			snapshot.sampleCount shouldBe 1
			snapshot.dominantActivity shouldBe DetectedActivityType.WALKING
		}

		@Test
		fun `snapshot with no signals after start`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)

			val snapshot = aggregator.snapshot()
			snapshot.sessionStartMs shouldBe currentTimeMs
			snapshot.lastUpdateMs shouldBe currentTimeMs
			snapshot.sessionDistanceM shouldBe 0f
			snapshot.sessionSteps shouldBe 0
			snapshot.sessionDurationMs shouldBe 0L
			snapshot.currentSpeedMps.shouldBeNull()
			snapshot.avgSpeedMps shouldBe 0f
			snapshot.maxSpeedMps shouldBe 0f
			snapshot.sampleCount shouldBe 0
			snapshot.dominantActivity.shouldBeNull()
		}

		@Test
		fun `dominant activity is most frequent`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)

			aggregator.onSignal(movingSignal(currentTimeMs + 1000, activityType = DetectedActivityType.WALKING))
			aggregator.onSignal(movingSignal(currentTimeMs + 2000, activityType = DetectedActivityType.RUNNING))
			aggregator.onSignal(movingSignal(currentTimeMs + 3000, activityType = DetectedActivityType.RUNNING))
			aggregator.onSignal(movingSignal(currentTimeMs + 4000, activityType = DetectedActivityType.RUNNING))
			aggregator.onSignal(movingSignal(currentTimeMs + 5000, activityType = DetectedActivityType.WALKING))

			val snapshot = aggregator.snapshot()
			snapshot.dominantActivity shouldBe DetectedActivityType.RUNNING // 3 votes vs 2
		}
	}

	@Nested
	inner class EdgeCases {

		@Test
		fun `single signal session`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)

			aggregator.onSignal(movingSignal(
				timestampMs = currentTimeMs + 1000,
				distanceDeltaM = 50f,
				speedMps = 2.5f,
				stepDelta = 5
			))

			val snapshot = aggregator.stop()
			snapshot.sessionDistanceM shouldBe 50f
			snapshot.sessionSteps shouldBe 5
			snapshot.sessionDurationMs shouldBe 1000L // start to single signal
			snapshot.currentSpeedMps shouldBe 2.5f
			snapshot.sampleCount shouldBe 1
		}

		@Test
		fun `very long session with many signals`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)

			val signalCount = 1000
			repeat(signalCount) { i ->
				aggregator.onSignal(movingSignal(
					timestampMs = currentTimeMs + (i + 1) * 1000L,
					distanceDeltaM = 10f,
					speedMps = 5f,
					stepDelta = 2
				))
			}

			val snapshot = aggregator.snapshot()
			snapshot.sessionDistanceM shouldBe (10f * signalCount)
			snapshot.sessionSteps shouldBe (2 * signalCount)
			snapshot.sessionDurationMs shouldBe (signalCount * 1000L)
			snapshot.sampleCount shouldBe signalCount
		}

		@Test
		fun `zero distance signals`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)

			aggregator.onSignal(movingSignal(currentTimeMs + 1000, distanceDeltaM = 0f, speedMps = 0f))
			aggregator.onSignal(movingSignal(currentTimeMs + 2000, distanceDeltaM = 0f, speedMps = 0f))

			val snapshot = aggregator.snapshot()
			snapshot.sessionDistanceM shouldBe 0f
			snapshot.avgSpeedMps shouldBe 0f
			snapshot.maxSpeedMps shouldBe 0f
			snapshot.sampleCount shouldBe 2 // still counted as GPS samples
		}

		@Test
		fun `mixed null and non-null speed readings`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)

			aggregator.onSignal(movingSignal(currentTimeMs + 1000, speedMps = 5f))
			aggregator.onSignal(movingSignal(currentTimeMs + 2000, speedMps = null))
			aggregator.onSignal(movingSignal(currentTimeMs + 3000, speedMps = 10f))
			aggregator.onSignal(movingSignal(currentTimeMs + 4000, speedMps = null))
			aggregator.onSignal(movingSignal(currentTimeMs + 5000, speedMps = 8f))

			val snapshot = aggregator.snapshot()
			snapshot.currentSpeedMps shouldBe 8f // last non-null
			snapshot.avgSpeedMps shouldBe 100f // 500m (5 x default 100m) / 5s, independent of null speed readings
			snapshot.maxSpeedMps shouldBe 10f
		}

		@Test
		fun `rapid signals with same timestamp`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)

			val sameTime = currentTimeMs + 1000
			aggregator.onSignal(movingSignal(sameTime, distanceDeltaM = 10f, stepDelta = 2))
			aggregator.onSignal(movingSignal(sameTime, distanceDeltaM = 15f, stepDelta = 3))
			aggregator.onSignal(movingSignal(sameTime, distanceDeltaM = 20f, stepDelta = 4))

			val snapshot = aggregator.snapshot()
			snapshot.sessionDistanceM shouldBe 45f // all accumulated
			snapshot.sessionSteps shouldBe 9 // all accumulated
			snapshot.sessionDurationMs shouldBe 1000L // start to first signal, then no time progression
		}

		@Test
		fun `restored session continues from recovery time without double counting duration`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs - 60_000L)
			aggregator.restoreSessionTotals(
				distanceM = 250f,
				steps = 400,
				durationMs = 60_000L,
				sampleCount = 12,
				lastUpdateMs = currentTimeMs,
			)

			aggregator.onSignal(
				movingSignal(currentTimeMs + 1_000L, distanceDeltaM = 10f, stepDelta = 2),
			)

			val snapshot = aggregator.snapshot()
			snapshot.sessionDistanceM shouldBe 260f
			snapshot.sessionSteps shouldBe 402
			snapshot.sessionDurationMs shouldBe 61_000L
			snapshot.sampleCount shouldBe 13
		}

		@Test
		fun `day rollover resets day contribution but preserves full session`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)
			aggregator.seedDayTotals(100f, 20, 5_000L, 1)
			aggregator.onSignal(movingSignal(currentTimeMs + 1_000L, distanceDeltaM = 10f, stepDelta = 2))

			aggregator.rolloverDay()
			aggregator.onSignal(movingSignal(currentTimeMs + 2_000L, distanceDeltaM = 5f, stepDelta = 1))

			val snapshot = aggregator.snapshot()
			snapshot.sessionDistanceM shouldBe 15f
			snapshot.sessionSteps shouldBe 3
			snapshot.sessionDurationMs shouldBe 2_000L
			snapshot.dayTotalDistanceM shouldBe 5f
			snapshot.dayTotalSteps shouldBe 1
			snapshot.dayTotalDurationMs shouldBe 1_000L
		}

		@Test
		fun `signal without start throws IllegalStateException`() {
			val aggregator = createAggregator()
			assertThrows<IllegalStateException> {
				aggregator.onSignal(movingSignal(currentTimeMs))
			}
		}

		@Test
		fun `stop returns final snapshot with all accumulated data`() {
			val aggregator = createAggregator()
			aggregator.start(currentTimeMs)
			aggregator.seedDayTotals(distanceM = 1000f, steps = 200, durationMs = 30000, trips = 1)
			aggregator.onSignal(movingSignal(currentTimeMs + 1000, distanceDeltaM = 100f, stepDelta = 20))
			aggregator.onSignal(movingSignal(currentTimeMs + 3000, distanceDeltaM = 150f, stepDelta = 30))

			val finalSnapshot = aggregator.stop()

			finalSnapshot.sessionDistanceM shouldBe 250f
			finalSnapshot.sessionSteps shouldBe 50
			finalSnapshot.dayTotalDistanceM shouldBe 1250f // 1000 + 250
			finalSnapshot.dayTotalSteps shouldBe 250 // 200 + 50
			aggregator.isActive.shouldBeFalse()
		}
	}
}
