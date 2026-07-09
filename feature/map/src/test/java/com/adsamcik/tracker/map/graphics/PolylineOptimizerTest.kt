package com.adsamcik.tracker.map.graphics

import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PolylineOptimizer")
class PolylineOptimizerTest {

    @Nested
    @DisplayName("Optimization")
    inner class Optimization {

        @Test
        fun `reduces point count within budget`() {
            val points = (0..1000).map { LatLngModel(0.0, it / 1000.0) }

            val out = PolylineOptimizer.optimize(points, toleranceMeters = 10.0, maxPoints = 100)

            out.size shouldBeLessThanOrEqualTo 100
            out.first() shouldBe points.first()
            out.last() shouldBe points.last()
        }

        @Test
        fun `collapses a straight line to its endpoints`() {
            // ~2.2 km of collinear samples: shape is a straight line, so everything
            // except the endpoints is redundant.
            val points = (0..20).map { LatLngModel(0.0, it / 1000.0) }

            val out = PolylineOptimizer.optimize(points, toleranceMeters = 8.0, maxPoints = 10)

            out.size shouldBe 2
            out.first() shouldBe points.first()
            out.last() shouldBe points.last()
        }

        @Test
        fun `preserves a shape-defining corner instead of straightening`() {
            val start = LatLngModel(0.0, 0.0)
            val corner = LatLngModel(0.0, 0.02)
            val end = LatLngModel(0.02, 0.02)
            // Collinear filler along each leg (east, then north) around a sharp corner.
            val leg1 = (1..9).map { LatLngModel(0.0, 0.02 * it / 10.0) }
            val leg2 = (1..9).map { LatLngModel(0.02 * it / 10.0, 0.02) }
            val points = listOf(start) + leg1 + listOf(corner) + leg2 + listOf(end)

            val out = PolylineOptimizer.optimize(points, toleranceMeters = 8.0, maxPoints = 50)

            // The corner survives; the collinear legs collapse around it.
            out shouldContain corner
            out.size shouldBeLessThanOrEqualTo 4
            out.first() shouldBe start
            out.last() shouldBe end
        }

        @Test
        fun `keeps only original vertices without interpolating new points`() {
            // A staircase whose every vertex is shape-defining. Capping below the
            // vertex count must keep a subset of the *recorded* points, never
            // even-distance interpolated points (the previous bug).
            val points = listOf(
                LatLngModel(0.0, 0.0),
                LatLngModel(0.0, 0.01),
                LatLngModel(0.01, 0.01),
                LatLngModel(0.01, 0.02),
                LatLngModel(0.02, 0.02),
                LatLngModel(0.02, 0.03),
            )

            val out = PolylineOptimizer.optimize(points, toleranceMeters = 1.0, maxPoints = 4)

            out.size shouldBeLessThanOrEqualTo 4
            out.forEach { point -> points shouldContain point }
            out.first() shouldBe points.first()
            out.last() shouldBe points.last()
        }
    }

    @Nested
    @DisplayName("Smoothing")
    inner class Smoothing {

        @Test
        fun `cuts the sharp corner while pinning the endpoints`() {
            val start = LatLngModel(0.0, 0.0)
            val corner = LatLngModel(0.0, 0.02)
            val end = LatLngModel(0.02, 0.02)
            val leg1 = (1..9).map { LatLngModel(0.0, 0.02 * it / 10.0) }
            val leg2 = (1..9).map { LatLngModel(0.02 * it / 10.0, 0.02) }
            val points = listOf(start) + leg1 + listOf(corner) + leg2 + listOf(end)

            val out = PolylineOptimizer.optimize(
                points, toleranceMeters = 8.0, maxPoints = 50, smoothingIterations = 2,
            )

            // Chaikin shaves the exact corner vertex away, replacing it with nearby cut points,
            // and the track stays anchored to the real recorded start and end.
            out shouldNotContain corner
            out.first() shouldBe start
            out.last() shouldBe end
            out.size shouldBeLessThanOrEqualTo 50
        }

        @Test
        fun `keeps a straight line straight`() {
            val points = (0..20).map { LatLngModel(0.0, it / 1000.0) }

            val out = PolylineOptimizer.optimize(
                points, toleranceMeters = 0.0, maxPoints = 100, smoothingIterations = 2,
            )

            // Every smoothed vertex stays on the original latitude — smoothing must not bend a
            // straight path — and the endpoints are preserved.
            out.forEach { it.lat shouldBe 0.0 }
            out.first() shouldBe points.first()
            out.last() shouldBe points.last()
        }

        @Test
        fun `respects the point budget after smoothing`() {
            // A dense zigzag: many corners for Chaikin to round, well over the budget.
            val points = (0..400).map { LatLngModel(if (it % 2 == 0) 0.0 else 0.001, it / 1000.0) }

            val out = PolylineOptimizer.optimize(
                points, toleranceMeters = 1.0, maxPoints = 80, smoothingIterations = 2,
            )

            out.size shouldBeLessThanOrEqualTo 80
            out.first() shouldBe points.first()
            out.last() shouldBe points.last()
        }
    }
}
