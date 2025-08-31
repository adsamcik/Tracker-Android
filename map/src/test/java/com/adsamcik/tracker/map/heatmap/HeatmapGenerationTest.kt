package com.adsamcik.tracker.map.heatmap

import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.heatmap.creators.HeatmapConfig
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileData
import com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted
import com.adsamcik.tracker.shared.map.CoordinateBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

class HeatmapGenerationTest {

    private fun config(opacity: Float = 1f): HeatmapConfig = HeatmapConfig(
        colorScheme = HeatmapColorScheme.viridis(),
    maxHeat = 100f,
        ageThreshold = 60,
        weightMergeFunction = { cur, _, sv, v -> cur + sv * v },
        alphaMergeFunction = { cur, sv, w ->
            val a = cur / 255f
            val out = 1f - (1f - a) * (1f - sv * w.coerceIn(0f, 1f))
            (out * 255f).toInt().coerceIn(0, 255)
        },
        valueCurve = { it },
    alphaFromNormalized = true,
    opacity = opacity
    )

    private fun alphaAt(buf: IntArray, w: Int, x: Int, y: Int): Int {
        val c = buf[y * w + x]
        return (c ushr 24) and 0xFF
    }

    @Test
    fun single_center_point_forms_peak_with_radial_falloff() {
        val heatmapSize = 33
        val stampRadius = 5
        val pad = stampRadius + 3
        val zoom = 12
        val tileX = 50
        val tileY = 77

        val data = HeatmapTileData(
            config = config(opacity = 1f),
            stamp = HeatmapStamp.generateGaussian(radius = stampRadius),
            heatmapSize = heatmapSize,
            x = tileX,
            y = tileY,
            zoom = zoom,
            area = CoordinateBounds(0.0, 0.0, 0.0, 0.0),
            pad = pad
        )
        val tile = HeatmapEngine(data)

        val lat = MapFunctions.toLat(tileY + 0.5, zoom)
        val lon = MapFunctions.toLon(tileX + 0.5, zoom)
        val t = 1_000_000L
        val p = TimeLocation2DWeighted(t, lat, lon, 0.0).also { it.normalize(1.0) }
        tile.addAll(listOf(p))

        val colors = tile.buildCroppedColorArray()

        // Find argmax alpha
        var maxA = -1
        var maxX = 0
        var maxY = 0
        for (y in 0 until heatmapSize) {
            for (x in 0 until heatmapSize) {
                val a = alphaAt(colors, heatmapSize, x, y)
                if (a > maxA) { maxA = a; maxX = x; maxY = y }
            }
        }
        // Expect near center
        assertTrue("peak near center", hypot((maxX - heatmapSize/2).toDouble(), (maxY - heatmapSize/2).toDouble()) <= 2.0)

        // Compare mean alpha on concentric rings: r=1 should be >= r=2
        fun ringMean(r: Int): Double {
            var sum = 0L
            var cnt = 0
            for (dy in -r..r) for (dx in -r..r) {
                if (abs(dx) == r || abs(dy) == r) {
                    val x = (heatmapSize/2 + dx).coerceIn(0, heatmapSize-1)
                    val y = (heatmapSize/2 + dy).coerceIn(0, heatmapSize-1)
                    sum += alphaAt(colors, heatmapSize, x, y)
                    cnt++
                }
            }
            return if (cnt>0) sum.toDouble()/cnt else 0.0
        }
        val r1 = ringMean(1)
        val r2 = ringMean(2)
        assertTrue("inner ring should be brighter than outer ring", r1 >= r2)
    }

