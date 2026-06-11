package com.adsamcik.tracker.shared.base.database.converter

import com.adsamcik.tracker.shared.base.data.DetectedActivity
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("DetectedActivityTypeConverter - DetectedActivity enum to/from Int")
class DetectedActivityTypeConverterTest {

	private val converter = DetectedActivityTypeConverter()

	@Nested
	@DisplayName("fromDetectedActivity")
	inner class FromDetectedActivity {
		@Test
		fun `STILL returns correct int`() {
			converter.fromDetectedActivity(DetectedActivity.STILL) shouldBe DetectedActivity.STILL.value
		}

		@Test
		fun `RUNNING returns correct int`() {
			converter.fromDetectedActivity(DetectedActivity.RUNNING) shouldBe DetectedActivity.RUNNING.value
		}

		@Test
		fun `UNKNOWN returns correct int`() {
			converter.fromDetectedActivity(DetectedActivity.UNKNOWN) shouldBe DetectedActivity.UNKNOWN.value
		}
	}

	@Nested
	@DisplayName("toDetectedActivity")
	inner class ToDetectedActivity {
		@Test
		fun `valid int returns STILL`() {
			converter.toDetectedActivity(DetectedActivity.STILL.value) shouldBe DetectedActivity.STILL
		}

		@Test
		fun `valid int returns IN_VEHICLE`() {
			converter.toDetectedActivity(DetectedActivity.IN_VEHICLE.value) shouldBe DetectedActivity.IN_VEHICLE
		}

		@Test
		fun `valid int returns WALKING`() {
			converter.toDetectedActivity(DetectedActivity.WALKING.value) shouldBe DetectedActivity.WALKING
		}

		@Test
		fun `invalid int throws`() {
			assertThrows<IllegalArgumentException> {
				converter.toDetectedActivity(-999)
			}
		}
	}

	@Nested
	@DisplayName("Round-trip consistency")
	inner class RoundTrip {
		@Test
		fun `all enum values survive round-trip`() {
			DetectedActivity.entries.forEach { activity ->
				val serialized = converter.fromDetectedActivity(activity)
				val deserialized = converter.toDetectedActivity(serialized)
				deserialized shouldBe activity
			}
		}
	}
}
