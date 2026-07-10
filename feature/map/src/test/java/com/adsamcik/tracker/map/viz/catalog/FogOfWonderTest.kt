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
 * Tests for Fog-of-Wonder: the quality-driven aggregator (quality tier -> tile weight) and its
 * alpha-encoded reveal ramp, plus the pipeline id.
 */
@DisplayName("Fog-of-Wonder")
class FogOfWonderTest {

	private val ctx = AggContext(zoom = 12f, quality = 1f, maxPoints = 20_000)

	private fun cell(quality: Int) = ExplorationCellFeature(
		lat = 50.0, lon = 14.0, level = 14, seasonBitmask = 0, quality = quality,
		firstDiscoveredAt = 0L, visitCount = 1,
	)

	@Nested
	@DisplayName("QualityFogAggregator")
	inner class Aggregation {

		@Test
		fun `maps quality tier to a normalised tile weight`() {
			val tiles = QualityFogAggregator().aggregate(
				listOf(cell(0), cell(2), cell(4)), ctx,
			).tiles
			tiles shouldHaveSize 3
			tiles[0].weight shouldBe (0.0 plusOrMinus 1e-9)
			tiles[1].weight shouldBe (0.5 plusOrMinus 1e-9)
			tiles[2].weight shouldBe (1.0 plusOrMinus 1e-9)
		}

		@Test
		fun `renders every explored cell including passed-through ones`() {
			// Unlike the seasonal overlay, fog reveals even the faintest contact.
			QualityFogAggregator().aggregate(listOf(cell(0)), ctx).tiles shouldHaveSize 1
		}

		@Test
		fun `builds a non-degenerate square around the cell centre`() {
			val t = QualityFogAggregator().aggregate(listOf(cell(3)), ctx).tiles.single()
			(t.east > t.west) shouldBe true
			(t.north > t.south) shouldBe true
		}
	}

	@Test
	fun `ramp alpha rises monotonically so deeper exploration reads stronger`() {
		val alphas = FOG_RAMP.map { (it.second ushr 24) and 0xFF }
		alphas.zipWithNext().all { (a, b) -> b > a } shouldBe true
	}

	@Test
	fun `fog pipeline has the expected id`() {
		fogOfWonder(mockk()).id shouldBe "fog_of_wonder"
	}
}
