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
 * Tests for the engine's third render shape — 3D extrusion ([ExtrusionEncoder] over
 * [SpatialData.FillCells], reusing the [LegacyTileAggregatorStage] tile field with a configurable
 * tile size).
 */
@DisplayName("FillExtrusion (3D terrain) shape")
class ExtrusionTest {

	private fun feature(lat: Double, lon: Double) = WeightedGeoFeature(lat, lon, 0L, 1.0)
	private fun ctx() = AggContext(zoom = 12f, quality = 1f, maxPoints = 25_000)

	@Nested
	@DisplayName("ExtrusionEncoder")
	inner class Encoding {

		private val encoder = ExtrusionEncoder(
			colorStops = listOf(0.0f to 0xFF1B5E20.toInt(), 1.0f to 0xFFFFF3E0.toInt()),
			maxHeightMeters = 1_600f,
			opacity = 0.9f,
		)

		@Test
		fun `empty field encodes to null`() {
			encoder.encode(SpatialData.FillCells(emptyList()), RenderContext(1f)).shouldBeNull()
		}

		@Test
		fun `non-empty field encodes to a FillExtrusion config`() {
			val tiles = LegacyTileAggregatorStage().aggregate(List(6) { feature(50.0, 14.0) }, ctx()).tiles
			val config = encoder.encode(SpatialData.FillCells(tiles), RenderContext(1f))
			config.shouldBeInstanceOf<MapLibreLayerConfig.FillExtrusion>()
			config.geoJson.contains(""""type":"Polygon"""") shouldBe true
			config.maxHeightMeters shouldBe 1_600f
			config.opacity shouldBe 0.9f
			config.weightProperty shouldBe "weight"
		}
	}

	@Nested
	@DisplayName("LegacyTileAggregatorStage tile size")
	inner class TileSize {

		@Test
		fun `coarser tile size produces fewer, larger tiles for the same spread`() {
			// A spread of points ~50 m apart: fine 25 m tiles split them; coarse 200 m tiles merge them.
			val points = (0..8).map { feature(50.0 + it * 0.0005, 14.0) }
			val fine = LegacyTileAggregatorStage(tileMeters = 25.0).aggregate(points, ctx()).tiles
			val coarse = LegacyTileAggregatorStage(tileMeters = 200.0).aggregate(points, ctx()).tiles
			(coarse.size <= fine.size) shouldBe true
			(coarse.isNotEmpty()) shouldBe true
		}
	}
}
