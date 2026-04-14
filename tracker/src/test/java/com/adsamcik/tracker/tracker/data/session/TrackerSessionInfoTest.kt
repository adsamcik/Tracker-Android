package com.adsamcik.tracker.tracker.data.session

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TrackerSessionInfo")
class TrackerSessionInfoTest {

	@Nested
	@DisplayName("Construction")
	inner class Construction {

		@Test
		fun `user-initiated session preserves flag`() {
			val info = TrackerSessionInfo(isInitiatedByUser = true)
			info.isInitiatedByUser shouldBe true
		}

		@Test
		fun `non-user-initiated session preserves flag`() {
			val info = TrackerSessionInfo(isInitiatedByUser = false)
			info.isInitiatedByUser shouldBe false
		}
	}

	@Nested
	@DisplayName("Data class")
	inner class DataClass {

		@Test
		fun `two instances with same value are equal`() {
			val a = TrackerSessionInfo(isInitiatedByUser = true)
			val b = TrackerSessionInfo(isInitiatedByUser = true)
			a shouldBe b
		}

		@Test
		fun `describeContents returns 0`() {
			val info = TrackerSessionInfo(isInitiatedByUser = true)
			info.describeContents() shouldBe 0
		}
	}
}
