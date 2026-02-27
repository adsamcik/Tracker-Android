package com.adsamcik.tracker.stats.api.threshold

import com.adsamcik.tracker.stats.api.DetectedActivityType
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ThresholdTest {

	// ── ActivityTypeMapping ────────────────────────────────────────────

	@Nested
	inner class FromPlayServicesCode {

		@Test
		fun `code 0 maps to IN_VEHICLE`() {
			ActivityTypeMapping.fromPlayServicesCode(0) shouldBe DetectedActivityType.IN_VEHICLE
		}

		@Test
		fun `code 1 maps to ON_BICYCLE`() {
			ActivityTypeMapping.fromPlayServicesCode(1) shouldBe DetectedActivityType.ON_BICYCLE
		}

		@Test
		fun `code 2 maps to ON_FOOT`() {
			ActivityTypeMapping.fromPlayServicesCode(2) shouldBe DetectedActivityType.ON_FOOT
		}

		@Test
		fun `code 3 maps to STILL`() {
			ActivityTypeMapping.fromPlayServicesCode(3) shouldBe DetectedActivityType.STILL
		}

		@Test
		fun `code 5 maps to TILTING`() {
			ActivityTypeMapping.fromPlayServicesCode(5) shouldBe DetectedActivityType.TILTING
		}

		@Test
		fun `code 7 maps to WALKING`() {
			ActivityTypeMapping.fromPlayServicesCode(7) shouldBe DetectedActivityType.WALKING
		}

		@Test
		fun `code 8 maps to RUNNING`() {
			ActivityTypeMapping.fromPlayServicesCode(8) shouldBe DetectedActivityType.RUNNING
		}

		@Test
		fun `unknown code maps to UNKNOWN`() {
			ActivityTypeMapping.fromPlayServicesCode(99) shouldBe DetectedActivityType.UNKNOWN
		}

		@Test
		fun `negative code maps to UNKNOWN`() {
			ActivityTypeMapping.fromPlayServicesCode(-1) shouldBe DetectedActivityType.UNKNOWN
		}
	}

	@Nested
	inner class ToPlayServicesCode {

		@Test
		fun `IN_VEHICLE maps to 0`() {
			ActivityTypeMapping.toPlayServicesCode(DetectedActivityType.IN_VEHICLE) shouldBe 0
		}

		@Test
		fun `STILL maps to 3`() {
			ActivityTypeMapping.toPlayServicesCode(DetectedActivityType.STILL) shouldBe 3
		}

		@Test
		fun `WALKING maps to 7`() {
			ActivityTypeMapping.toPlayServicesCode(DetectedActivityType.WALKING) shouldBe 7
		}

		@Test
		fun `UNKNOWN maps to 4`() {
			ActivityTypeMapping.toPlayServicesCode(DetectedActivityType.UNKNOWN) shouldBe 4
		}
	}

	@Nested
	inner class RoundTrip {

		@Test
		fun `all known types survive round-trip`() {
			val knownTypes = listOf(
				DetectedActivityType.IN_VEHICLE,
				DetectedActivityType.ON_BICYCLE,
				DetectedActivityType.ON_FOOT,
				DetectedActivityType.STILL,
				DetectedActivityType.TILTING,
				DetectedActivityType.WALKING,
				DetectedActivityType.RUNNING,
			)
			knownTypes.forEach { type ->
				val code = ActivityTypeMapping.toPlayServicesCode(type)
				val decoded = ActivityTypeMapping.fromPlayServicesCode(code)
				decoded shouldBe type
			}
		}
	}

	// ── SpeedThresholds ────────────────────────────────────────────────

	@Nested
	inner class SpeedThresholdValues {

		@Test
		fun `STILLNESS is below MAX_WALK`() {
			SpeedThresholds.STILLNESS shouldBeLessThan SpeedThresholds.MAX_WALK
		}

		@Test
		fun `MAX_WALK is below MAX_RUN`() {
			SpeedThresholds.MAX_WALK shouldBeLessThan SpeedThresholds.MAX_RUN
		}

		@Test
		fun `MAX_ON_FOOT is below MAX_RUN`() {
			SpeedThresholds.MAX_ON_FOOT shouldBeLessThan SpeedThresholds.MAX_RUN
		}

		@Test
		fun `MIN_CYCLE is below MAX_CYCLE`() {
			SpeedThresholds.MIN_CYCLE shouldBeLessThan SpeedThresholds.MAX_CYCLE
		}

		@Test
		fun `MAX_CYCLE is below MIN_DEFINITE_VEHICLE`() {
			SpeedThresholds.MAX_CYCLE shouldBeLessThan SpeedThresholds.MIN_DEFINITE_VEHICLE
		}

		@Test
		fun `MIN_DEFINITE_VEHICLE is below MIN_HIGH_SPEED_RAIL`() {
			SpeedThresholds.MIN_DEFINITE_VEHICLE shouldBeLessThan SpeedThresholds.MIN_HIGH_SPEED_RAIL
		}

		@Test
		fun `STILLNESS threshold is 0_3 m per s`() {
			SpeedThresholds.STILLNESS.raw shouldBe 0.3f
		}

		@Test
		fun `MIN_HIGH_SPEED_RAIL is 50 m per s`() {
			SpeedThresholds.MIN_HIGH_SPEED_RAIL.raw shouldBe 50.0f
		}

		@Test
		fun `all thresholds are positive`() {
			val all = listOf(
				SpeedThresholds.MAX_WALK,
				SpeedThresholds.MAX_RUN,
				SpeedThresholds.MIN_CYCLE,
				SpeedThresholds.MAX_CYCLE,
				SpeedThresholds.MIN_DEFINITE_VEHICLE,
				SpeedThresholds.MAX_ON_FOOT,
				SpeedThresholds.STILLNESS,
				SpeedThresholds.MIN_HIGH_SPEED_RAIL,
			)
			all.forEach { threshold ->
				threshold shouldBeGreaterThan com.adsamcik.tracker.stats.api.value.SpeedMps.ZERO
			}
		}
	}
}
