package com.adsamcik.tracker.stats.api.signal

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.value.ActivityConfidence
import com.adsamcik.tracker.stats.api.value.CoordinateE7
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import com.adsamcik.tracker.stats.api.value.SpeedMps
import com.adsamcik.tracker.stats.api.value.StepCount
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TrackingSignalTest {

	private val ts = EpochMs(1_700_000_000_000L)
	private val coord = CoordinateE7(LatE7(487_000_000), LonE7(163_000_000))

	@Nested
	inner class TrackingSignalConstruction {

		@Test
		fun `minimal signal with only timestamp`() {
			val signal = TrackingSignal(timestampMs = ts)
			signal.timestampMs shouldBe ts
			signal.location.shouldBeNull()
			signal.activity.shouldBeNull()
			signal.steps.shouldBeNull()
		}

		@Test
		fun `signal with all sub-signals`() {
			val signal = TrackingSignal(
				timestampMs = ts,
				location = LocationSignal(coord, 5.0f, SpeedMps(1.5f)),
				activity = ActivitySignal(DetectedActivityType.WALKING, ActivityConfidence(85)),
				steps = StepSignal(StepCount(12), 50_000L),
			)
			signal.location!!.coordinate shouldBe coord
			signal.activity!!.type shouldBe DetectedActivityType.WALKING
			signal.steps!!.stepDelta.raw shouldBe 12
		}
	}

	@Nested
	inner class LocationSignalTest {

		@Test
		fun `preserves required fields`() {
			val loc = LocationSignal(coord, horizontalAccuracyM = 3.5f, speed = SpeedMps(2.0f))
			loc.coordinate shouldBe coord
			loc.horizontalAccuracyM shouldBe 3.5f
			loc.speed!!.raw shouldBe 2.0f
		}

		@Test
		fun `optional fields default to null`() {
			val loc = LocationSignal(coord, horizontalAccuracyM = 10f, speed = null)
			loc.speed.shouldBeNull()
			loc.altitudeM.shouldBeNull()
			loc.distanceDelta.shouldBeNull()
		}

		@Test
		fun `preserves altitude and distance delta`() {
			val loc = LocationSignal(
				coord,
				horizontalAccuracyM = 5f,
				speed = SpeedMps(1f),
				altitudeM = 250f,
				distanceDelta = DistanceM(15f),
			)
			loc.altitudeM shouldBe 250f
			loc.distanceDelta!!.raw shouldBe 15f
		}

		@Test
		fun `equality with same values`() {
			val loc1 = LocationSignal(coord, 5f, SpeedMps(1f))
			val loc2 = LocationSignal(coord, 5f, SpeedMps(1f))
			loc1 shouldBe loc2
		}
	}

	@Nested
	inner class ActivitySignalTest {

		@Test
		fun `preserves type and confidence`() {
			val sig = ActivitySignal(DetectedActivityType.RUNNING, ActivityConfidence(92))
			sig.type shouldBe DetectedActivityType.RUNNING
			sig.confidence.raw shouldBe 92
		}

		@Test
		fun `equality`() {
			val s1 = ActivitySignal(DetectedActivityType.STILL, ActivityConfidence.MAX)
			val s2 = ActivitySignal(DetectedActivityType.STILL, ActivityConfidence.MAX)
			s1 shouldBe s2
		}
	}

	@Nested
	inner class StepSignalTest {

		@Test
		fun `preserves step delta and total`() {
			val sig = StepSignal(StepCount(25), 100_000L)
			sig.stepDelta.raw shouldBe 25
			sig.totalStepsSinceBoot shouldBe 100_000L
		}

		@Test
		fun `equality`() {
			StepSignal(StepCount(5), 100L) shouldBe StepSignal(StepCount(5), 100L)
		}
	}
}
