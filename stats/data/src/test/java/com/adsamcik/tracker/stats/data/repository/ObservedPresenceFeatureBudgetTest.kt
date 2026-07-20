package com.adsamcik.tracker.stats.data.repository

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ObservedPresenceFeatureBudgetTest {
	@Test
	fun `reports omitted mass without renormalizing included cells`() {
		val cells = listOf(
			Cell("hot", 5_000L),
			Cell("warm", 3_000L),
			Cell("cool", 2_000L),
		)

		val result = applyPresenceFeatureBudget(cells, maxFeatures = 2, expectedMs = Cell::expectedMs)

		result.included.map(Cell::id) shouldBe listOf("hot", "warm")
		result.included.sumOf(Cell::expectedMs) shouldBe 8_000L
		result.omittedMs shouldBe 2_000L
		result.truncated shouldBe true
	}

	@Test
	fun `complete result reports no omitted mass`() {
		val cells = listOf(Cell("only", 1_000L))

		val result = applyPresenceFeatureBudget(cells, maxFeatures = 2, expectedMs = Cell::expectedMs)

		result.included shouldBe cells
		result.omittedMs shouldBe 0L
		result.truncated shouldBe false
	}

	private data class Cell(val id: String, val expectedMs: Long)
}
