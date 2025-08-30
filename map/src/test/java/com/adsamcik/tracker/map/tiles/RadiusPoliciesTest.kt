package com.adsamcik.tracker.map.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadiusPoliciesTest {

    @Test
    fun scaledMeters_clamps_to_minimum_one() {
        val metersPerPixel = 10_000.0 // absurdly large pixels -> radius would be < 1
        val info = RadiusPolicies.scaledMeters(baseMeters = 0.1, zoom = 17, metersPerPixel = metersPerPixel)
        assertEquals(1, info.baseRadius)
        assertEquals(1, info.maxRadius)
    }

    @Test
    fun scaledMeters_decreases_with_zoom() {
        val mpp = 1.0
        val lowZoom = RadiusPolicies.scaledMeters(baseMeters = 10.0, zoom = 5, metersPerPixel = mpp)
        val highZoom = RadiusPolicies.scaledMeters(baseMeters = 10.0, zoom = 15, metersPerPixel = mpp)
        assertTrue("radius at low zoom should be larger", lowZoom.baseRadius > highZoom.baseRadius)
    }

    @Test
    fun scaledMetersRange_orders_base_le_max() {
        val info = RadiusPolicies.scaledMetersRange(
            baseMeters = 5.0,
            maxMeters = 20.0,
            zoom = 12.0,
            metersPerPixel = 0.5
        )
        assertTrue(info.baseRadius >= 1)
        assertTrue(info.maxRadius >= info.baseRadius)
    }

    @Test
    fun fixedMeters_behaves_as_expected_and_clamps() {
        val mpp = 2.5
        val r = RadiusPolicies.fixedMeters(meters = 10.0, metersPerPixel = mpp)
        // ceil(10/2.5) = ceil(4) = 4
        assertEquals(4, r.baseRadius)
        assertEquals(4, r.maxRadius)

        val rZero = RadiusPolicies.fixedMeters(meters = 0.0, metersPerPixel = 100.0)
        assertEquals(1, rZero.baseRadius)
    }
}
