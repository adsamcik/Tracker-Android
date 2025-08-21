package com.adsamcik.tracker.map.heatmap

import com.adsamcik.tracker.map.heatmap.implementation.AgeWeightedHeatmap
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.math.abs

class AgeDecayOrderInvariantTest {
    private fun renderNormalized(
        hm: AgeWeightedHeatmap,
        saturation: Float = 1f
    ): FloatArray = hm.buildNormalizedBuffer(saturation)

    @Test
    fun addingOlderSampleAfterNewerAppliesForwardDecay() {
        val w = 16
        val h = 16
        val tau = 60 // seconds
    val hm1 = AgeWeightedHeatmap(w, h, ageThreshold = tau, maxHeat = 0f)
    val hm2 = AgeWeightedHeatmap(w, h, ageThreshold = tau, maxHeat = 0f)
        val stamp = HeatmapStamp.generateGaussian(3)

        val x = 8; val y = 8
        val t0 = 0
        val t1 = 30 // newer by 30s

        // Order A: add older, then newer
        hm1.addPoint(x, y, ageInSeconds = t0, weight = 1f, stamp = stamp)
        hm1.addPoint(x, y, ageInSeconds = t1, weight = 1f, stamp = stamp)

        // Order B: add newer, then older (older should be forward-decayed)
        hm2.addPoint(x, y, ageInSeconds = t1, weight = 1f, stamp = stamp)
        hm2.addPoint(x, y, ageInSeconds = t0, weight = 1f, stamp = stamp)

        val a = renderNormalized(hm1, saturation = 1f)
        val b = renderNormalized(hm2, saturation = 1f)

        // Compare within tolerance
        assertArrayEquals(a, b, 1e-3f)
    }
}
