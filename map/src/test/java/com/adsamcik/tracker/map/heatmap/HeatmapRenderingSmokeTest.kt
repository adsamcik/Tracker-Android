package com.adsamcik.tracker.map.heatmap

import com.adsamcik.tracker.map.heatmap.implementation.AgeWeightedHeatmap
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatmapRenderingSmokeTest {
    @Test
    fun render_isNotBlank_afterSinglePoint() {
        val size = 33
        val heat = AgeWeightedHeatmap(
            width = size,
            height = size,
            ageThreshold = 60,
            maxHeat = 100f
        )

        val stamp = HeatmapStamp.generateGaussian(radius = 3)

        // Add a single point roughly at center
        heat.addPoint(
            x = size / 2,
            y = size / 2,
            ageInSeconds = 0,
            weight = 1f,
            stamp = stamp,
            weightMergeFunction = { cur, _, stampValue, value -> cur + stampValue * value },
            alphaMergeFunction = { cur, stampValue, weight ->
                val a = cur / 255f
                val out = 1f - (1f - a) * (1f - stampValue * weight.coerceIn(0f,1f))
                (out * 255f).toInt().coerceIn(0, 255)
            }
        )

        val coverage = heat.activeCoverage()
        assertTrue("coverage should be > 0", coverage > 0f)

        val p = (if (coverage < 0.05f) 0.96f else 0.95f)
        val sat = (heat.estimatePercentile(p).takeIf { it > 0f } ?: heat.maxHeat).coerceAtLeast(1f)
        val normalized = heat.buildNormalizedBuffer(saturation = sat)
        val colors = heat.renderFromNormalized(HeatmapColorScheme.viridis(), normalized, alphaFromNormalized = true, opacity = 0.9f)

        val anyNonZero = colors.any { it != 0 }
        assertTrue("render should contain non-zero pixels", anyNonZero)
    }
}
