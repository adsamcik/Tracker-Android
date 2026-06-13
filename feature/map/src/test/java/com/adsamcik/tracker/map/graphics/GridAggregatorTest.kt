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
        fun `aggregates at street zoom`() {
            // Regression: zoom >= 13 used to return 0.0 (no aggregation), letting raw overlapping
            // path points saturate the heatmap to solid red. It must now aggregate.
            GridAggregator.cellSizeForZoom(14f) shouldBe 0.002
        }

        @Test
        fun `aggregates at block zoom`() {
            GridAggregator.cellSizeForZoom(16f) shouldBe 0.0005
        }

        @Test
        fun `aggregates at building zoom`() {
            GridAggregator.cellSizeForZoom(18f) shouldBe 0.00012
        }

        @Test
        fun `is always positive and never increases with zoom`() {
            var previous = Double.MAX_VALUE
            for (z in 0..22) {
                val size = GridAggregator.cellSizeForZoom(z.toFloat())
                check(size > 0.0) { "cell size must be positive (was $size at zoom $z)" }
                check(size <= previous) { "cell size increased from $previous to $size at zoom $z" }
                previous = size
            }
        }

        @Test
        fun `default quality equals quality one`() {
            GridAggregator.cellSizeForZoom(16f) shouldBe GridAggregator.cellSizeForZoom(16f, 1f)
        }

        @Test
        fun `higher quality produces finer cells`() {
            val balanced = GridAggregator.cellSizeForZoom(16f, 1f)
            val detailed = GridAggregator.cellSizeForZoom(16f, 2f)
            // Detailed (quality 2.0) halves the cell size -> more resolution/detail, not just size.
            detailed shouldBe balanced / 2.0
        }

        @Test
        fun `lower quality produces coarser cells`() {
            val balanced = GridAggregator.cellSizeForZoom(16f, 1f)
            val fast = GridAggregator.cellSizeForZoom(16f, 0.5f)
            fast shouldBe balanced * 2.0
        }

        @Test
        fun `quality is clamped to the supported range`() {
            val maxDetail = GridAggregator.cellSizeForZoom(16f, GridAggregator.MAX_QUALITY)
            GridAggregator.cellSizeForZoom(16f, 10f) shouldBe maxDetail
            val maxCoarse = GridAggregator.cellSizeForZoom(16f, GridAggregator.MIN_QUALITY)
            GridAggregator.cellSizeForZoom(16f, 0.01f) shouldBe maxCoarse
        }
    }

    @Nested
    inner class RadiusForQuality {
        @Test
        fun `higher quality shrinks the radius to match finer cells`() {
            GridAggregator.radiusForQuality(20f, 2f) shouldBe 10f
            GridAggregator.radiusForQuality(20f, 1f) shouldBe 20f
            GridAggregator.radiusForQuality(20f, 0.5f) shouldBe 40f
        }

        @Test
        fun `clamps extreme quality values`() {
            GridAggregator.radiusForQuality(20f, 99f) shouldBe 2.5f
            GridAggregator.radiusForQuality(20f, 0.001f) shouldBe 80f
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
