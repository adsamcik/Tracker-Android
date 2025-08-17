package com.adsamcik.tracker.map.heatmap.engine

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.map.heatmap.HeatmapTile
import com.adsamcik.tracker.map.heatmap.creators.HeatmapConfig
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileData
import com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted
import com.adsamcik.tracker.shared.map.CoordinateBounds

/**
 * A clean CPU-only API that renders heat tiles with support for supersampling and external quantiles.
 * Internally uses HeatmapTile metatile renderer.
 */
internal object HeatTileEngine {

    internal data class Palette(val mapper: (Double) -> Int) // placeholder for future OKLCH mapper

    internal data class Request(
        val z: Int, val x: Int, val y: Int,
        val timeMs: Long,
        val params: HeatParams,
        val colorScheme: HeatmapColorScheme = HeatmapColorScheme.viridis(),
        val supersample: Int = params.supersample.coerceAtLeast(1),
        val padExtra: Int = 0,
    val quantiles: QuantileStops? = null,
    val hsMetersBase: Double = params.hsMetersBase,
    val qService: QuantileService? = null,
    val qKey: QuantileService.WindowKey? = null,
    val collectQuantiles: Boolean = false
    )

    /** Resampled events provider, expected to return Δt events for the expanded bbox and time window. */
    internal fun interface FetchEvents { fun invoke(bboxWgs84: RectD, tFrom: Long, tTo: Long): List<Event> }

