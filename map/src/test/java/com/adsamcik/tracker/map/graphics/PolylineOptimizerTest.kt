package com.adsamcik.tracker.map.graphics

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PolylineOptimizerTest {
    @Test
    fun `optimize reduces point count within budget`() {
        val points = (0..1000).map { LatLng(0.0, it / 1000.0) }
        val out = PolylineOptimizer.optimize(points, toleranceMeters = 10.0, maxPoints = 100)
        assertTrue(out.size <= 100)
        assertEquals(points.first(), out.first())
        assertEquals(points.last(), out.last())
    }

    @Test
    fun `optimize maintains endpoints and spacing`() {
        val points = listOf(
            LatLng(0.0, 0.0),
            LatLng(0.0, 0.1),
            LatLng(0.0, 0.2),
            LatLng(0.0, 0.3),
            LatLng(0.0, 0.4),
            LatLng(0.0, 0.5)
        )
        val out = PolylineOptimizer.optimize(points, toleranceMeters = 0.0, maxPoints = 3)
        assertEquals(3, out.size)
        assertEquals(points.first(), out.first())
        assertEquals(points.last(), out.last())
    }
}
