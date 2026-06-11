package com.adsamcik.tracker.map.layers.base

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("SupportsDateRange")
class SupportsDateRangeTest {

	private class TestDateRangeLayer : SupportsDateRange {
		override var dateRange: LongRange = LongRange.EMPTY
	}

	@Test
	fun `default implementation allows setting and getting date range`() {
		val layer = TestDateRangeLayer()
		layer.dateRange = 1000L..2000L
		layer.dateRange shouldBe 1000L..2000L
	}

	@Test
	fun `date range can be updated`() {
		val layer = TestDateRangeLayer()
		layer.dateRange = 100L..200L
		layer.dateRange shouldBe 100L..200L
		layer.dateRange = 300L..400L
		layer.dateRange shouldBe 300L..400L
	}

	@Test
	fun `empty range is valid`() {
		val layer = TestDateRangeLayer()
		layer.dateRange = LongRange.EMPTY
		layer.dateRange shouldBe LongRange.EMPTY
	}

	@Test
	fun `single value range`() {
		val layer = TestDateRangeLayer()
		layer.dateRange = 500L..500L
		layer.dateRange.first shouldBe 500L
		layer.dateRange.last shouldBe 500L
	}
}
