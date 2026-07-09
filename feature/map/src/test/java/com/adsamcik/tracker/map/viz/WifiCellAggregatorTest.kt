package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.WeightedGeoFeature
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [WifiCellAggregator] — the Wi-Fi-specific grid aggregation preserved verbatim from the
 * old Wi-Fi layers (custom cell size, average weight, top-N cap, optional viewport-relative
 * count normalisation).
 */
@DisplayName("WifiCellAggregator")
class WifiCellAggregatorTest {

	private fun feature(lat: Double, lon: Double, weight: Double) =
		WeightedGeoFeature(lat, lon, 0L, weight)

	private fun ctx(zoom: Float = 17f, quality: Float = 1f, maxPoints: Int = 1000) =
		AggContext(zoom, quality, maxPoints)

	@Test
	fun `empty input yields no cells`() {
		WifiCellAggregator(0.00045, normalizeByMax = false).aggregate(emptyList(), ctx()).cells shouldHaveSize 0
	}

	@Test
	fun `averages signal weight within a cell`() {
		val cells = WifiCellAggregator(0.00045, normalizeByMax = false).aggregate(
			listOf(feature(50.0, 14.0, 0.2), feature(50.0, 14.0, 0.6)),
			ctx(),
		).cells
		cells shouldHaveSize 1
		cells.single().weight shouldBe (0.4 plusOrMinus 1e-9)
	}

	@Test
	fun `normalizeByMax divides by max coerced to at least one`() {
		// Faithful to the original layer: the denominator is max(cellWeight).coerceAtLeast(1.0).
		// Per-cell weights are already in [0, 1], so the denominator is 1.0 and weights are left
		// unchanged — the viewport-relative "normalisation" is effectively a passthrough here.
		val cells = WifiCellAggregator(0.00045, normalizeByMax = true).aggregate(
			listOf(feature(50.0, 14.0, 0.25), feature(60.0, 24.0, 0.5)),
			ctx(),
		).cells.sortedBy { it.weight }
		cells shouldHaveSize 2
		cells.first().weight shouldBe (0.25 plusOrMinus 1e-9)
		cells.last().weight shouldBe (0.5 plusOrMinus 1e-9)
	}

	@Test
	fun `keeps only the heaviest cells when over budget`() {
		val many = (1..40).map { feature(50.0 + it * 0.05, 14.0, it / 40.0) }
		WifiCellAggregator(0.00045, normalizeByMax = false)
			.aggregate(many, ctx(maxPoints = 10)).cells shouldHaveSize 10
	}
}
