package com.adsamcik.tracker.map.heatmap

import com.adsamcik.tracker.map.heatmap.creators.HeatmapConfig
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileData
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted
import com.adsamcik.tracker.shared.map.CoordinateBounds
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Direction-invariance: rendering the same events forwards vs backwards in time
 * produces identical cropped ARGB output.
 */
class HeatmapTileDirectionTest {

    private fun defaultConfig(): HeatmapConfig = HeatmapConfig(
        colorScheme = HeatmapColorScheme.viridis(),
        maxHeat = 100f,
        dynamicHeat = false,
        ageThreshold = 15 * 60,
        weightMergeFunction = { current, alpha, stamp, value -> current + stamp * value },
        alphaMergeFunction = { cur, stamp, weight ->
            val a = cur / 255f
            val out = 1f - (1f - a) * (1f - stamp * weight.coerceIn(0f,1f))
            (out * 255f).toInt().coerceIn(0, 255)
        },
        valueCurve = { it },
        alphaFromNormalized = true,
        opacity = 1f
    )

    @Test
    fun forward_backward_same() {
        val size = 128
        val pad = 12
        val tileX = 0
        val tileY = 0
        val zoom = 3
        val area = CoordinateBounds(85.0, 180.0, -85.0, -180.0)
        val stamp = HeatmapStamp.generateGaussian(radius = 8)
        val data = HeatmapTileData(
            config = defaultConfig(),
            stamp = stamp,
            heatmapSize = size,
            x = tileX,
            y = tileY,
            zoom = zoom,
            area = area,
            pad = pad,
            saturationOverride = null
        )

        // Three events spaced in time; different longitudes to ensure placement
        val baseT = 1_000_000_000L
        val events = listOf(
            TimeLocation2DWeighted(baseT - 15 * 60_000L, 0.0, 0.0, 10.0),
            TimeLocation2DWeighted(baseT,  0.1, 0.1, 10.0),
            TimeLocation2DWeighted(baseT + 15 * 60_000L, 0.2, 0.2, 10.0)
        ).onEach { it.normalize(100.0) }

        val forward = HeatmapTile(data).apply { addAll(events) }.renderIntArrayCroppedForTest()
        val backward = HeatmapTile(data).apply { addAll(events.asReversed()) }.renderIntArrayCroppedForTest()

        assertArrayEquals("Forward and backward renders must match", forward, backward)
    }
}
