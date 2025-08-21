package com.adsamcik.tracker.map.heatmap

import com.adsamcik.tracker.map.heatmap.implementation.AgeWeightedHeatmap

/**
 * Centralized normalization policy: percentile selection, robust percentile estimation,
 * and temporal smoothing helpers for saturation.
 */
internal object NormalizationPolicy {

    // Coverage thresholds for adaptive processing
    private const val LOW_COVERAGE_THRESHOLD = 0.05f
    private const val MEDIUM_COVERAGE_THRESHOLD = 0.15f
    private const val BLUR_COVERAGE_THRESHOLD = 0.25f
    
    // Zoom-based percentile selection
    private const val LOW_ZOOM_THRESHOLD = 10
    private const val MID_ZOOM_THRESHOLD = 13
    
    // Base percentiles for different scenarios
    private const val BASE_PERCENTILE_LOW_ZOOM = 0.90f
    private const val PERCENTILE_LOW_COVERAGE_MID_ZOOM = 0.92f
    private const val PERCENTILE_LOW_COVERAGE_HIGH_ZOOM = 0.96f
    private const val PERCENTILE_MED_COVERAGE_MID_ZOOM = 0.95f
    private const val PERCENTILE_MED_COVERAGE_HIGH_ZOOM = 0.98f
    private const val PERCENTILE_HIGH_COVERAGE_MID_ZOOM = 0.95f
    private const val PERCENTILE_HIGH_COVERAGE_HIGH_ZOOM = 0.985f
    
    // Blur radius settings
    private const val BLUR_RADIUS_LOW_COVERAGE = 2
    private const val BLUR_RADIUS_MEDIUM_COVERAGE = 1
    private const val BLUR_RADIUS_HIGH_COVERAGE = 0
    
    // Cutoff thresholds
    private const val CUTOFF_LOW_COVERAGE = 0.02f
    private const val CUTOFF_HIGH_COVERAGE = 0.01f
    
    // EMA smoothing parameters
    private const val DEFAULT_SMOOTHING_TAU_MS = 800f

    /**
     * Choose percentile based on zoom and tile coverage (fraction of active pixels).
     * Higher zoom and lower coverage push percentile higher to preserve contrast.
     */
    fun percentileFor(zoom: Int, coverage: Float): Float = when {
        zoom <= LOW_ZOOM_THRESHOLD -> BASE_PERCENTILE_LOW_ZOOM
        coverage < LOW_COVERAGE_THRESHOLD -> if (zoom <= MID_ZOOM_THRESHOLD) PERCENTILE_LOW_COVERAGE_MID_ZOOM else PERCENTILE_LOW_COVERAGE_HIGH_ZOOM
        coverage < MEDIUM_COVERAGE_THRESHOLD -> if (zoom <= MID_ZOOM_THRESHOLD) PERCENTILE_MED_COVERAGE_MID_ZOOM else PERCENTILE_MED_COVERAGE_HIGH_ZOOM
        else -> if (zoom <= MID_ZOOM_THRESHOLD) PERCENTILE_HIGH_COVERAGE_MID_ZOOM else PERCENTILE_HIGH_COVERAGE_HIGH_ZOOM
    }

    /**
     * Estimate p-th percentile using a histogram-based approach with fallback to sampling.
     * Falls back to maxHeat when buffers are empty. Never returns <= 0 when maxHeat > 0.
     */
    fun robustPercentile(heatmap: AgeWeightedHeatmap, p: Float): Float {
        val histP = heatmap.estimatePercentileHist(p)
        val nonZeroP = if (histP <= 0f) heatmap.estimatePercentile(p) else histP
        val fallback = if (heatmap.maxHeat > 0f) heatmap.maxHeat else 1f
        return if (nonZeroP > 0f) nonZeroP else fallback
    }

    /**
     * Choose final saturation using optional override; ensures strictly positive output.
     */
    fun chooseSaturation(local: Float, override: Float?, minFallback: Float = 1f): Float {
        val v = override ?: local
        return if (v > 0f) v else local.coerceAtLeast(minFallback)
    }

    /**
     * Exponential moving average smoothing for saturation over time.
     * Returns the new smoothed value and timestamp to store.
     */
    fun smoothSaturation(
        previous: Float?,
        previousTimeMs: Long?,
        raw: Float,
        nowMs: Long,
        tauMs: Float = DEFAULT_SMOOTHING_TAU_MS
    ): Pair<Float, Long> {
        if (previous == null || previousTimeMs == null) return raw to nowMs
        val dt = (nowMs - previousTimeMs).coerceAtLeast(0L).toFloat()
        val alpha = (dt / tauMs).coerceIn(0f, 1f)
        val smoothed = previous + (raw - previous) * alpha
        return smoothed to nowMs
    }

    /** Default blur radius heuristic based on active coverage fraction. */
    fun blurRadiusFor(coverage: Float): Int = when {
        coverage < LOW_COVERAGE_THRESHOLD -> BLUR_RADIUS_LOW_COVERAGE
        coverage < BLUR_COVERAGE_THRESHOLD -> BLUR_RADIUS_MEDIUM_COVERAGE
        else -> BLUR_RADIUS_HIGH_COVERAGE
    }

    /** Default cutoff threshold in normalized [0,1] based on coverage. */
    fun cutoffFor(coverage: Float): Float = if (coverage < BLUR_COVERAGE_THRESHOLD) CUTOFF_LOW_COVERAGE else CUTOFF_HIGH_COVERAGE

    /** Separable Gaussian blur on a float buffer using pooled scratch arrays. */
    fun gaussianBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius <= 0) return src
        val sigma = radius / 1.5f
        val kernelRadius = radius
        val kernelSize = kernelRadius * 2 + 1
        val kernel = FloatArray(kernelSize)
        var sum = 0f
        var i = -kernelRadius
        var kIndex = 0
        while (i <= kernelRadius) {
            val v = kotlin.math.exp(-(i * i) / (2f * sigma * sigma))
            kernel[kIndex++] = v
            sum += v
            i++
        }
        var j = 0
        while (j < kernelSize) { kernel[j] /= sum; j++ }

        val arraySize = w * h
        val tmp = com.adsamcik.tracker.map.heatmap.ScratchBufferPool.acquire(arraySize)
        val out = com.adsamcik.tracker.map.heatmap.ScratchBufferPool.acquire(arraySize)

        try {
            // Horizontal
            var y = 0
            while (y < h) {
                val row = y * w
                var x = 0
                while (x < w) {
                    var acc = 0f
                    var k = 0
                    var xi = x - kernelRadius
                    while (k < kernelSize) {
                        val xc = if (xi < 0) 0 else if (xi >= w) w - 1 else xi
                        acc += src[row + xc] * kernel[k]
                        k++; xi++
                    }
                    tmp[row + x] = acc
                    x++
                }
                y++
            }
            // Vertical
            var x = 0
            while (x < w) {
                var y2 = 0
                while (y2 < h) {
                    var acc = 0f
                    var k = 0
                    var yi = y2 - kernelRadius
                    while (k < kernelSize) {
                        val yc = if (yi < 0) 0 else if (yi >= h) h - 1 else yi
                        acc += tmp[yc * w + x] * kernel[k]
                        k++; yi++
                    }
                    out[y2 * w + x] = acc
                    y2++
                }
                x++
            }
            return out.copyOf()
        } finally {
            com.adsamcik.tracker.map.heatmap.ScratchBufferPool.release(tmp)
            com.adsamcik.tracker.map.heatmap.ScratchBufferPool.release(out)
        }
    }
}
