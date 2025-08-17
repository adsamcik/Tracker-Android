package com.adsamcik.tracker.map.heatmap.engine

/** Fixed quantile stops for normalization in [0,1]. */
data class QuantileStops(
    val p50: Double,
    val p75: Double,
    val p90: Double,
    val p99: Double
) {
    fun pickMax(): Double = p99
}

/** A tiny streaming estimator using reservoir sampling (placeholder for t-digest/KLL). */
class StreamingQuantiles(private val capacity: Int = 4096) {
    private val buf = DoubleArray(capacity)
    private var count = 0
    private val rnd = java.util.Random(42)

    fun add(v: Double) {
        val c = count
        if (c < capacity) buf[c] = v else {
            val i = rnd.nextInt(c + 1)
            if (i < capacity) buf[i] = v
        }
        count = c + 1
    }

    fun snapshot(): QuantileStops {
        val n = kotlin.math.min(count, capacity)
        if (n == 0) return QuantileStops(0.0, 0.0, 0.0, 0.0)
        val arr = buf.copyOf(n)
        java.util.Arrays.sort(arr)
        fun q(p: Double): Double = arr[((n - 1) * p).toInt().coerceIn(0, n - 1)]
        return QuantileStops(q(0.50), q(0.75), q(0.90), q(0.99))
    }
}