    internal fun renderHeatTile(req: Request, fetch: (bboxWgs84: RectD, tFrom: Long, tTo: Long) -> List<Event>): Bitmap {
        // Geometry
        val left = MapFunctions.toLon(req.x.toDouble(), req.z)
        val top = MapFunctions.toLat(req.y.toDouble(), req.z)
        val right = MapFunctions.toLon((req.x + 1).toDouble(), req.z)
        val bottom = MapFunctions.toLat((req.y + 1).toDouble(), req.z)
        val heatSize = HeatmapTile.BASE_HEATMAP_SIZE * req.supersample
        val tileCount = MapFunctions.getTileCount(req.z)
        val metersPerPixel = com.adsamcik.tracker.shared.base.extension.LocationExtensions.EARTH_CIRCUMFERENCE / tileCount.toDouble() / heatSize.toDouble()
        val radiusPx = kotlin.math.ceil(req.hsMetersBase / metersPerPixel).toInt().coerceAtLeast(1)
    val stamp = HeatmapStamp.generateGaussian(radiusPx)
        val cellSizeLat = (top - bottom) / heatSize
        val cellSizeLon = (right - left) / heatSize
        val expandLat = cellSizeLat * (radiusPx + 1)
        val expandLon = cellSizeLon * (radiusPx + 1)
    val bbox = RectD(left - expandLon, bottom - expandLat, right + expandLon, top + expandLat)

        // Time window and bins
        val htSec = (req.params.htMinutesBase * 60L)
        val dtMs = (req.params.dtMinutes * 60_000L).coerceAtLeast(60_000L)
        val tFrom = req.timeMs - 3 * htSec * 1000
        val tTo = req.timeMs + 3 * htSec * 1000
    val events = fetch(bbox, tFrom, tTo)
        if (events.isEmpty()) {
            return Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        }

        // Bin by 15-min and rasterize per-bin spatial images (Path A)
    val byBin = events.groupBy { it.timeMs / dtMs }
        val pad = (radiusPx + 1 + req.padExtra)
        val padded = heatSize + pad * 2
    val acc = FloatArray(padded * padded)

        val tStar = req.timeMs
        fun temporalWeight(binTime: Long): Float {
            val dt = (tStar - binTime).toDouble() / (req.params.htMinutesBase * 60.0 * 1000.0)
            return kotlin.math.exp(-(dt * dt)).toFloat()
        }

        for ((bin, list) in byBin) {
            val tBin = bin * dtMs + dtMs / 2
            val w = temporalWeight(tBin)
            if (w <= 1e-6f) continue
            val s = BinCache.getOrBuild(req.z, req.x, req.y, heatSize, pad, radiusPx, list, req.params.useFlows)
            // accumulate: acc += w * s
            var i = 0
            while (i < acc.size) { acc[i] += s[i] * w; i++ }
        }

        // Optional multiscale blending adds detail from sigma, 2*sigma, 4*sigma
    if (req.params.multiscale && req.params.multiscaleLevels > 1) {
            var level = 2
            while (level <= req.params.multiscaleLevels) {
                val rp = (radiusPx * level).coerceAtLeast(1)
        val s2 = BinCache.multiScaleAccumulate(req.z, req.x, req.y, heatSize, pad, rp, byBin, dtMs, ::temporalWeight, req.params.useFlows)
                // blend with decreasing weight
                val wBlend = 1f / level
                var i2 = 0
                while (i2 < acc.size) { acc[i2] += s2[i2] * wBlend; i2++ }
                level *= 2
            }
        }

        // Crop center
        val cropped = FloatArray(heatSize * heatSize)
        var dst = 0
        var srcRow = pad * padded + pad
        for (row in 0 until heatSize) {
            System.arraycopy(acc, srcRow, cropped, dst, heatSize)
            dst += heatSize
            srcRow += padded
        }

        // Optional extra blur
        val blurred = if (req.params.extraBlurPx > 0) gaussianBlur(cropped, heatSize, heatSize, req.params.extraBlurPx) else cropped

        // Optionally collect quantile samples for a shared window service
        if (req.collectQuantiles && req.qService != null && req.qKey != null) {
            val samples = sampleValues(blurred, 2048)
            if (samples.isNotEmpty()) req.qService.add(req.qKey, samples)
        }

        // Normalize with fixed quantiles or per-tile percentile
    val qStops = req.quantiles ?: (if (req.qService != null && req.qKey != null) req.qService.snapshot(req.qKey) else null)
    val saturation = qStops?.pickMax()?.toFloat() ?: run {
            val p = estimatePercentileHist(blurred, 0.985f)
            if (p > 0f) p else estimatePercentile(blurred, 0.985f)
        }

        // Soft floor and transform
        val coverage = activeCoverage(cropped)
        val cutoff = if (coverage < 0.25f) 0.055f else 0.03f
        val normalized = FloatArray(cropped.size)
        val sat = if (saturation > 0f) saturation else 1f
        var idx = 0
        while (idx < blurred.size) {
            val v = blurred[idx]
            val n = (kotlin.math.min(v, sat) / sat)
            normalized[idx] = if (n <= cutoff) 0f else ((n - cutoff) / (1f - cutoff)).coerceIn(0f, 1f)
            idx++
        }

        // Colorize
        val argb = renderFromNormalized(req.colorScheme, normalized, heatSize, heatSize, true, 1f)

        // Build bitmap and supersample down to 256
    val bmp = Bitmap.createBitmap(heatSize, heatSize, Bitmap.Config.ARGB_8888)
        bmp.setPixels(argb, 0, heatSize, 0, 0, heatSize, heatSize)
        val target = 256
        val scaled = if (heatSize != target) bmp.scale(target, target, true) else bmp
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    // Public small API: precompute bin buffer and accumulate multiple bins
    internal fun precomputeBinBuffer(
        req: Request,
        events: List<Event>
    ): FloatArray {
        val heatSize = HeatmapTile.BASE_HEATMAP_SIZE * req.supersample
        val tileCount = MapFunctions.getTileCount(req.z)
        val metersPerPixel = com.adsamcik.tracker.shared.base.extension.LocationExtensions.EARTH_CIRCUMFERENCE / tileCount.toDouble() / heatSize.toDouble()
        val radiusPx = kotlin.math.ceil(req.hsMetersBase / metersPerPixel).toInt().coerceAtLeast(1)
        val pad = (radiusPx + 1 + req.padExtra)
        return BinCache.precomputeBin(req.z, req.x, req.y, heatSize, pad, radiusPx, events, req.params.useFlows)
    }

    internal fun accumulatePrecomputed(target: FloatArray, bins: List<Pair<FloatArray, Float>>) =
        BinCache.accumulateBins(target, bins)

    // --- Bin cache and rasterization ---
    private object BinCache {
        private data class Key(val z: Int, val x: Int, val y: Int, val size: Int, val pad: Int, val radius: Int, val binHash: Int, val flows: Boolean)
        private val lru = object : java.util.LinkedHashMap<Key, FloatArray>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, FloatArray>?): Boolean = size > 128
        }

