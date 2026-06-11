package com.adsamcik.tracker.statistics.detail

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("StatisticDisplayType")
class StatisticDisplayTypeTest {

	@Nested
	@DisplayName("Enum values")
	inner class EnumValues {

		@Test
		fun `has all expected values`() {
			StatisticDisplayType.entries.map { it.name } shouldBe listOf(
				"INFORMATION", "LINE_CHART", "MAP"
			)
		}

		@Test
		fun `valueOf resolves correctly`() {
			StatisticDisplayType.valueOf("INFORMATION") shouldBe StatisticDisplayType.INFORMATION
			StatisticDisplayType.valueOf("LINE_CHART") shouldBe StatisticDisplayType.LINE_CHART
			StatisticDisplayType.valueOf("MAP") shouldBe StatisticDisplayType.MAP
		}

		@Test
		fun `ordinal values are sequential`() {
			StatisticDisplayType.INFORMATION.ordinal shouldBe 0
			StatisticDisplayType.LINE_CHART.ordinal shouldBe 1
			StatisticDisplayType.MAP.ordinal shouldBe 2
		}

		@Test
		fun `entries has correct size`() {
			StatisticDisplayType.entries.size shouldBe 3
		}
	}
}
