package com.adsamcik.tracker.statistics.ui

import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("formatPace")
class FormatPaceTest {

	@Nested
	@DisplayName("edge cases")
	inner class EdgeCases {
		@Test
		fun `returns dash for zero distance`() {
			formatPace(0.0, 60_000L, LengthSystem.Metric) shouldBe "—"
		}

		@Test
		fun `returns dash for negative distance`() {
			formatPace(-100.0, 60_000L, LengthSystem.Metric) shouldBe "—"
		}

		@Test
		fun `returns dash for zero duration`() {
			formatPace(1000.0, 0L, LengthSystem.Metric) shouldBe "—"
		}

		@Test
		fun `returns sub-second pace when totalSeconds rounds to zero`() {
			// Very fast: 1000m in 400ms → pace per km ≈ 0.4s → rounds to 0
			formatPace(1000.0, 400L, LengthSystem.Metric) shouldBe "< 0:01 / km"
		}

		@Test
		fun `returns sub-second pace imperial when totalSeconds rounds to zero`() {
			// Very fast: 1609.344m (1 mile) in 400ms → pace per mi ≈ 0.4s → rounds to 0
			formatPace(1609.344, 400L, LengthSystem.Imperial) shouldBe "< 0:01 / mi"
		}
	}

	@Nested
	@DisplayName("normal cases")
	inner class NormalCases {
		@Test
		fun `formats metric pace correctly`() {
			// 1000m in 5 minutes (300_000ms) → 5:00 / km
			formatPace(1000.0, 300_000L, LengthSystem.Metric) shouldBe "5:00 / km"
		}

		@Test
		fun `formats imperial pace correctly`() {
			// 1609.344m (1 mile) in 8 min 30 sec (510_000ms) → 8:30 / mi
			formatPace(1609.344, 510_000L, LengthSystem.Imperial) shouldBe "8:30 / mi"
		}

		@Test
		fun `formats pace with partial km`() {
			// 500m in 150_000ms (2.5 min) → pace = 150s per 0.5km = 300s per km = 5:00 / km
			formatPace(500.0, 150_000L, LengthSystem.Metric) shouldBe "5:00 / km"
		}
	}
}
