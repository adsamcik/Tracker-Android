package com.adsamcik.tracker.map.graphics

import com.adsamcik.tracker.map.data.WeightedGeoFeature
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("LegacyTileAggregator")
class LegacyTileAggregatorTest {

	private fun point(lat: Double, lon: Double) =
		WeightedGeoFeature(lat = lat, lon = lon, time = 0L, weight = 0.0)

	@Test
	fun `empty input yields no tiles`() {
		LegacyTileAggregator.tile(emptyList(), centerLat = 50.0) shouldBe emptyList()
	}

	@Test
	fun `points in the same 10m cell collapse into one tile`() {
		// Two points ~1 m apart at lat 50 — well within a 10 m cell.
		val points = listOf(point(50.000000, 14.000000), point(50.000005, 14.000005))
		val tiles = LegacyTileAggregator.tile(points, centerLat = 50.0)
		tiles shouldHaveSize 1
		tiles.first().count shouldBe 2
		// The single tile is the densest, so its normalized weight is 1.0.
		tiles.first().weight shouldBe 1.0
	}

	@Test
	fun `points far apart produce separate tiles`() {
		val points = listOf(point(50.0, 14.0), point(50.01, 14.01)) // ~1.1 km apart
		LegacyTileAggregator.tile(points, centerLat = 50.0).size shouldBe 2
	}

	@Test
	fun `weight is normalized by the densest cell`() {
		// Cell A gets 3 points, cell B gets 1 point.
		val points = listOf(
			point(50.0, 14.0), point(50.00001, 14.00001), point(50.00002, 14.0),
			point(50.01, 14.01),
		)
		val tiles = LegacyTileAggregator.tile(points, centerLat = 50.0).sortedByDescending { it.count }
		tiles shouldHaveSize 2
		tiles[0].count shouldBe 3
		tiles[0].weight shouldBe 1.0
		tiles[1].count shouldBe 1
		tiles[1].weight shouldBe (1.0 / 3.0 plusOrMinus 1e-9)
	}

	@Test
	fun `tile edges are roughly 10 metres on the ground`() {
		val tiles = LegacyTileAggregator.tile(listOf(point(50.0, 14.0)), centerLat = 50.0)
		val tile = tiles.single()
		// Latitude span * metres-per-degree ≈ 10 m.
		val latMeters = (tile.north - tile.south) * 111_320.0
		latMeters shouldBe (10.0 plusOrMinus 0.01)
		// Longitude span * metres-per-degree * cos(lat) ≈ 10 m (cos 50° ≈ 0.643).
		val lonMeters = (tile.east - tile.west) * 111_320.0 * Math.cos(Math.toRadians(50.0))
		lonMeters shouldBe (10.0 plusOrMinus 0.1)
	}

	@Test
	fun `tile count is capped at maxTiles`() {
		// 50 points each in their own distant cell; cap to 10.
		val points = (0 until 50).map { point(50.0 + it * 0.01, 14.0) }
		LegacyTileAggregator.tile(points, centerLat = 50.0, maxTiles = 10).size shouldBe 10
	}
}
