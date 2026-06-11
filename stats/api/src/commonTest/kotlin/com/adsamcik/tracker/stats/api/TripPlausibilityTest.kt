package com.adsamcik.tracker.stats.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TripPlausibilityTest {

	@Nested
	inner class WalkingTrips {
		@Test
		fun `normal walking trip is plausible`() {
			// 5 km in 1 hour = 5 km/h — well within walking range
			val result = TripPlausibility.evaluate(
				distanceM = 5_000f,
				durationMs = 3_600_000L,
				activityType = DetectedActivityType.WALKING,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Plausible>()
		}

		@Test
		fun `fast walk is still plausible`() {
			// 7 km in 1 hour = 7 km/h — brisk walk
			val result = TripPlausibility.evaluate(
				distanceM = 7_000f,
				durationMs = 3_600_000L,
				activityType = DetectedActivityType.WALKING,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Plausible>()
		}

		@Test
		fun `impossible walking speed is implausible`() {
			// 50 km in 1 hour = 50 km/h — impossible on foot
			val result = TripPlausibility.evaluate(
				distanceM = 50_000f,
				durationMs = 3_600_000L,
				activityType = DetectedActivityType.WALKING,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Implausible>()
		}

		@Test
		fun `on foot uses walking threshold`() {
			// ON_FOOT should use the same threshold as WALKING
			val result = TripPlausibility.evaluate(
				distanceM = 50_000f,
				durationMs = 3_600_000L,
				activityType = DetectedActivityType.ON_FOOT,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Implausible>()
		}
	}

	@Nested
	inner class RunningTrips {
		@Test
		fun `normal running trip is plausible`() {
			// 10 km in 1 hour = 10 km/h
			val result = TripPlausibility.evaluate(
				distanceM = 10_000f,
				durationMs = 3_600_000L,
				activityType = DetectedActivityType.RUNNING,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Plausible>()
		}

		@Test
		fun `sprint speed is plausible`() {
			// 400m in 43s = ~33.5 km/h — world class 400m
			val result = TripPlausibility.evaluate(
				distanceM = 400f,
				durationMs = 43_000L,
				activityType = DetectedActivityType.RUNNING,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Plausible>()
		}

		@Test
		fun `impossible running speed is implausible`() {
			// 100 km in 1 hour = 100 km/h — impossible running
			val result = TripPlausibility.evaluate(
				distanceM = 100_000f,
				durationMs = 3_600_000L,
				activityType = DetectedActivityType.RUNNING,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Implausible>()
		}
	}

	@Nested
	inner class CyclingTrips {
		@Test
		fun `normal cycling is plausible`() {
			// 30 km in 1 hour = 30 km/h
			val result = TripPlausibility.evaluate(
				distanceM = 30_000f,
				durationMs = 3_600_000L,
				activityType = DetectedActivityType.ON_BICYCLE,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Plausible>()
		}

		@Test
		fun `impossible cycling speed is implausible`() {
			// 200 km in 1 hour = 200 km/h
			val result = TripPlausibility.evaluate(
				distanceM = 200_000f,
				durationMs = 3_600_000L,
				activityType = DetectedActivityType.ON_BICYCLE,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Implausible>()
		}
	}

	@Nested
	inner class VehicleTrips {
		@Test
		fun `highway speed is plausible`() {
			// 130 km in 1 hour = 130 km/h
			val result = TripPlausibility.evaluate(
				distanceM = 130_000f,
				durationMs = 3_600_000L,
				activityType = DetectedActivityType.IN_VEHICLE,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Plausible>()
		}

		@Test
		fun `supersonic vehicle speed is implausible`() {
			// 1000 km in 1 hour = 1000 km/h — not ground vehicle
			val result = TripPlausibility.evaluate(
				distanceM = 1_000_000f,
				durationMs = 3_600_000L,
				activityType = DetectedActivityType.IN_VEHICLE,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Implausible>()
		}
	}

	@Nested
	inner class UnknownActivity {
		@Test
		fun `null activity uses generous default threshold`() {
			// 200 km in 1 hour = 200 km/h — within vehicle range with null activity
			val result = TripPlausibility.evaluate(
				distanceM = 200_000f,
				durationMs = 3_600_000L,
				activityType = null,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Plausible>()
		}

		@Test
		fun `extreme speed with null activity is implausible`() {
			// 9393 km in 2 min = ~280,000 km/h — the actual QC finding
			val result = TripPlausibility.evaluate(
				distanceM = 9_393_800f,
				durationMs = 121_000L,
				activityType = null,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Implausible>()
		}

		@Test
		fun `UNKNOWN activity type uses default threshold`() {
			val result = TripPlausibility.evaluate(
				distanceM = 9_393_800f,
				durationMs = 121_000L,
				activityType = DetectedActivityType.UNKNOWN,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Implausible>()
		}
	}

	@Nested
	inner class QcFindingReproduction {
		@Test
		fun `F02 case 1 - 9393km in 2m1s is implausible`() {
			val result = TripPlausibility.evaluate(
				distanceM = 9_393_800f,
				durationMs = 121_000L,
				activityType = null,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Implausible>()
			val implausible = result as PlausibilityResult.Implausible
			// ~77,634 m/s average speed
			(implausible.averageSpeedMps > 70_000f) shouldBe true
		}

		@Test
		fun `F02 case 2 - 5_4km in 41s is implausible`() {
			// 5.4 km in 41s = ~473 km/h
			val result = TripPlausibility.evaluate(
				distanceM = 5_400f,
				durationMs = 41_000L,
				activityType = null,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Implausible>()
		}
	}

	@Nested
	inner class EdgeCases {
		@Test
		fun `zero distance is always plausible`() {
			val result = TripPlausibility.evaluate(
				distanceM = 0f,
				durationMs = 3_600_000L,
				activityType = DetectedActivityType.WALKING,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Plausible>()
		}

		@Test
		fun `negative distance is always plausible`() {
			// Treat as no movement
			val result = TripPlausibility.evaluate(
				distanceM = -100f,
				durationMs = 3_600_000L,
				activityType = DetectedActivityType.WALKING,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Plausible>()
		}

		@Test
		fun `zero duration with positive distance is implausible`() {
			val result = TripPlausibility.evaluate(
				distanceM = 100f,
				durationMs = 0L,
				activityType = DetectedActivityType.WALKING,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Implausible>()
		}

		@Test
		fun `negative duration with positive distance is implausible`() {
			val result = TripPlausibility.evaluate(
				distanceM = 100f,
				durationMs = -1000L,
				activityType = DetectedActivityType.WALKING,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Implausible>()
		}

		@Test
		fun `zero duration with zero distance is plausible`() {
			val result = TripPlausibility.evaluate(
				distanceM = 0f,
				durationMs = 0L,
				activityType = DetectedActivityType.WALKING,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Plausible>()
		}

		@Test
		fun `very short trip with reasonable speed is plausible`() {
			// 10m in 5 seconds = 2 m/s = 7.2 km/h — reasonable walk
			val result = TripPlausibility.evaluate(
				distanceM = 10f,
				durationMs = 5_000L,
				activityType = DetectedActivityType.WALKING,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Plausible>()
		}

		@Test
		fun `NaN distance is plausible (treated as no movement)`() {
			val result = TripPlausibility.evaluate(
				distanceM = Float.NaN,
				durationMs = 60_000L,
				activityType = DetectedActivityType.WALKING,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Plausible>()
		}

		@Test
		fun `Infinity distance is implausible`() {
			val result = TripPlausibility.evaluate(
				distanceM = Float.POSITIVE_INFINITY,
				durationMs = 60_000L,
				activityType = DetectedActivityType.WALKING,
			)
			result.shouldBeInstanceOf<PlausibilityResult.Implausible>()
		}
	}

	@Nested
	inner class ThresholdAccess {
		@Test
		fun `maxSpeedMpsFor returns correct threshold for each activity`() {
			val walkThreshold = TripPlausibility.maxSpeedMpsFor(DetectedActivityType.WALKING)
			val runThreshold = TripPlausibility.maxSpeedMpsFor(DetectedActivityType.RUNNING)
			val bikeThreshold = TripPlausibility.maxSpeedMpsFor(DetectedActivityType.ON_BICYCLE)
			val vehicleThreshold = TripPlausibility.maxSpeedMpsFor(DetectedActivityType.IN_VEHICLE)

			// Walking < Running < Cycling < Vehicle
			(walkThreshold < runThreshold) shouldBe true
			(runThreshold < bikeThreshold) shouldBe true
			(bikeThreshold < vehicleThreshold) shouldBe true
		}

		@Test
		fun `null activity returns default threshold`() {
			val defaultThreshold = TripPlausibility.maxSpeedMpsFor(null)
			val vehicleThreshold = TripPlausibility.maxSpeedMpsFor(DetectedActivityType.IN_VEHICLE)
			// Default should be same as vehicle (most generous)
			(defaultThreshold) shouldBe vehicleThreshold
		}
	}
}
