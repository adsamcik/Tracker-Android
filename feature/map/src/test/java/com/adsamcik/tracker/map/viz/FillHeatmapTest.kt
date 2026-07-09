package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the engine's second render shape ([SpatialData.FillCells]) — the
 * [LegacyTileAggregatorStage] and [FillEncoder] behind the legacy square-tile heatmap.
 */
@DisplayName("Fill (legacy tile) shape")
class FillHeatmapTest {

	private fun feature(lat: Double, lon: Double) = WeightedGeoFeature(lat, lon, 0L, 1.0)
	private fun ctx() = AggContext(zoom = 17f, quality = 1f, maxPoints = 25_000)

	@Nested
	@DisplayName("LegacyTileAggregatorStage")
	inner class Aggregation {

		@Test
		fun `empty input yields no tiles`() {
			LegacyTileAggregatorStage().aggregate(emptyList(), ctx()).tiles.isEmpty() shouldBe true
		}

		@Test
		fun `co-located fixes collapse into a single tile`() {
			val tiles = LegacyTileAggregatorStage().aggregate(
				List(10) { feature(50.0, 14.0) }, ctx(),
			).tiles
			tiles.size shouldBe 1
			tiles.single().weight shouldBe 1.0 // single tile is the densest -> normalized weight 1.0
		}
	}

	@Nested
	@DisplayName("FillEncoder")
	inner class Encoding {

		private val encoder = FillEncoder(
			colorStops = listOf(0.0f to 0xFF0000FF.toInt(), 1.0f to 0xFFFF0000.toInt()),
			opacity = 0.6f,
			outlineColorArgb = 0x33000000,
		)

		@Test
		fun `empty field encodes to null (nothing to render)`() {
			encoder.encode(SpatialData.FillCells(emptyList()), RenderContext(1f)).shouldBeNull()
		}

		@Test
		fun `non-empty field encodes to a Fill config with polygons and outline`() {
			val tiles = LegacyTileAggregatorStage().aggregate(List(4) { feature(50.0, 14.0) }, ctx()).tiles
			val config = encoder.encode(SpatialData.FillCells(tiles), RenderContext(1f))
			config.shouldBeInstanceOf<MapLibreLayerConfig.Fill>()
			config.geoJson.contains(""""type":"Polygon"""") shouldBe true
			config.opacity shouldBe 0.6f
			config.outlineColorArgb shouldBe 0x33000000
			config.weightProperty shouldBe "weight"
		}
	}
}
