package com.adsamcik.tracker.map.heatmap

import com.adsamcik.tracker.map.heatmap.implementation.AgeWeightedHeatmap

/**
 * Centralized normalization policy: percentile selection, robust percentile estimation,
 * and temporal smoothing helpers for saturation.
 */
internal object NormalizationPolicy {

    /**
     * Choose percentile based on zoom and tile coverage (fraction of active pixels).
     * Higher zoom and lower coverage push percentile higher to preserve contrast.
     */
    fun percentileFor(zoom: Int, coverage: Float): Float = when {
        zoom <= 10 -> 0.90f
        coverage < 0.05f -> if (zoom <= 13) 0.92f else 0.96f
        coverage < 0.15f -> if (zoom <= 13) 0.95f else 0.98f
        else -> if (zoom <= 13) 0.95f else 0.985f
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
        tauMs: Float = 800f
    ): Pair<Float, Long> {
        if (previous == null || previousTimeMs == null) return raw to nowMs
        val dt = (nowMs - previousTimeMs).coerceAtLeast(0L).toFloat()
        val alpha = (dt / tauMs).coerceIn(0f, 1f)
        val smoothed = previous + (raw - previous) * alpha
        return smoothed to nowMs
    }

    /** Default blur radius heuristic based on active coverage fraction. */
    fun blurRadiusFor(coverage: Float): Int = when {
        coverage < 0.10f -> 2
        coverage < 0.25f -> 1
        else -> 0
    }

    /** Default cutoff threshold in normalized [0,1] based on coverage. */
    fun cutoffFor(coverage: Float): Float = if (coverage < 0.25f) 0.02f else 0.01f
}
