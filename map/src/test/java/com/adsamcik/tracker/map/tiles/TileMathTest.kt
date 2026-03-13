package com.adsamcik.tracker.map.tiles

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan as intShouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan as intShouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class TileMathTest {

    @Nested
    inner class TileToBounds {

        @Test
        fun `zoom 0 single tile covers the world`() {
            val bounds = TileMath.tileToBounds(0, 0, 0)
            bounds.west shouldBe -180.0
            bounds.east shouldBe 180.0
            bounds.north shouldBeGreaterThan 85.0
            bounds.south shouldBeLessThan -85.0
        }

        @Test
        fun `zoom 1 northwest quadrant`() {
            val bounds = TileMath.tileToBounds(1, 0, 0)
            bounds.west shouldBe -180.0
            bounds.east shouldBe 0.0
            bounds.north shouldBeGreaterThan 85.0
            bounds.south shouldBe 0.0
        }

        @Test
        fun `zoom 1 southeast quadrant`() {
            val bounds = TileMath.tileToBounds(1, 1, 1)
            bounds.west shouldBe 0.0
            bounds.east shouldBe 180.0
            bounds.south shouldBeLessThan -85.0
            bounds.north shouldBe 0.0
        }

        @Test
        fun `north is always greater than south`() {
            for (z in 0..16) {
                val n = 1 shl z
                // Test a few tiles at each zoom
                listOf(0, n / 4, n / 2, n - 1).filter { it in 0 until n }.forEach { y ->
                    listOf(0, n / 4, n / 2, n - 1).filter { it in 0 until n }.forEach { x ->
                        val bounds = TileMath.tileToBounds(z, x, y)
                        bounds.north shouldBeGreaterThan bounds.south
                        bounds.east shouldBeGreaterThan bounds.west
                    }
                }
            }
        }
    }

    @Nested
    inner class LatLonToTile {

        @Test
        fun `origin maps to valid tile`() {
            val (x, y) = TileMath.latLonToTile(0.0, 0.0, 1)
            x shouldBe 1
            // At zoom 1, the equator falls exactly on the y=0/y=1 boundary
            // so y can be 1 (the implementation truncates toward south tile)
            y shouldBe 1
        }

        @Test
        fun `negative coordinates map correctly`() {
            val (x, y) = TileMath.latLonToTile(-33.8688, 151.2093, 10) // Sydney
            x intShouldBeGreaterThan 0
            y intShouldBeGreaterThan 0
        }

        @Test
        fun `round trip at multiple zoom levels`() {
            val testLat = 48.8566 // Paris
            val testLon = 2.3522

            for (z in 1..16) {
                val (x, y) = TileMath.latLonToTile(testLat, testLon, z)
                val bounds = TileMath.tileToBounds(z, x, y)

                // The original point should lie within the tile bounds
                testLat shouldBeGreaterThan bounds.south
                testLat shouldBeLessThan bounds.north
                testLon shouldBeGreaterThan bounds.west
                testLon shouldBeLessThan bounds.east
            }
        }

        @ParameterizedTest
        @CsvSource(
            "51.5074, -0.1278",   // London
            "40.7128, -74.0060",  // New York
            "-33.8688, 151.2093", // Sydney
            "35.6762, 139.6503",  // Tokyo
            "0.0001, 0.0001",    // Near Null Island (avoid exact boundary)
        )
        fun `round trip for known cities`(lat: Double, lon: Double) {
            for (z in 5..15) {
                val (x, y) = TileMath.latLonToTile(lat, lon, z)
                val bounds = TileMath.tileToBounds(z, x, y)

                // Point should be within tile bounds (inclusive for boundary cases)
                (lat >= bounds.south) shouldBe true
                (lat <= bounds.north) shouldBe true
                (lon >= bounds.west) shouldBe true
                (lon <= bounds.east) shouldBe true
            }
        }
    }

    @Nested
    inner class CoordinateToTilePixel {

        @Test
        fun `tile origin maps to near 0-0`() {
            val bounds = TileMath.tileToBounds(10, 512, 340)
            val (px, py) = TileMath.coordinateToTilePixel(
                bounds.north, bounds.west, 10, 512, 340
            )
            // Should be near (0, 0) — the top-left corner of the tile
            px intShouldBeLessThan 10
            py intShouldBeLessThan 10
        }

        @Test
        fun `tile bottom-right maps to near extent`() {
            val extent = 4096
            val bounds = TileMath.tileToBounds(10, 512, 340)
            val (px, py) = TileMath.coordinateToTilePixel(
                bounds.south, bounds.east, 10, 512, 340, extent
            )
            // Should be near (4096, 4096)
            px intShouldBeGreaterThan extent - 10
            py intShouldBeGreaterThan extent - 10
        }
    }

    @Nested
    inner class TilesForBounds {

        @Test
        fun `single tile for small bounds at low zoom`() {
            val bounds = com.adsamcik.tracker.map.data.Bounds(
                north = 48.87, south = 48.85, east = 2.36, west = 2.34
            )
            val tiles = TileMath.tilesForBounds(bounds, 10)
            tiles.size intShouldBeGreaterThan 0
        }

        @Test
        fun `tile count grows with zoom`() {
            val bounds = com.adsamcik.tracker.map.data.Bounds(
                north = 48.90, south = 48.80, east = 2.40, west = 2.30
            )
            val tilesZ10 = TileMath.tilesForBounds(bounds, 10)
            val tilesZ14 = TileMath.tilesForBounds(bounds, 14)
            tilesZ14.size intShouldBeGreaterThan tilesZ10.size
        }
    }
}
