package com.adsamcik.tracker.map.graphics

import com.adsamcik.tracker.map.presentation.udf.LatLngModel
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
        fun `maintains endpoints and spacing`() {
            val points = listOf(
                LatLngModel(0.0, 0.0),
                LatLngModel(0.0, 0.1),
                LatLngModel(0.0, 0.2),
                LatLngModel(0.0, 0.3),
                LatLngModel(0.0, 0.4),
                LatLngModel(0.0, 0.5)
            )

            val out = PolylineOptimizer.optimize(points, toleranceMeters = 0.0, maxPoints = 3)

            out.size shouldBe 3
            out.first() shouldBe points.first()
            out.last() shouldBe points.last()
        }
    }
}
