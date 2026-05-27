package com.adsamcik.tracker.stats.api.metric

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TimeWindowTest {

	@Test
	fun `interval supports equality`() {
		val first = TimeWindow.Interval(startMs = 1_000L, endMs = 5_000L)
		val second = TimeWindow.Interval(startMs = 1_000L, endMs = 5_000L)

		first shouldBe second
	}

	@Test
	fun `interval copy updates start only`() {
		val original = TimeWindow.Interval(startMs = 1_000L, endMs = 5_000L)
		val copied = original.copy(startMs = 2_000L)

		copied shouldBe TimeWindow.Interval(startMs = 2_000L, endMs = 5_000L)
	}

	@Test
	fun `rolling supports equality`() {
		val first = TimeWindow.Rolling(durationMs = 86_400_000L)
		val second = TimeWindow.Rolling(durationMs = 86_400_000L)

		first shouldBe second
	}

	@Test
	fun `cumulative is singleton`() {
		TimeWindow.Cumulative shouldBe TimeWindow.Cumulative
	}
}
