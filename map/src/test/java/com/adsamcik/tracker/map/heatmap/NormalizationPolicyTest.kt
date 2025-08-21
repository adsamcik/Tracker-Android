package com.adsamcik.tracker.map.heatmap

import com.adsamcik.tracker.map.heatmap.implementation.AgeWeightedHeatmap
import org.junit.Assert.*
import org.junit.Test

class NormalizationPolicyTest {

    @Test
    fun percentile_mapping_varies_with_zoom_and_coverage() {
        // Low zoom -> lower percentile
        assertEquals(0.90f, NormalizationPolicy.percentileFor(8, 0.50f), 1e-6f)
        // Mid zoom, sparse coverage -> higher percentile
        assertEquals(0.92f, NormalizationPolicy.percentileFor(12, 0.03f), 1e-6f)
        assertEquals(0.95f, NormalizationPolicy.percentileFor(12, 0.10f), 1e-6f)
        // High zoom -> highest percentiles
        assertEquals(0.96f, NormalizationPolicy.percentileFor(15, 0.03f), 1e-6f)
        assertEquals(0.98f, NormalizationPolicy.percentileFor(15, 0.10f), 1e-6f)
        assertEquals(0.985f, NormalizationPolicy.percentileFor(15, 0.50f), 1e-6f)
    }

    @Test
    fun robust_percentile_ignores_zero_background_and_orders_correctly() {
        val hm = AgeWeightedHeatmap(
            width = 32,
            height = 32,
            ageThreshold = 60,
            maxHeat = 0f,
            dynamicHeat = false
        )
        // Empty -> fallback positive saturation
        val pEmpty = NormalizationPolicy.robustPercentile(hm, 0.95f)
        assertTrue(pEmpty >= 1f)

        // Add two separated peaks with different weights
        val stamp = HeatmapStamp.generateGaussian(radius = 1)
    hm.addPoint(x = 8, y = 8, ageInSeconds = 0, weight = 2f, stamp = stamp)
    hm.addPoint(x = 20, y = 20, ageInSeconds = 0, weight = 4f, stamp = stamp)

        val p50 = NormalizationPolicy.robustPercentile(hm, 0.50f)
        val p90 = NormalizationPolicy.robustPercentile(hm, 0.90f)

    assertTrue(p50 > 0f)
    assertTrue(p90 >= p50)
    }

    @Test
    fun smooth_saturation_applies_exponential_moving_average() {
        // First sample: no previous -> raw
        val (s0, t0) = NormalizationPolicy.smoothSaturation(previous = null, previousTimeMs = null, raw = 10f, nowMs = 0L, tauMs = 1000f)
        assertEquals(10f, s0, 1e-6f)

        // Halfway to tau -> alpha = 0.5
        val (s1, t1) = NormalizationPolicy.smoothSaturation(previous = s0, previousTimeMs = 0L, raw = 20f, nowMs = 500L, tauMs = 1000f)
        assertEquals(15f, s1, 1e-4f)

        val (s2, _) = NormalizationPolicy.smoothSaturation(previous = s1, previousTimeMs = t1, raw = 20f, nowMs = 1000L, tauMs = 1000f)
        assertEquals(17.5f, s2, 1e-4f)
        assertTrue(s2 in 15f..20f)
    }
}