        fun getOrBuild(
            z: Int,
            x: Int,
            y: Int,
            heatSize: Int,
            pad: Int,
            radiusPx: Int,
            events: List<Event>,
            useFlows: Boolean
        ): FloatArray {
            val binHash = events.hashCode()
            val key = Key(z, x, y, heatSize, pad, radiusPx, binHash, useFlows)
            lru[key]?.let { return it }
            val buf = rasterizeBin(z, x, y, heatSize, pad, radiusPx, events, useFlows)
            lru[key] = buf
            return buf
        }

        // Public API to precompute a bin buffer (S_b) to be reused across frames (fast scrubbing)
        fun precomputeBin(
            z: Int, x: Int, y: Int,
            heatSize: Int, pad: Int, radiusPx: Int,
            events: List<Event>, useFlows: Boolean
        ): FloatArray = rasterizeBin(z, x, y, heatSize, pad, radiusPx, events, useFlows)

        // Public API to accumulate a time-weighted sum over multiple precomputed bins
        fun accumulateBins(
            target: FloatArray,
            bins: List<Pair<FloatArray, Float /*weight*/>>
        ) {
            var i = 0
            while (i < target.size) {
                var acc = target[i]
                var j = 0
                while (j < bins.size) { acc += bins[j].first[i] * bins[j].second; j++ }
                target[i] = acc
                i++
            }
        }

        // Helper used for multiscale accumulation in one shot for given radius
        fun multiScaleAccumulate(
            z: Int, x: Int, y: Int,
            heatSize: Int, pad: Int, radiusPx: Int,
            byBin: Map<Long, List<Event>>,
            dtMs: Long,
            temporal: (Long) -> Float,
            useFlows: Boolean
        ): FloatArray {
            val out = FloatArray((heatSize + pad * 2) * (heatSize + pad * 2))
            for ((bin, list) in byBin) {
                val tBin = bin * dtMs + dtMs / 2
                val w = temporal(tBin)
                if (w <= 1e-6f) continue
                val s = rasterizeBin(z, x, y, heatSize, pad, radiusPx, list, useFlows)
                var i = 0
                while (i < out.size) { out[i] += s[i] * w; i++ }
            }
            return out
        }

        private fun rasterizeBin(
            z: Int, x: Int, y: Int,
            heatSize: Int, pad: Int, radiusPx: Int,
            events: List<Event>, useFlows: Boolean
        ): FloatArray {
            val padded = heatSize + pad * 2
            val out = FloatArray(padded * padded)
            val tileCount = MapFunctions.getTileCount(z)
            val stamp = HeatmapStamp.generateGaussian(radiusPx)
            // Points
            for (e in events) {
                val tx = MapFunctions.toTileX(e.lon, tileCount)
                val ty = MapFunctions.toTileY(e.lat, tileCount)
                val px = ((tx - x) * heatSize).toInt() + pad
                val py = ((ty - y) * heatSize).toInt() + pad
                deposit(out, padded, px, py, stamp, e.weight.toFloat())
            }
            // Flows (segments between successive events in time)
            if (useFlows && events.size > 1) {
                val sorted = events.sortedBy { it.timeMs }
                // sigma approx radiusPx/2 for softness along segment
                val sigma = kotlin.math.max(1f, radiusPx * 0.5f)
                for (i in 1 until sorted.size) {
                    val a = sorted[i - 1]
                    val b = sorted[i]
                    val tx0 = MapFunctions.toTileX(a.lon, tileCount)
                    val ty0 = MapFunctions.toTileY(a.lat, tileCount)
                    val tx1 = MapFunctions.toTileX(b.lon, tileCount)
                    val ty1 = MapFunctions.toTileY(b.lat, tileCount)
                    val x0p = ((tx0 - x) * heatSize).toInt() + pad
                    val y0p = ((ty0 - y) * heatSize).toInt() + pad
                    val x1p = ((tx1 - x) * heatSize).toInt() + pad
                    val y1p = ((ty1 - y) * heatSize).toInt() + pad
                    drawSegmentGaussian(out, padded, x0p, y0p, x1p, y1p, sigma, ((a.weight + b.weight) * 0.5).toFloat())
                }
            }
            return out
        }

