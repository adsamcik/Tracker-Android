package com.adsamcik.tracker.map.heatmap

/** Preset value curves for remapping normalized intensity. */
object ValueCurves {
    /** Identity mapping. */
    val identity: (Float) -> Float = { it }

    /** Smoothstep curve (quintic) with tunable blend t between linear and smooth. */
    fun smoothstep(t: Float = 0.6f): (Float) -> Float = { v ->
        val s = v * v * v * (v * (v * 6f - 15f) + 10f)
        (1f - t) * v + t * s
    }

    /** Emphasize mid-range values. */
    fun midrange(gamma: Float = 0.8f): (Float) -> Float = { v ->
        val g = gamma.coerceIn(0.1f, 3f)
        val a = Math.pow(v.toDouble(), g.toDouble()).toFloat()
        val b = Math.pow(v.toDouble(), (2.0 - g)).toFloat()
        (0.5f * (a + b)).coerceIn(0f, 1f)
    }
}
