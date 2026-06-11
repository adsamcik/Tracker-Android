package com.adsamcik.tracker.map.graphics

import com.adsamcik.tracker.map.data.WeightedGeoFeature
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class GridAggregatorTest {

    private fun makePoints(count: Int, latBase: Double = 48.85, lonBase: Double = 2.34): List<WeightedGeoFeature> =
        (0 until count).map { i ->
            WeightedGeoFeature(
                lat = latBase + (i % 10) * 0.0001,
                lon = lonBase + (i / 10) * 0.0001,
                time = 1000L + i,
                weight = 1.0,
            )
        }

    @Nested
    inner class CellSizeForZoom {
        @Test
        fun `returns 0-5 for very low zoom`() {
            GridAggregator.cellSizeForZoom(3f) shouldBe 0.5
        }

        @Test
        fun `returns 0-1 for medium zoom`() {
            GridAggregator.cellSizeForZoom(7f) shouldBe 0.1
        }

        @Test
        fun `returns 0-01 for high zoom`() {
            GridAggregator.cellSizeForZoom(11f) shouldBe 0.01
        }

        @Test
        fun `returns 0 for very high zoom`() {
            GridAggregator.cellSizeForZoom(15f) shouldBe 0.0
        }
    }

    @Nested
    inner class Aggregate {
        @Test
        fun `reduces point count`() {
            val points = makePoints(100)
            val cells = GridAggregator.aggregate(points, 0.01)
            val features = GridAggregator.toWeightedFeatures(cells)

            features.size shouldBeLessThan points.size
            features.size shouldBeGreaterThan 0
        }

        @Test
        fun `averaging preserves weight for uniform input`() {
            // With uniform weight=1.0, each cell average should be 1.0
            val points = makePoints(50)
            val cells = GridAggregator.aggregate(points, 0.001)
            val features = GridAggregator.toWeightedFeatures(cells)

            // Every aggregated feature should have weight ~1.0 (average of uniform 1.0)
            features.forEach { f ->
                f.weight.shouldBeBetween(0.99, 1.01, 0.0)
            }
        }

        @Test
        fun `single point returns single cell`() {
            val points = listOf(WeightedGeoFeature(48.85, 2.34, 1000L, 5.0))
            val cells = GridAggregator.aggregate(points, 0.01)
            val features = GridAggregator.toWeightedFeatures(cells)

            features shouldHaveSize 1
            features[0].weight shouldBe 5.0
        }

        @Test
        fun `empty input returns empty`() {
            val cells = GridAggregator.aggregate(emptyList(), 0.01)
            GridAggregator.toWeightedFeatures(cells) shouldHaveSize 0
        }
    }
}
