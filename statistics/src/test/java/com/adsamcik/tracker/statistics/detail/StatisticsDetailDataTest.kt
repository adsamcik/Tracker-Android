package com.adsamcik.tracker.statistics.detail

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("StatisticsDetailData")
class StatisticsDetailDataTest {

	@Nested
	@DisplayName("InformationStatisticsData")
	inner class InformationTests {

		@Test
		fun `stores all fields`() {
			val data = InformationStatisticsData(
				titleRes = 1,
				iconRes = 2,
				value = "42 km"
			)
			data.titleRes shouldBe 1
			data.iconRes shouldBe 2
			data.value shouldBe "42 km"
		}

		@Test
		fun `is StatisticsDetailData`() {
			val data: StatisticsDetailData = InformationStatisticsData(
				titleRes = 0,
				iconRes = 0,
				value = ""
			)
			data.shouldBeInstanceOf<InformationStatisticsData>()
		}

		@Test
		fun `equality works`() {
			val d1 = InformationStatisticsData(1, 2, "val")
			val d2 = InformationStatisticsData(1, 2, "val")
			d1 shouldBe d2
			d1.hashCode() shouldBe d2.hashCode()
		}

		@Test
		fun `inequality for different values`() {
			val d1 = InformationStatisticsData(1, 2, "a")
			val d2 = InformationStatisticsData(1, 2, "b")
			d1 shouldNotBe d2
		}

		@Test
		fun `copy works`() {
			val orig = InformationStatisticsData(1, 2, "old")
			val copy = orig.copy(value = "new")
			copy.value shouldBe "new"
			copy.titleRes shouldBe 1
		}
	}

	@Nested
	@DisplayName("LineChartStatisticsData")
	inner class LineChartTests {

		@Test
		fun `stores all fields`() {
			val data = LineChartStatisticsData(
				titleRes = 10,
				values = listOf(1.0f, 2.0f, 3.0f)
			)
			data.titleRes shouldBe 10
			data.values shouldBe listOf(1.0f, 2.0f, 3.0f)
		}

		@Test
		fun `is StatisticsDetailData`() {
			val data: StatisticsDetailData = LineChartStatisticsData(0, emptyList())
			data.shouldBeInstanceOf<LineChartStatisticsData>()
		}

		@Test
		fun `empty values list`() {
			val data = LineChartStatisticsData(titleRes = 0, values = emptyList())
			data.values shouldBe emptyList()
		}
	}

	@Nested
	@DisplayName("MapStatisticsData")
	inner class MapTests {

		@Test
		fun `stores all fields`() {
			val data = MapStatisticsData(
				bounds = "some-bounds",
				locations = listOf("loc1", "loc2")
			)
			data.bounds shouldBe "some-bounds"
			data.locations shouldBe listOf("loc1", "loc2")
		}

		@Test
		fun `is StatisticsDetailData`() {
			val data: StatisticsDetailData = MapStatisticsData(null, emptyList())
			data.shouldBeInstanceOf<MapStatisticsData>()
		}

		@Test
		fun `null bounds is valid`() {
			val data = MapStatisticsData(bounds = null, locations = emptyList())
			data.bounds shouldBe null
		}
	}

	@Nested
	@DisplayName("Sealed hierarchy")
	inner class SealedHierarchyTests {

		@Test
		fun `when expression covers all subtypes`() {
			val items: List<StatisticsDetailData> = listOf(
				InformationStatisticsData(1, 2, "v"),
				LineChartStatisticsData(3, listOf(1f)),
				MapStatisticsData(null, emptyList()),
			)
			val labels = items.map { data ->
				when (data) {
					is InformationStatisticsData -> "info"
					is LineChartStatisticsData -> "chart"
					is MapStatisticsData -> "map"
				}
			}
			labels shouldBe listOf("info", "chart", "map")
		}
	}
}