        private fun deposit(buf: FloatArray, stride: Int, x: Int, y: Int, stamp: HeatmapStamp, weight: Float) {
            val halfH = stamp.height / 2
            val halfW = stamp.width / 2
            val x0 = if (x < halfW) halfW - x else 0
            val y0 = if (y < halfH) halfH - y else 0
            val x1 = if (x + halfW < stride) stamp.width else halfW + stride - x
            val y1 = if (y + halfH < stride) stamp.height else halfH + stride - y
            var iy = y0
            while (iy < y1) {
                var pix = (y + iy - halfH) * stride + (x + x0) - halfW
                var st = iy * stamp.width + x0
                var ix = x0
                while (ix < x1) {
                    val sv = stamp.stampData[st]
                    if (sv > 0f) buf[pix] += sv * weight
                    pix++; st++; ix++
                }
                iy++
            }
        }

        private fun drawSegmentGaussian(
            buf: FloatArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int, sigma: Float, weight: Float
        ) {
            val minX = kotlin.math.min(x0, x1) - sigma.toInt() - 2
            val maxX = kotlin.math.max(x0, x1) + sigma.toInt() + 2
            val minY = kotlin.math.min(y0, y1) - sigma.toInt() - 2
            val maxY = kotlin.math.max(y0, y1) + sigma.toInt() + 2
            val twoSigma2 = 2f * sigma * sigma
            val dx = (x1 - x0).toFloat(); val dy = (y1 - y0).toFloat()
            val len2 = dx * dx + dy * dy
            val invLen2 = if (len2 > 0f) 1f / len2 else 0f
            val xMin = minX.coerceAtLeast(0); val xMax = maxX.coerceAtMost(stride - 1)
            val yMin = minY.coerceAtLeast(0); val yMax = maxY.coerceAtMost(stride - 1)
            var y = yMin
            while (y <= yMax) {
                var x = xMin
                while (x <= xMax) {
                    // distance from pixel center to segment
                    val px = x - x0; val py = y - y0
                    var t = (px * dx + py * dy) * invLen2
                    t = t.coerceIn(0f, 1f)
                    val projX = x0 + dx * t
                    val projY = y0 + dy * t
                    val ddx = x - projX
                    val ddy = y - projY
                    val dist2 = ddx * ddx + ddy * ddy
                    val v = kotlin.math.exp(-dist2 / twoSigma2) * weight
                    if (v > 1e-6f) buf[y * stride + x] += v.toFloat()
                    x++
                }
                y++
            }
        }
    }

    // --- Shared helpers copied from HeatmapTile (no Android deps) ---
    private fun gaussianBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius <= 0) return src
        val sigma = radius / 1.5f
        val kernelRadius = radius
        val kernelSize = kernelRadius * 2 + 1
        val kernel = FloatArray(kernelSize)
        var sum = 0f
        for (i in -kernelRadius..kernelRadius) {
            val v = kotlin.math.exp(-(i * i) / (2f * sigma * sigma))
            kernel[i + kernelRadius] = v
            sum += v
        }
        for (i in 0 until kernelSize) kernel[i] /= sum
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0f; var k = 0; var xi = x - kernelRadius
                while (k < kernelSize) { val xc = xi.coerceIn(0, w - 1); acc += src[row + xc] * kernel[k]; k++; xi++ }
                tmp[row + x] = acc
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                var acc = 0f; var k = 0; var yi = y - kernelRadius
                while (k < kernelSize) { val yc = yi.coerceIn(0, h - 1); acc += tmp[yc * w + x] * kernel[k]; k++; yi++ }
                out[y * w + x] = acc
            }
        }
        return out
    }

    private fun estimatePercentileHist(buffer: FloatArray, p: Float, bins: Int = 1024): Float {
        if (buffer.isEmpty()) return 0f
        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        var i = 0
        while (i < buffer.size) { val v = buffer[i]; if (v < minV) minV = v; if (v > maxV) maxV = v; i++ }
        if (!minV.isFinite() || !maxV.isFinite() || minV == maxV) return maxV
        val hist = IntArray(bins)
        val scale = (bins - 1) / (maxV - minV)
        i = 0
        while (i < buffer.size) { val v = buffer[i]; val b = (((v - minV) * scale).toInt()).coerceIn(0, bins - 1); hist[b]++; i++ }
        val target = (p.coerceIn(0f,1f) * buffer.size).toInt().coerceAtLeast(1)
        var cum = 0
        var b = 0
        while (b < bins) { cum += hist[b]; if (cum >= target) return minV + b / scale; b++ }
        return maxV
    }

    private fun estimatePercentile(buffer: FloatArray, p: Float, maxSamples: Int = 2048): Float {
        if (buffer.isEmpty()) return 0f
        val total = buffer.size
        val step = (total / maxSamples).coerceAtLeast(1)
        val arr = FloatArray((total + step - 1) / step)
        var idx = 0
        var i = 0
        while (i < total) { val v = buffer[i]; if (v > 0f) { arr[idx++] = v }; i += step }
        if (idx == 0) return 0f
        java.util.Arrays.sort(arr, 0, idx)
        val pos = ((idx - 1) * p.coerceIn(0f, 1f)).toInt()
        return arr[pos]
    }

    private fun activeCoverage(buffer: FloatArray, epsilon: Float = 1e-6f): Float {
        if (buffer.isEmpty()) return 0f
        val total = buffer.size
        var active = 0
        var i = 0
        while (i < total) { if (buffer[i] > epsilon) active++; i++ }
        return active.toFloat() / total.toFloat()
    }

    private fun renderFromNormalized(
        colorScheme: HeatmapColorScheme,
        normalized: FloatArray,
        width: Int,
        height: Int,
        alphaFromNormalized: Boolean,
        opacity: Float
    ): IntArray {
        val buffer = IntArray(width * height)
        var index = 0
        val maxIdx = (colorScheme.colors.size - 1).coerceAtLeast(0)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val n = normalized[index].coerceIn(0f, 1f)
                val colorId = (maxIdx * n).toInt().coerceIn(0, maxIdx)
                val base = colorScheme.colors[colorId]
                val a = if (alphaFromNormalized) {
                    (opacity * n * 255f).toInt().coerceIn(0, 255)
                } else 255
                buffer[index] = (a shl 24) or (base and 0x00FFFFFF)
                index++
            }
        }
        return buffer
    }

    private fun sampleValues(buffer: FloatArray, maxSamples: Int = 2048): DoubleArray {
        if (buffer.isEmpty()) return DoubleArray(0)
        val step = (buffer.size / maxSamples).coerceAtLeast(1)
        var count = 0
        var i = 0
        while (i < buffer.size) { if (buffer[i] > 0f) count++; i += step }
        if (count == 0) return DoubleArray(0)
        val out = DoubleArray(count)
        var idx = 0
        i = 0
        while (i < buffer.size) { val v = buffer[i]; if (v > 0f) { out[idx++] = v.toDouble() }; i += step }
        return out
    }
}
