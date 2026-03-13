package com.adsamcik.tracker.map.tiles

import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TileGeoJsonGeneratorTest {

    private val tileBounds = Bounds(north = 48.87, south = 48.85, east = 2.36, west = 2.34)

    @Nested
    inner class GeneratePointTile {

        @Test
        fun `includes points inside tile`() {
            val points = listOf(
                WeightedGeoFeature(48.86, 2.35, 1000L, 0.5),
                WeightedGeoFeature(48.855, 2.345, 2000L, 0.8),
            )
            val result = TileGeoJsonGenerator.generatePointTile(points, tileBounds)
            result shouldContain "FeatureCollection"
            result shouldContain "Point"
            result shouldContain "48.86"
        }

        @Test
        fun `excludes points outside tile`() {
            val points = listOf(
                WeightedGeoFeature(48.86, 2.35, 1000L, 0.5),   // inside
                WeightedGeoFeature(50.0, 10.0, 2000L, 0.8),    // far outside
            )
            val result = TileGeoJsonGenerator.generatePointTile(points, tileBounds)
            result shouldContain "48.86"
            result shouldNotContain "50.0"
        }

        @Test
        fun `empty input produces empty collection`() {
            val result = TileGeoJsonGenerator.generatePointTile(emptyList(), tileBounds)
            result shouldContain "FeatureCollection"
            result shouldNotContain "Point"
        }

        @Test
        fun `includes points in buffer zone`() {
            // Point just outside tile bounds but within 10% buffer
            val bufferPoint = WeightedGeoFeature(
                lat = 48.871,  // slightly above north (48.87)
                lon = 2.35,
                time = 1000L,
                weight = 0.5,
            )
            val result = TileGeoJsonGenerator.generatePointTile(listOf(bufferPoint), tileBounds)
            result shouldContain "48.871"
        }
    }

    @Nested
    inner class GenerateLineTile {

        @Test
        fun `clips line segments to tile`() {
            val points = listOf(
                LatLngModel(48.86, 2.35),   // inside
                LatLngModel(48.855, 2.345), // inside
                LatLngModel(50.0, 10.0),    // far outside
            )
            val result = TileGeoJsonGenerator.generateLineTile(points, tileBounds)
            result shouldContain "LineString"
            result shouldContain "48.86"
        }

        @Test
        fun `empty points produce empty collection`() {
            val result = TileGeoJsonGenerator.generateLineTile(emptyList(), tileBounds)
            result shouldContain "FeatureCollection"
            result shouldNotContain "LineString"
        }

        @Test
        fun `single point is not a valid line`() {
            val result = TileGeoJsonGenerator.generateLineTile(
                listOf(LatLngModel(48.86, 2.35)),
                tileBounds,
            )
            result shouldNotContain "LineString"
        }
    }

    @Nested
    inner class ClipLineToTile {

        @Test
        fun `line fully inside tile is preserved`() {
            val points = listOf(
                LatLngModel(48.86, 2.35),
                LatLngModel(48.855, 2.345),
                LatLngModel(48.865, 2.355),
            )
            val clipped = TileGeoJsonGenerator.clipLineToTile(points, tileBounds)
            clipped.size shouldBe 3
        }

        @Test
        fun `line fully outside tile is empty`() {
            val points = listOf(
                LatLngModel(50.0, 10.0),
                LatLngModel(51.0, 11.0),
            )
            val clipped = TileGeoJsonGenerator.clipLineToTile(points, tileBounds)
            clipped.shouldBeEmpty()
        }

        @Test
        fun `line entering tile includes entry point`() {
            val points = listOf(
                LatLngModel(50.0, 10.0),    // outside
                LatLngModel(48.86, 2.35),   // inside
            )
            val clipped = TileGeoJsonGenerator.clipLineToTile(points, tileBounds)
            clipped.size shouldBeGreaterThan 0
        }
    }

    @Nested
    inner class ClipLineToTileSegments {

        @Test
        fun `line re-entering tile produces two segments`() {
            val points = listOf(
                LatLngModel(48.86, 2.35),    // inside
                LatLngModel(48.855, 2.345),  // inside
                LatLngModel(50.0, 10.0),     // far outside
                LatLngModel(51.0, 11.0),     // far outside
                LatLngModel(48.865, 2.355),  // inside again
                LatLngModel(48.86, 2.35),    // inside
            )
            val segments = TileGeoJsonGenerator.clipLineToTileSegments(points, tileBounds)
            segments.size shouldBe 2
            // First segment: 2 inside + 1 exit = 3 points
            segments[0].size shouldBe 3
            // Second segment: 1 entry + 2 inside = 3 points
            segments[1].size shouldBe 3
        }

        @Test
        fun `single inside run produces one segment`() {
            val points = listOf(
                LatLngModel(50.0, 10.0),     // outside
                LatLngModel(48.86, 2.35),    // inside
                LatLngModel(48.855, 2.345),  // inside
                LatLngModel(50.0, 10.0),     // outside
            )
            val segments = TileGeoJsonGenerator.clipLineToTileSegments(points, tileBounds)
            segments.size shouldBe 1
            // entry + 2 inside + exit = 4
            segments[0].size shouldBe 4
        }

        @Test
        fun `all outside produces empty segments`() {
            val points = listOf(
                LatLngModel(50.0, 10.0),
                LatLngModel(51.0, 11.0),
                LatLngModel(52.0, 12.0),
            )
            val segments = TileGeoJsonGenerator.clipLineToTileSegments(points, tileBounds)
            segments.shouldBeEmpty()
        }

        @Test
        fun `trailing inside segment is flushed`() {
            val points = listOf(
                LatLngModel(50.0, 10.0),     // outside
                LatLngModel(48.86, 2.35),    // inside
                LatLngModel(48.855, 2.345),  // inside — ends inside
            )
            val segments = TileGeoJsonGenerator.clipLineToTileSegments(points, tileBounds)
            segments.size shouldBe 1
            segments[0].size shouldBe 3 // entry + 2 inside
        }

        @Test
        fun `single inside point between two outside is discarded as too short`() {
            // Only 1 inside point → segment = [outside, inside] = 2 points
            // But if the line exits immediately, segment = [outside, inside, outside] = 3
            val points = listOf(
                LatLngModel(50.0, 10.0),     // outside
                LatLngModel(48.86, 2.35),    // inside
                LatLngModel(50.0, 10.0),     // outside
            )
            val segments = TileGeoJsonGenerator.clipLineToTileSegments(points, tileBounds)
            segments.size shouldBe 1
        }
    }
}
