package com.adsamcik.tracker.points

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PointLog")
class PointLogTest {

	@Nested
	inner class PointsLogSource {
		@Test
		fun `POINTS_LOG_SOURCE equals points`() {
			POINTS_LOG_SOURCE shouldBe "points"
		}

		@Test
		fun `POINTS_LOG_SOURCE is not blank`() {
			POINTS_LOG_SOURCE.shouldNotBeBlank()
		}
	}
}
