package com.adsamcik.tracker.map.heatmap

import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.heatmap.implementation.AgeWeightedHeatmap
import com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class HeatmapTilePipelineTest {
    private fun gaussianBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius <= 0) return src
        val sigma = radius / 1.5f
        val kr = radius
        val ks = kr * 2 + 1
        val kernel = FloatArray(ks)
        var sum = 0f
        for (i in -kr..kr) {
            val v = exp(-(i * i) / (2f * sigma * sigma))
            kernel[i + kr] = v
            sum += v
        }
        for (i in 0 until ks) kernel[i] /= sum
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        // H
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0f
                var k = 0
                var xi = x - kr
                while (k < ks) {
                    val xc = xi.coerceIn(0, w - 1)
                    acc += src[row + xc] * kernel[k]
                    k++; xi++
                }
                tmp[row + x] = acc
            }
        }
        // V
        for (x in 0 until w) {
            for (y in 0 until h) {
                var acc = 0f
                var k = 0
                var yi = y - kr
                while (k < ks) {
                    val yc = yi.coerceIn(0, h - 1)
                    acc += tmp[yc * w + x] * kernel[k]
                    k++; yi++
                }
                out[y * w + x] = acc
            }
        }
        return out
    }

    @Test
    fun sparse_tile_does_not_blank_after_cutoff_fallback() {
        val heatmapSize = 32
        val pad = 6
        val padded = heatmapSize + 2 * pad
        val zoom = 14
        val tileX = 100
        val tileY = 200
        val stamp = HeatmapStamp.generateGaussian(radius = 5)

        val heat = AgeWeightedHeatmap(
            width = padded,
            height = padded,
            ageThreshold = 60,
            maxHeat = 100f,
            dynamicHeat = false
        )

        val now = 1_000_000L
        val centerLat = MapFunctions.toLat(tileY + 0.5, zoom)
        val centerLon = MapFunctions.toLon(tileX + 0.5, zoom)
        val p1 = TimeLocation2DWeighted(now, centerLat, centerLon, 0.0).also { it.normalize(1.0) }
        val p2 = TimeLocation2DWeighted(now, centerLat + 1e-4, centerLon + 1e-4, 0.0).also { it.normalize(1.0) }
        val pts = listOf(p1, p2)

        val minTime = pts.minOf { it.time }
        val tileCount = MapFunctions.getTileCount(zoom)
        val eps = 1e-6
        fun mapX(lon: Double): Int {
            val tx = MapFunctions.toTileX(lon, tileCount)
            return kotlin.math.floor(((tx - tileX) * heatmapSize) - eps).toInt() + pad
        }
        fun mapY(lat: Double): Int {
            val ty = MapFunctions.toTileY(lat, tileCount)
            return kotlin.math.floor(((ty - tileY) * heatmapSize) - eps).toInt() + pad
        }

        // Deposit points
        pts.sortedBy { it.time }.forEach { loc ->
            val x = mapX(loc.longitude)
            val y = mapY(loc.latitude)
            val ageSec = (((loc.time - minTime).coerceAtLeast(0L)) / com.adsamcik.tracker.shared.base.Time.SECOND_IN_MILLISECONDS).toInt()
            heat.addPoint(
                x = x,
                y = y,
                ageInSeconds = ageSec,
                weight = loc.normalizedWeight.toFloat(),
                stamp = stamp,
                weightMergeFunction = { cur, _, sv, v -> cur + sv * v },
                alphaMergeFunction = { cur, sv, w ->
                    val a = cur / 255f
                    val out = 1f - (1f - a) * (1f - sv * w.coerceIn(0f, 1f))
                    (out * 255f).toInt().coerceIn(0, 255)
                }
            )
        }

        val coverage = heat.activeCoverage()
        // Simple percentile selection similar to production path
        val p = if (coverage < 0.05f) 0.96f else 0.95f
        val histP = heat.estimatePercentileHist(p)
        val nonZeroP = if (histP <= 0f) heat.estimatePercentile(p) else histP
        val localP = if (nonZeroP > 0f) nonZeroP else heat.maxHeat
        val saturation = if (localP > 0f) localP else 1f

        val normalized = heat.buildNormalizedBuffer(saturation)
        val blurred = if (coverage < 0.25f) gaussianBlur(normalized, padded, padded, radius = 2) else normalized

        val cutoff = if (coverage < 0.25f) 0.02f else 0.01f
        var anyNonZero = false
        val adjusted = FloatArray(blurred.size) { i ->
            val v = blurred[i]
            if (v <= cutoff) 0f else {
                val vv = ((v - cutoff) / (1f - cutoff)).coerceIn(0f, 1f)
                if (vv > 0f) anyNonZero = true
                vv
            }
        }
        val finalBuf = if (anyNonZero) adjusted else blurred

        val any = finalBuf.any { it > 0f }
        assertTrue("sparse tile should not be blank after cutoff fallback", any)
    }
}
