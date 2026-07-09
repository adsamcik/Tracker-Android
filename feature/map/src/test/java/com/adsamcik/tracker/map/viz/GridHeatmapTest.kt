package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the shared grid-heatmap stages ([GridHeatmapAggregator], [CellWeighting], [HeatmapEncoder])
 * of the map-visualization engine. Exercises the value-vs-mass distinction and the acceptance
 * fixtures (empty, single, dense, duplicate-rate) at the aggregator level; source-side date/bounds
 * filtering is covered by the catalog source tests.
 */
@DisplayName("Grid heatmap stages")
class GridHeatmapTest {

	/** Reference log-density: matches GridAggregator.densityWeight(count). */
	private fun densityWeight(count: Int): Double =
		Math.log1p(count.toDouble()) / Math.log1p(150.0)

	private fun feature(lat: Double, lon: Double, time: Long = 0L, weight: Double = 1.0) =
		WeightedGeoFeature(lat, lon, time, weight)

	private fun ctx(zoom: Float = 17f, quality: Float = 1f, maxPoints: Int = 1000) =
		AggContext(zoom, quality, maxPoints)

	// ── Density weighting (mass) ────────────────────────────────────────────────

	@Nested
	@DisplayName("GridHeatmapAggregator + CellWeighting.Density")
	inner class Density {

		private val agg = GridHeatmapAggregator(CellWeighting.Density)

		@Test
		fun `empty input yields no cells`() {
			agg.aggregate(emptyList(), ctx()).cells shouldHaveSize 0
		}

		@Test
		fun `single point yields one cool cell`() {
			agg.aggregate(listOf(feature(50.0, 14.0)), ctx()).cells.single().weight shouldBe
				(densityWeight(1) plusOrMinus 1e-9)
		}

		@Test
		fun `duplicate-rate points at one spot collapse into a single cell`() {
			val burst = List(1000) { feature(50.0, 14.0, it.toLong()) }
			val cells = agg.aggregate(burst, ctx(maxPoints = 5000)).cells
			cells shouldHaveSize 1
			cells.single().weight shouldBe (densityWeight(1000).coerceAtMost(1.0) plusOrMinus 1e-9)
		}

		@Test
		fun `weight is absolute and independent of a busier neighbour`() {
			val quiet = List(5) { feature(50.0, 14.0, it.toLong()) }
			val busy = List(500) { feature(60.0, 24.0, it.toLong()) }
			val alone = agg.aggregate(quiet, ctx()).cells.single().weight
			agg.aggregate(quiet + busy, ctx()).cells.minOf { it.weight } shouldBe alone
		}

		@Test
		fun `dense cell is hotter than sparse cell but not saturated`() {
			val weights = agg.aggregate(
				List(2) { feature(50.0, 14.0, it.toLong()) } + List(400) { feature(60.0, 24.0, it.toLong()) },
				ctx(),
			).cells.map { it.weight }.sorted()
			weights shouldHaveSize 2
			(weights.first() < 0.5) shouldBe true
			(weights.last() > weights.first()) shouldBe true
		}

		@Test
		fun `thins uniformly when cells exceed the render budget`() {
			val many = (1..200).map { feature(50.0 + it * 0.01, 14.0, it.toLong()) }
			agg.aggregate(many, ctx(maxPoints = 50)).cells shouldHaveSize 50
		}
	}

	// ── Average weighting (value) ───────────────────────────────────────────────

	@Nested
	@DisplayName("GridHeatmapAggregator + CellWeighting.Average")
	inner class Average {

		@Test
		fun `cell weight is the mean of its fixes' values`() {
			val cell = GridHeatmapAggregator(CellWeighting.Average).aggregate(
				listOf(feature(50.0, 14.0, 0L, 0.2), feature(50.0, 14.0, 1L, 0.4)),
				ctx(),
			).cells.single()
			cell.weight shouldBe (0.3 plusOrMinus 1e-9)
		}
	}

	// ── Encoder ─────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("HeatmapEncoder")
	inner class Encoder {

		private val encoder = HeatmapEncoder(
			colorStops = listOf(0.0f to 0x00000000, 1.0f to 0xFFFF0000.toInt()),
			baseRadiusPx = 20f,
			opacity = 0.8f,
		)

		@Test
		fun `encodes weighted cells to a heatmap config carrying the weight`() {
			val config = encoder.encode(
				SpatialData.WeightedCells(listOf(feature(50.0, 14.0, 0L, 0.42))),
				RenderContext(quality = 1f),
			)
			config.shouldBeInstanceOf<MapLibreLayerConfig.Heatmap>()
			config.geoJson.contains(""""weight":0.42""") shouldBe true
			config.weightProperty shouldBe "weight"
			config.opacity shouldBe 0.8f
		}

		@Test
		fun `empty field still encodes to a heatmap config with no features`() {
			val config = encoder.encode(SpatialData.WeightedCells(emptyList()), RenderContext(1f))
			config.shouldBeInstanceOf<MapLibreLayerConfig.Heatmap>()
			config.geoJson.contains(""""features":[]""") shouldBe true
		}

		@Test
		fun `radius scales inversely with quality`() {
			val lowQ = encoder.encode(SpatialData.WeightedCells(emptyList()), RenderContext(0.5f)) as MapLibreLayerConfig.Heatmap
			val highQ = encoder.encode(SpatialData.WeightedCells(emptyList()), RenderContext(2f)) as MapLibreLayerConfig.Heatmap
			(lowQ.radiusPx > highQ.radiusPx) shouldBe true
		}
	}
}
