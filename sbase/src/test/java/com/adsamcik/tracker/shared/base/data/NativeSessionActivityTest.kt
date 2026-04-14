package com.adsamcik.tracker.shared.base.data

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("NativeSessionActivity - built-in activity types enum")
class NativeSessionActivityTest {

	@Nested
	@DisplayName("Enum entries")
	inner class EnumEntries {
		@Test
		fun `has 8 entries`() {
			NativeSessionActivity.entries.size shouldBe 8
		}

		@Test
		fun `all IDs are negative`() {
			NativeSessionActivity.entries.forEach { activity ->
				(activity.id < 0) shouldBe true
			}
		}

		@Test
		fun `all IDs are unique`() {
			val ids = NativeSessionActivity.entries.map { it.id }
			ids.distinct().size shouldBe ids.size
		}
	}

	@Nested
	@DisplayName("Known IDs")
	inner class KnownIds {
		@Test
		fun `WALKING is minus 2`() {
			NativeSessionActivity.WALKING.id shouldBe -2L
		}

		@Test
		fun `RUNNING is minus 3`() {
			NativeSessionActivity.RUNNING.id shouldBe -3L
		}

		@Test
		fun `BICYCLE is minus 4`() {
			NativeSessionActivity.BICYCLE.id shouldBe -4L
		}

		@Test
		fun `VEHICLE is minus 5`() {
			NativeSessionActivity.VEHICLE.id shouldBe -5L
		}

		@Test
		fun `SLOPE_SPORTS is minus 22`() {
			NativeSessionActivity.SLOPE_SPORTS.id shouldBe -22L
		}

		@Test
		fun `WATER_VEHICLE is minus 26`() {
			NativeSessionActivity.WATER_VEHICLE.id shouldBe -26L
		}

		@Test
		fun `AIR_VEHICLE is minus 31`() {
			NativeSessionActivity.AIR_VEHICLE.id shouldBe -31L
		}

		@Test
		fun `LAND_VEHICLE is minus 34`() {
			NativeSessionActivity.LAND_VEHICLE.id shouldBe -34L
		}
	}

	@Nested
	@DisplayName("Icon names")
	inner class IconNames {
		@Test
		fun `all entries have non-empty icon names`() {
			NativeSessionActivity.entries.forEach { activity ->
				(activity.iconName.isNotEmpty()) shouldBe true
			}
		}
	}
}
