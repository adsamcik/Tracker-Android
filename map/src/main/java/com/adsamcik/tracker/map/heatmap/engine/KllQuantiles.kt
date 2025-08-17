package com.adsamcik.tracker.map.heatmap.engine

/**
 * Lightweight KLL-like quantile sketch for doubles.
 * Not a full spec implementation, but adequate for window-level normalization.
 */
class KllSketch(private val k: Int = 256) {
    private val levels = ArrayList<DoubleArray>()
    private val sizes = ArrayList<Int>()
    private var n = 0L

    fun add(v: Double) {
        n++
        if (levels.isEmpty()) { levels.add(DoubleArray(k)); sizes.add(0) }
        insertAtLevel(0, v)
    }

    private fun insertAtLevel(level: Int, v: Double) {
        while (levels.size <= level) { levels.add(DoubleArray(k)); sizes.add(0) }
        val arr = levels[level]
        val size = sizes[level]
        if (size < k) {
            arr[size] = v
            sizes[level] = size + 1
            return
        }
        // compress this level (deterministic halve) and push up along with v
        java.util.Arrays.sort(arr, 0, size)
        val up = DoubleArray(k)
        var upSize = 0
        var i = 1 // keep odd indices to approximate random halve deterministically
        while (i < size) { up[upSize++] = arr[i]; i += 2 }
        levels[level] = DoubleArray(k); sizes[level] = 0
        insertAtLevel(level + 1, v)
        var j = 0
        while (j < upSize) { insertAtLevel(level + 1, up[j]); j++ }
    }

    fun estimateQuantile(p: Double): Double {
        val total = sizes.sum()
        if (total == 0) return 0.0
        val merged = DoubleArray(total)
        var idx = 0
        for (l in levels.indices) {
            val s = sizes[l]
            if (s > 0) {
                java.util.Arrays.sort(levels[l], 0, s)
                java.lang.System.arraycopy(levels[l], 0, merged, idx, s)
                idx += s
            }
        }
        java.util.Arrays.sort(merged, 0, idx)
        val pos = ((idx - 1) * p.coerceIn(0.0, 1.0)).toInt()
        return merged[pos]
    }

    fun snapshotStops(): QuantileStops = QuantileStops(
        estimateQuantile(0.50), estimateQuantile(0.75), estimateQuantile(0.90), estimateQuantile(0.99)
    )
}

/** Shared, window-scoped quantile service. */
class QuantileService {
    data class WindowKey(val z: Int, val tFrom: Long, val tTo: Long)
    private val map = HashMap<WindowKey, KllSketch>()

    @Synchronized fun begin(key: WindowKey) { map.putIfAbsent(key, KllSketch()) }

    @Synchronized fun add(key: WindowKey, values: DoubleArray) {
        val s = map[key] ?: return
        var i = 0
        while (i < values.size) { s.add(values[i]); i++ }
    }

    @Synchronized fun snapshot(key: WindowKey): QuantileStops? = map[key]?.snapshotStops()

    @Synchronized fun end(key: WindowKey) { map.remove(key) }
}
