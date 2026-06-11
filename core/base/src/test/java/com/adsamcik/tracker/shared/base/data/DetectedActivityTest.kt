package com.adsamcik.tracker.shared.base.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("DetectedActivity - activity type enum")
class DetectedActivityTest {

	@Nested
	@DisplayName("Enum entries")
	inner class EnumEntries {
		@Test
		fun `has 8 entries`() {
			DetectedActivity.entries.size shouldBe 8
		}

		@Test
		fun `each entry has a unique value`() {
			val values = DetectedActivity.entries.map { it.value }
			values.distinct().size shouldBe values.size
		}
	}

	@Nested
	@DisplayName("Grouped activity mapping")
	inner class GroupedActivityMapping {
		@Test
		fun `STILL maps to GroupedActivity STILL`() {
			DetectedActivity.STILL.groupedActivity shouldBe GroupedActivity.STILL
		}

		@Test
		fun `RUNNING maps to ON_FOOT`() {
			DetectedActivity.RUNNING.groupedActivity shouldBe GroupedActivity.ON_FOOT
		}

		@Test
		fun `ON_FOOT maps to ON_FOOT`() {
			DetectedActivity.ON_FOOT.groupedActivity shouldBe GroupedActivity.ON_FOOT
		}

		@Test
		fun `WALKING maps to ON_FOOT`() {
			DetectedActivity.WALKING.groupedActivity shouldBe GroupedActivity.ON_FOOT
		}

		@Test
		fun `ON_BICYCLE maps to IN_VEHICLE`() {
			DetectedActivity.ON_BICYCLE.groupedActivity shouldBe GroupedActivity.IN_VEHICLE
		}

		@Test
		fun `IN_VEHICLE maps to IN_VEHICLE`() {
			DetectedActivity.IN_VEHICLE.groupedActivity shouldBe GroupedActivity.IN_VEHICLE
		}

		@Test
		fun `TILTING maps to UNKNOWN`() {
			DetectedActivity.TILTING.groupedActivity shouldBe GroupedActivity.UNKNOWN
		}

		@Test
		fun `UNKNOWN maps to UNKNOWN`() {
			DetectedActivity.UNKNOWN.groupedActivity shouldBe GroupedActivity.UNKNOWN
		}
	}

	@Nested
	@DisplayName("fromDetectedType companion function")
	inner class FromDetectedType {
		@Test
		fun `resolves each known type value`() {
			DetectedActivity.entries.forEach { expected ->
				val result = DetectedActivity.fromDetectedType(expected.value)
				result shouldBe expected
			}
		}

		@Test
		fun `throws for unknown type value`() {
			assertThrows<IllegalArgumentException> {
				DetectedActivity.fromDetectedType(-999)
			}
		}

		@Test
		fun `error message includes the invalid value`() {
			val ex = assertThrows<IllegalArgumentException> {
				DetectedActivity.fromDetectedType(42)
			}
			ex.message shouldNotBe null
		}
	}
}
