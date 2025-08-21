package com.adsamcik.tracker.map.heatmap

import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.heatmap.implementation.AgeWeightedHeatmap
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class HeatmapTileSeamTest {
    private fun renderCropped(
        heat: AgeWeightedHeatmap,
        saturation: Float,
        colorScheme: HeatmapColorScheme,
        alphaFromNormalized: Boolean,
        opacity: Float,
        pad: Int,
        heatmapSize: Int
    ): IntArray {
        val normalized = heat.buildNormalizedBuffer(saturation)
        val colors = heat.renderFromNormalized(colorScheme, normalized, alphaFromNormalized, opacity)
        // Crop center heatmap area (remove padding)
        val w = heat.width
        val h = heat.height
        val out = IntArray(heatmapSize * heatmapSize)
        var di = 0
        for (y in 0 until heatmapSize) {
            val sy = y + pad
            val si = sy * w + pad
            for (x in 0 until heatmapSize) {
                out[di++] = colors[si + x]
            }
        }
        return out
    }

    @Test
    fun sharedVerticalEdge_colors_match_between_adjacent_tiles() {
        val zoom = 5
        val tileX = 10
        val tileY = 12
        val heatmapSize = 4
        val pad = 4
        val paddedSize = heatmapSize + 2 * pad
        val stamp = HeatmapStamp.generateGaussian(radius = 4) // big enough to cross the crop border
        val saturation = 10f
        val scheme = HeatmapColorScheme.viridis()

    // Points just inside each tile by half a heatmap pixel relative to the shared vertical border
    val halfPixel = 0.5 / heatmapSize
    val lonLeft = MapFunctions.toLon(tileX + 1.0 - halfPixel, zoom)
    val lonRight = MapFunctions.toLon(tileX + 1.0 + halfPixel, zoom)
    val latMid = MapFunctions.toLat(tileY + 0.5, zoom)

        fun mappedX(lon: Double, tileStartX: Int): Int {
            val tileCount = MapFunctions.getTileCount(zoom)
            val tx = MapFunctions.toTileX(lon, tileCount)
            val eps = 1e-6
            return kotlin.math.floor(((tx - tileStartX) * heatmapSize) - eps).toInt() + pad
        }

        fun mappedY(lat: Double, tileStartY: Int): Int {
            val tileCount = MapFunctions.getTileCount(zoom)
            val ty = MapFunctions.toTileY(lat, tileCount)
            val eps = 1e-6
            return kotlin.math.floor(((ty - tileStartY) * heatmapSize) - eps).toInt() + pad
        }

        val xLeft = mappedX(lonLeft, tileX)
        val yLeft = mappedY(latMid, tileY)
        val xRight = mappedX(lonRight, tileX + 1)
        val yRight = mappedY(latMid, tileY)

        val left = AgeWeightedHeatmap(
            width = paddedSize,
            height = paddedSize,
            ageThreshold = 60,
            maxHeat = 100f,
            dynamicHeat = false
        )
        val right = AgeWeightedHeatmap(
            width = paddedSize,
            height = paddedSize,
            ageThreshold = 60,
            maxHeat = 100f,
            dynamicHeat = false
        )

        // Same point added into both heatmaps in their local coordinates
        left.addPoint(xLeft, yLeft, ageInSeconds = 0, weight = 1f, stamp = stamp,
            weightMergeFunction = { cur, _, sv, v -> cur + sv * v },
            alphaMergeFunction = { cur, sv, w ->
                val a = cur / 255f
                val out = 1f - (1f - a) * (1f - sv * w.coerceIn(0f, 1f))
                (out * 255f).toInt().coerceIn(0, 255)
            })

        right.addPoint(xRight, yRight, ageInSeconds = 0, weight = 1f, stamp = stamp,
            weightMergeFunction = { cur, _, sv, v -> cur + sv * v },
            alphaMergeFunction = { cur, sv, w ->
                val a = cur / 255f
                val out = 1f - (1f - a) * (1f - sv * w.coerceIn(0f, 1f))
                (out * 255f).toInt().coerceIn(0, 255)
            })

        val leftCropped = renderCropped(left, saturation, scheme, true, 1f, pad, heatmapSize)
        val rightCropped = renderCropped(right, saturation, scheme, true, 1f, pad, heatmapSize)

    // Compare rightmost column of left to leftmost column of right
    val colLeft = IntArray(heatmapSize) { row -> leftCropped[row * heatmapSize + (heatmapSize - 1)] }
    val colRight = IntArray(heatmapSize) { row -> rightCropped[row * heatmapSize] }

        assertArrayEquals("Shared edge columns should match", colLeft, colRight)
    }
}
