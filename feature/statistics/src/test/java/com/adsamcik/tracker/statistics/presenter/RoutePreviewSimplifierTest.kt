package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.feature.map.api.preview.RoutePoint
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.sin

class RoutePreviewSimplifierTest {
    @Test
    fun `leaves a route within the point budget unchanged`() {
        val route = listOf(
            RoutePoint(50.0, 14.0),
            RoutePoint(50.1, 14.1),
            RoutePoint(50.2, 14.2),
        )

        RoutePreviewSimplifier.simplify(route, toleranceMeters = 3.0, maxPoints = 3) shouldBe route
    }

    @Test
    fun `enforces the point budget while preserving endpoints`() {
        val route = (0..1_000).map { index ->
            RoutePoint(
                lat = 50.0 + index * 0.00001,
                lng = 14.0 + sin(index / 8.0) * 0.001,
            )
        }

        val simplified = RoutePreviewSimplifier.simplify(
            points = route,
            toleranceMeters = 3.0,
            maxPoints = 100,
        )

        simplified.size shouldBeLessThanOrEqual 100
        simplified.first() shouldBe route.first()
        simplified.last() shouldBe route.last()
    }
}
