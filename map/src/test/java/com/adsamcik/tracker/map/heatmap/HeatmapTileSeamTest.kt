package com.adsamcik.tracker.map.heatmap

import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.heatmap.creators.HeatmapConfig
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileData
import com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted
import com.adsamcik.tracker.shared.map.CoordinateBounds
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Seam test: render four neighboring tiles around (x,y) and compare pixels along shared edges.
 */
class HeatmapTileSeamTest {

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

    private fun tileData(x: Int, y: Int, z: Int, size: Int, pad: Int, saturation: Float) : HeatmapTileData {
        val left = MapFunctions.toLon(x.toDouble(), z)
        val top = MapFunctions.toLat(y.toDouble(), z)
        val right = MapFunctions.toLon((x + 1).toDouble(), z)
        val bottom = MapFunctions.toLat((y + 1).toDouble(), z)
        val area = CoordinateBounds(top, right, bottom, left)
        val stamp = HeatmapStamp.generateGaussian(radius = 8)
        return HeatmapTileData(
            config = defaultConfig(),
            stamp = stamp,
            heatmapSize = size,
            x = x,
            y = y,
            zoom = z,
            area = area,
            pad = pad,
            saturationOverride = saturation
        )
    }

    @Test
    fun neighbors_have_identical_borders() {
        val z = 3
        val x = 2
        val y = 3
        val size = 128
        val pad = 12

        // Single event near the corner crossing all 4 tiles via stamp + pad
        val t = 1_000_000_000L
        val event = TimeLocation2DWeighted(t, 0.0, 0.0, 10.0).also { it.normalize(100.0) }

    val sharedSat = 1.0f
    val t00 = HeatmapTile(tileData(x, y, z, size, pad, sharedSat)).apply { addAll(listOf(event)) }.renderIntArrayCroppedForTest(disableBlur = true, cutoffOverride = 0f)
    val t10 = HeatmapTile(tileData(x + 1, y, z, size, pad, sharedSat)).apply { addAll(listOf(event)) }.renderIntArrayCroppedForTest(disableBlur = true, cutoffOverride = 0f)
    val t01 = HeatmapTile(tileData(x, y + 1, z, size, pad, sharedSat)).apply { addAll(listOf(event)) }.renderIntArrayCroppedForTest(disableBlur = true, cutoffOverride = 0f)
    val t11 = HeatmapTile(tileData(x + 1, y + 1, z, size, pad, sharedSat)).apply { addAll(listOf(event)) }.renderIntArrayCroppedForTest(disableBlur = true, cutoffOverride = 0f)

        fun leftEdge(buf: IntArray, w: Int, h: Int) = IntArray(h) { i -> buf[i * w] }
        fun rightEdge(buf: IntArray, w: Int, h: Int) = IntArray(h) { i -> buf[i * w + (w - 1)] }
        fun topEdge(buf: IntArray, w: Int, h: Int) = IntArray(w) { i -> buf[i] }
        fun bottomEdge(buf: IntArray, w: Int, h: Int) = IntArray(w) { i -> buf[(h - 1) * w + i] }

        // Shared vertical border between (x,y) right edge and (x+1,y) left edge
        val v0 = rightEdge(t00, size, size)
        val v1 = leftEdge(t10, size, size)
        // Shared horizontal border between (x,y) bottom edge and (x,y+1) top edge
        val h0 = bottomEdge(t00, size, size)
        val h1 = topEdge(t01, size, size)

        if (!v0.contentEquals(v1)) {
            fail("Vertical shared edge mismatch. " + edgeDiffReport("vertical", v0, v1))
        }
        if (!h0.contentEquals(h1)) {
            fail("Horizontal shared edge mismatch. " + edgeDiffReport("horizontal", h0, h1))
        }

        // Also check the opposite borders for completeness
        val v2 = rightEdge(t01, size, size)
        val v3 = leftEdge(t11, size, size)
        val h2 = bottomEdge(t10, size, size)
        val h3 = topEdge(t11, size, size)
        if (!v2.contentEquals(v3)) {
            fail("Vertical shared edge mismatch (bottom). " + edgeDiffReport("vertical-bottom", v2, v3))
        }
        if (!h2.contentEquals(h3)) {
            fail("Horizontal shared edge mismatch (right). " + edgeDiffReport("horizontal-right", h2, h3))
        }
    }

    private fun edgeDiffReport(name: String, a: IntArray, b: IntArray, sample: Int = 8): String {
        val n = a.size
        var mismatches = 0
        val samples = mutableListOf<String>()
        for (i in 0 until n) {
            if (a[i] != b[i]) {
                if (samples.size < sample) {
                    val av = a[i]
                    val bv = b[i]
                    samples += "i=$i a=${av.toUInt().toString(16)} b=${bv.toUInt().toString(16)}"
                }
                mismatches++
            }
        }
        val mismatchPct = if (n > 0) (mismatches * 100.0 / n).toInt() else 0
        return "$name edge: $mismatches/$n mismatches (~$mismatchPct%), samples=[${samples.joinToString()}]"
    }
}