    @Test
    fun two_points_create_two_distinct_peaks() {
        val heatmapSize = 49
        val stampRadius = 5
        val pad = stampRadius + 3
        val zoom = 12
        val tileX = 10
        val tileY = 20
        val data = HeatmapTileData(
            config = config(opacity = 1f),
            stamp = HeatmapStamp.generateGaussian(radius = stampRadius),
            heatmapSize = heatmapSize,
            x = tileX,
            y = tileY,
            zoom = zoom,
            area = CoordinateBounds(0.0, 0.0, 0.0, 0.0),
            pad = pad
        )
        val tile = HeatmapEngine(data)

        val cLat = MapFunctions.toLat(tileY + 0.5, zoom)
        val cLon = MapFunctions.toLon(tileX + 0.5, zoom)
        val dx = 0.15 // fraction of tile
        val p1 = TimeLocation2DWeighted(1_000_000L, cLat, MapFunctions.toLon(tileX + 0.5 - dx, zoom), 0.0).also { it.normalize(1.0) }
        val p2 = TimeLocation2DWeighted(1_000_000L, cLat, MapFunctions.toLon(tileX + 0.5 + dx, zoom), 0.0).also { it.normalize(1.0) }
        tile.addAll(listOf(p1, p2))

        val colors = tile.buildCroppedColorArray()
        // Project expected x positions in cropped space
        val tileCount = MapFunctions.getTileCount(zoom)
        fun mapX(lon: Double): Int {
            val x = HeatmapMapping.lonToX(lon, tileCount, tileX, heatmapSize, pad)
            return (x - pad).coerceIn(0, heatmapSize-1)
        }
        val x1 = mapX(p1.longitude)
        val x2 = mapX(p2.longitude)

        // For each expected peak column, find the row with max alpha
        fun colPeakX(x: Int): Int {
            var max = -1
            for (y in 0 until heatmapSize) max = max.coerceAtLeast(alphaAt(colors, heatmapSize, x, y))
            return max
        }
        val a1 = colPeakX(x1)
        val a2 = colPeakX(x2)
        // Midpoint should be lower
        val mid = (x1 + x2) / 2
        val aMid = colPeakX(mid)
        assertTrue("two peaks brighter than midpoint", a1 > aMid && a2 > aMid)
    }

    @Test
    fun mapping_places_peak_near_expected_pixel() {
        val heatmapSize = 33
        val stampRadius = 4
        val pad = stampRadius + 2
        val zoom = 8
        val tileX = 5
        val tileY = 9
        val data = HeatmapTileData(
            config = config(opacity = 1f),
            stamp = HeatmapStamp.generateGaussian(radius = stampRadius),
            heatmapSize = heatmapSize,
            x = tileX,
            y = tileY,
            zoom = zoom,
            area = CoordinateBounds(0.0, 0.0, 0.0, 0.0),
            pad = pad
        )
        val tile = HeatmapEngine(data)

        // Place at 0.7, 0.3 within the tile
        val lat = MapFunctions.toLat(tileY + 0.3, zoom)
        val lon = MapFunctions.toLon(tileX + 0.7, zoom)
        val p = TimeLocation2DWeighted(1_000_000L, lat, lon, 0.0).also { it.normalize(1.0) }
        tile.addAll(listOf(p))

        val colors = tile.buildCroppedColorArray()

        // Expected pixel (cropped) using same mapping as HeatmapEngine.add
        val tileCount = MapFunctions.getTileCount(zoom)
        val px = HeatmapMapping.lonToX(lon, tileCount, tileX, heatmapSize, 0) // no pad for cropped coordinates
        val py = HeatmapMapping.latToY(lat, tileCount, tileY, heatmapSize, 0) // no pad for cropped coordinates

        // Argmax
        var maxA = -1
        var mx = 0
        var my = 0
        for (y in 0 until heatmapSize) for (x in 0 until heatmapSize) {
            val a = alphaAt(colors, heatmapSize, x, y)
            if (a > maxA) { maxA = a; mx = x; my = y }
        }
        val dist = hypot((mx - px).toDouble(), (my - py).toDouble())
        assertTrue("peak should land near expected pixel", dist <= 1.5)

        // Peak alpha must be > 0
        assertTrue(maxA > 0)
        // Center pixel alpha must match computed position
        assertEquals(maxA, alphaAt(colors, heatmapSize, mx, my))
    }
}
