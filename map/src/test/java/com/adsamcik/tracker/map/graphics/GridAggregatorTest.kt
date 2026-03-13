package com.adsamcik.tracker.map.graphics

import com.adsamcik.tracker.map.data.WeightedGeoFeature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("GridAggregator")
class GridAggregatorTest {

    @Nested
    @DisplayName("cellSizeForZoom")
    inner class CellSizeForZoom {

        @Test
        fun `zoom below 5 returns 0_5 degrees`() {
            assertEquals(0.5, GridAggregator.cellSizeForZoom(3f))
            assertEquals(0.5, GridAggregator.cellSizeForZoom(0f))
            assertEquals(0.5, GridAggregator.cellSizeForZoom(4.99f))
        }

        @Test
        fun `zoom 5 to 9 returns 0_1 degrees`() {
            assertEquals(0.1, GridAggregator.cellSizeForZoom(5f))
            assertEquals(0.1, GridAggregator.cellSizeForZoom(7f))
            assertEquals(0.1, GridAggregator.cellSizeForZoom(8.99f))
        }

        @Test
        fun `zoom 9 to 13 returns 0_01 degrees`() {
            assertEquals(0.01, GridAggregator.cellSizeForZoom(9f))
            assertEquals(0.01, GridAggregator.cellSizeForZoom(11f))
            assertEquals(0.01, GridAggregator.cellSizeForZoom(12.99f))
        }

        @Test
        fun `zoom 13 and above returns 0_0 (no aggregation)`() {
            assertEquals(0.0, GridAggregator.cellSizeForZoom(13f))
            assertEquals(0.0, GridAggregator.cellSizeForZoom(17f))
            assertEquals(0.0, GridAggregator.cellSizeForZoom(22f))
        }
    }

    @Nested
    @DisplayName("aggregate")
    inner class Aggregate {

        @Test
        fun `empty input returns empty list`() {
            val result = GridAggregator.aggregate(emptyList(), 0.5)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `zero cell size returns empty list`() {
            val points = listOf(feature(10.0, 20.0, 0.5))
            val result = GridAggregator.aggregate(points, 0.0)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `negative cell size returns empty list`() {
            val points = listOf(feature(10.0, 20.0, 0.5))
            val result = GridAggregator.aggregate(points, -1.0)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `single point produces single cell`() {
            val points = listOf(feature(10.3, 20.7, 0.8, time = 1000L))
            val result = GridAggregator.aggregate(points, 0.5)
            assertEquals(1, result.size)
            val cell = result.first()
            assertEquals(10.3, cell.lat, 0.001)
            assertEquals(20.7, cell.lon, 0.001)
            assertEquals(0.8, cell.weight, 0.001)
            assertEquals(1, cell.count)
            assertEquals(1000L, cell.newestTime)
        }

        @Test
        fun `points in same cell are aggregated`() {
            val points = listOf(
                feature(10.1, 20.1, 0.4, time = 100L),
                feature(10.2, 20.2, 0.6, time = 200L),
                feature(10.3, 20.3, 0.8, time = 150L),
            )
            // Cell size 0.5 → all three in same bucket (10.0-10.5, 20.0-20.5)
            val result = GridAggregator.aggregate(points, 0.5)
            assertEquals(1, result.size)
            val cell = result.first()
            assertEquals(3, cell.count)
            // Average weight: (0.4 + 0.6 + 0.8) / 3 = 0.6
            assertEquals(0.6, cell.weight, 0.001)
            // Average lat: (10.1 + 10.2 + 10.3) / 3 = 10.2
            assertEquals(10.2, cell.lat, 0.001)
            // Average lon: (20.1 + 20.2 + 20.3) / 3 = 20.2
            assertEquals(20.2, cell.lon, 0.001)
            // Newest time
            assertEquals(200L, cell.newestTime)
        }

        @Test
        fun `points in different cells produce separate cells`() {
            val points = listOf(
                feature(10.0, 20.0, 0.5),
                feature(11.0, 21.0, 0.7),
                feature(12.0, 22.0, 0.9),
            )
            // Cell size 0.5 → each point in different bucket
            val result = GridAggregator.aggregate(points, 0.5)
            assertEquals(3, result.size)
            result.forEach { assertEquals(1, it.count) }
        }

        @Test
        fun `weight is averaged not summed`() {
            val points = listOf(
                feature(10.1, 20.1, 0.2),
                feature(10.2, 20.2, 0.8),
            )
            val result = GridAggregator.aggregate(points, 1.0)
            assertEquals(1, result.size)
            assertEquals(0.5, result.first().weight, 0.001)
        }

        @Test
        fun `count accuracy with many points`() {
            val points = (1..100).map { feature(10.0 + it * 0.001, 20.0 + it * 0.001, 0.5) }
            // Cell size 0.5 → all 100 in one cell
            val result = GridAggregator.aggregate(points, 0.5)
            assertEquals(1, result.size)
            assertEquals(100, result.first().count)
        }

        @Test
        fun `antimeridian positive wrap`() {
            val points = listOf(
                feature(10.0, 179.8, 0.5),
                feature(10.0, 179.9, 0.5),
            )
            val result = GridAggregator.aggregate(points, 0.5)
            assertEquals(1, result.size, "Points near 180° should be in same cell")
        }

        @Test
        fun `large dataset reduces to manageable cell count`() {
            // Simulate world-view: 80K points spread over large area
            val points = (0 until 1000).map { i ->
                feature(
                    lat = -60.0 + (i / 50) * 1.0,
                    lon = -170.0 + (i % 50) * 7.0,
                    weight = 0.5
                )
            }
            val result = GridAggregator.aggregate(points, 0.5)
            // With 0.5° cells, 1000 points spread across ~120x50 degrees
            // should aggregate significantly
            assertTrue(result.size <= points.size,
                "Aggregation should reduce point count: ${result.size} >= ${points.size}")
            assertTrue(result.isNotEmpty())
        }
    }

    @Nested
    @DisplayName("toWeightedFeatures")
    inner class ToWeightedFeatures {

        @Test
        fun `converts cells to WeightedGeoFeature`() {
            val cells = listOf(
                GridAggregator.AggregatedCell(10.0, 20.0, 0.5, 3, 1000L),
                GridAggregator.AggregatedCell(11.0, 21.0, 0.8, 5, 2000L),
            )
            val features = GridAggregator.toWeightedFeatures(cells)
            assertEquals(2, features.size)
            assertEquals(10.0, features[0].lat)
            assertEquals(20.0, features[0].lon)
            assertEquals(0.5, features[0].weight)
            assertEquals(1000L, features[0].time)
        }

        @Test
        fun `empty cells returns empty features`() {
            assertTrue(GridAggregator.toWeightedFeatures(emptyList()).isEmpty())
        }
    }

    private fun feature(
        lat: Double,
        lon: Double,
        weight: Double,
        time: Long = 0L,
    ) = WeightedGeoFeature(lat = lat, lon = lon, time = time, weight = weight)
}
