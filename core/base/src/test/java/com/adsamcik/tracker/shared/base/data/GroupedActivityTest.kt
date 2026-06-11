package com.adsamcik.tracker.shared.base.data

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("GroupedActivity - activity grouping enum")
class GroupedActivityTest {

	@Nested
	@DisplayName("Enum entries")
	inner class EnumEntries {
		@Test
		fun `has 4 entries`() {
			GroupedActivity.entries.size shouldBe 4
		}

		@Test
		fun `contains STILL, ON_FOOT, IN_VEHICLE, UNKNOWN`() {
			GroupedActivity.entries.map { it.name } shouldBe
					listOf("STILL", "ON_FOOT", "IN_VEHICLE", "UNKNOWN")
		}
	}

	@Nested
	@DisplayName("isStillOrUnknown property")
	inner class IsStillOrUnknown {
		@Test
		fun `STILL is still or unknown`() {
			GroupedActivity.STILL.isStillOrUnknown shouldBe true
		}

		@Test
		fun `UNKNOWN is still or unknown`() {
			GroupedActivity.UNKNOWN.isStillOrUnknown shouldBe true
		}

		@Test
		fun `ON_FOOT is not still or unknown`() {
			GroupedActivity.ON_FOOT.isStillOrUnknown shouldBe false
		}

		@Test
		fun `IN_VEHICLE is not still or unknown`() {
			GroupedActivity.IN_VEHICLE.isStillOrUnknown shouldBe false
		}
	}

	@Nested
	@DisplayName("isKnownMovement property")
	inner class IsKnownMovement {
		@Test
		fun `ON_FOOT is known movement`() {
			GroupedActivity.ON_FOOT.isKnownMovement shouldBe true
		}

		@Test
		fun `IN_VEHICLE is known movement`() {
			GroupedActivity.IN_VEHICLE.isKnownMovement shouldBe true
		}

		@Test
		fun `STILL is not known movement`() {
			GroupedActivity.STILL.isKnownMovement shouldBe false
		}

		@Test
		fun `UNKNOWN is not known movement`() {
			GroupedActivity.UNKNOWN.isKnownMovement shouldBe false
		}
	}

	@Nested
	@DisplayName("isStillOrUnknown and isKnownMovement are mutually exclusive")
	inner class MutuallyExclusive {
		@Test
		fun `no activity is both still-or-unknown AND known-movement`() {
			GroupedActivity.entries.forEach { activity ->
				val bothTrue = activity.isStillOrUnknown && activity.isKnownMovement
				bothTrue shouldBe false
			}
		}
	}
}
