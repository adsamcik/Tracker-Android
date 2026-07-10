package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.viz.AggContext
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for First Contact: the recency weight (fixed-window, clamped) and the recency-driven
 * aggregator, plus the pipeline id.
 */
@DisplayName("First Contact")
class FirstContactTest {

	private val now = 1_000_000_000_000L
	private val ctx = AggContext(zoom = 12f, quality = 1f, maxPoints = 20_000)

	private fun cell(firstDiscoveredAt: Long) = ExplorationCellFeature(
		lat = 50.0, lon = 14.0, level = 14, seasonBitmask = 0, quality = 2,
		firstDiscoveredAt = firstDiscoveredAt, visitCount = 1,
	)

	@Nested
	@DisplayName("recencyWeight")
	inner class Recency {

		@Test
		fun `a discovery right now weighs one`() {
			recencyWeight(firstDiscoveredAt = now, now = now) shouldBe (1.0 plusOrMinus 1e-9)
		}

		@Test
		fun `a discovery at the window edge weighs zero`() {
			recencyWeight(firstDiscoveredAt = now - RECENCY_WINDOW_MS, now = now) shouldBe (0.0 plusOrMinus 1e-9)
		}

		@Test
		fun `a discovery halfway through the window weighs one half`() {
			recencyWeight(firstDiscoveredAt = now - RECENCY_WINDOW_MS / 2, now = now) shouldBe (0.5 plusOrMinus 1e-6)
		}

		@Test
		fun `anything older than the window clamps to zero`() {
			recencyWeight(firstDiscoveredAt = now - RECENCY_WINDOW_MS * 3, now = now) shouldBe (0.0 plusOrMinus 1e-9)
		}

		@Test
		fun `a future timestamp clamps to one rather than overshooting`() {
			recencyWeight(firstDiscoveredAt = now + 10_000L, now = now) shouldBe (1.0 plusOrMinus 1e-9)
		}
	}

	@Nested
	@DisplayName("FirstContactAggregator")
	inner class Aggregation {

		@Test
		fun `colours recent ground hotter than old ground`() {
			val recent = cell(now)
			val old = cell(now - RECENCY_WINDOW_MS)
			val tiles = FirstContactAggregator(nowProvider = { now }).aggregate(listOf(old, recent), ctx).tiles
			tiles shouldHaveSize 2
			(tiles[1].weight > tiles[0].weight) shouldBe true
			tiles[0].weight shouldBe (0.0 plusOrMinus 1e-9)
			tiles[1].weight shouldBe (1.0 plusOrMinus 1e-9)
		}
	}

	@Test
	fun `first contact pipeline has the expected id`() {
		firstContact(mockk()).id shouldBe "first_contact"
	}
}
