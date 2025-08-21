package com.adsamcik.tracker.map.heatmap

import com.adsamcik.tracker.map.heatmap.implementation.AgeWeightedHeatmap
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatmapNormalizationAndRevisitTest {
    @Test
    fun higher_saturation_produces_dimmer_normalized_values() {
        val size = 17
        val heat = AgeWeightedHeatmap(
            width = size,
            height = size,
            ageThreshold = 60,
            maxHeat = 100f
        )
        val stamp = HeatmapStamp.generateGaussian(radius = 3)
        val cx = size / 2
        val cy = size / 2
        heat.addPoint(cx, cy, ageInSeconds = 0, weight = 10f, stamp = stamp,
            weightMergeFunction = { cur, _, sv, v -> cur + sv * v },
            alphaMergeFunction = { cur, sv, w ->
                val a = cur / 255f
                val out = 1f - (1f - a) * (1f - sv * w.coerceIn(0f, 1f))
                (out * 255f).toInt().coerceIn(0, 255)
            })

        val satLow = 5f
        val satHigh = 50f
        val nLow = heat.buildNormalizedBuffer(saturation = satLow)
        val nHigh = heat.buildNormalizedBuffer(saturation = satHigh)

        // The center pixel should be brighter with lower saturation
        val idx = cy * size + cx
        assertTrue("normalized center should be higher for lower saturation", nLow[idx] > nHigh[idx])
    }

    // Revisit behavior moved to policy level; coverage retained by HeatmapTile tests.
}
