package com.adsamcik.tracker.map.ui

import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig

/**
 * Byte-bounded LRU cache for per-layer [MapLibreLayerConfig] payloads keyed by viewport bucket.
 *
 * Replaces the original 5-entry [LinkedHashMap] used by [LayerController]. The old cache held
 * up to 5 buckets per layer and could retain ~12 MB UTF-16 GeoJSON strings each, growing into
 * the hundreds of megabytes across multiple heatmap layers (R2 round 5, finding #3).
 *
 * Semantics:
 *  - Access-ordered LRU. Reads via [get] and overwrites via [put] move the entry to MRU.
 *  - On insert, oldest entries are evicted until both the running byte total and entry count fit
 *    within their limits, or until only the just-inserted (MRU) entry remains. The single MRU entry
 *    is always retained even if it alone exceeds the budget — this preserves the original
 *    perf intent (skip re-encode on rapid back-and-forth pan within the same viewport)
 *    while keeping retention bounded in steady state.
 *  - `null` entries are tracked (a viewport that legitimately produces no config caches as
 *    null so the next refresh in the same bucket can skip the reload) and receive the fixed
 *    per-entry charge, so empty buckets cannot become unbounded free entries.
 *  - Byte size includes a conservative fixed entry charge plus `geoJson.length * 2` (UTF-16)
 *    across payloads, recursing into Composite.
 *
 * Not thread-safe by itself: [LayerController] callers already serialize cache access
 * through its single-threaded coroutine context, mirroring the previous behaviour.
 */
internal class ViewportConfigCache(
    private val maxBytes: Long,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    init {
        require(maxBytes > 0) { "maxBytes must be positive, was $maxBytes" }
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
    }

    private val entries = LinkedHashMap<LayerController.ViewportCacheKey, MapLibreLayerConfig?>(
        /* initialCapacity */ 4,
        /* loadFactor */ 0.75f,
        /* accessOrder */ true,
    )

    private var currentBytes: Long = 0L

    /** Total estimated bytes retained across cached entries. */
    fun byteSize(): Long = currentBytes

    /** Number of cached entries (including null entries). */
    fun size(): Int = entries.size

    /** True if [key] has been inserted (value may be null). */
    fun containsKey(key: LayerController.ViewportCacheKey): Boolean = entries.containsKey(key)

    /**
     * Look up [key]. A returned `null` may either mean "absent" or "cached null".
     * Use [containsKey] to distinguish.
     */
    fun get(key: LayerController.ViewportCacheKey): MapLibreLayerConfig? = entries[key]

    /**
     * Insert or update [key] with [value]. Evicts older entries until under [maxBytes],
     * always retaining at least the inserted (MRU) entry. Returns the inserted value.
     */
    fun put(key: LayerController.ViewportCacheKey, value: MapLibreLayerConfig?): MapLibreLayerConfig? {
        val hadPrevious = entries.containsKey(key)
        val previous = entries.remove(key)
        if (hadPrevious) {
            currentBytes -= estimateBytes(previous)
        }
        entries[key] = value
        currentBytes += estimateBytes(value)

        evictUntilWithinBudget(protect = key)
        return value
    }

    /** Drop every cached entry and reset the byte total. */
    fun clear() {
        entries.clear()
        currentBytes = 0L
    }

    private fun evictUntilWithinBudget(protect: LayerController.ViewportCacheKey) {
        if (currentBytes <= maxBytes && entries.size <= maxEntries) return
        val iterator = entries.entries.iterator()
        while (
            iterator.hasNext() &&
            (currentBytes > maxBytes || entries.size > maxEntries) &&
            entries.size > 1
        ) {
            val entry = iterator.next()
            if (entry.key == protect) continue
            currentBytes -= estimateBytes(entry.value)
            iterator.remove()
        }
    }

    companion object {
        /**
         * Estimate retained bytes for one cache entry. Every entry includes a conservative fixed
         * charge for the key, LinkedHashMap node, and config wrapper; payload strings use two
         * bytes per UTF-16 `Char`.
         */
        fun estimateBytes(config: MapLibreLayerConfig?): Long =
            ENTRY_OVERHEAD_BYTES + estimatePayloadBytes(config)

        private fun estimatePayloadBytes(config: MapLibreLayerConfig?): Long = when (config) {
            null -> 0L
            is MapLibreLayerConfig.Heatmap -> config.geoJson.length.toLong() * 2L
            is MapLibreLayerConfig.HeatLine -> config.geoJson.length.toLong() * 2L
            is MapLibreLayerConfig.Line -> config.geoJson.length.toLong() * 2L
            is MapLibreLayerConfig.Fill -> config.geoJson.length.toLong() * 2L
            is MapLibreLayerConfig.FillExtrusion -> config.geoJson.length.toLong() * 2L
            is MapLibreLayerConfig.Circle -> config.geoJson.length.toLong() * 2L
            is MapLibreLayerConfig.GradientLine -> config.geoJson.length.toLong() * 2L
            is MapLibreLayerConfig.Symbol -> config.geoJson.length.toLong() * 2L
            is MapLibreLayerConfig.Composite -> config.layers.sumOf { estimatePayloadBytes(it) }
        }

        internal const val DEFAULT_MAX_ENTRIES = 64
        internal const val ENTRY_OVERHEAD_BYTES = 256L
    }
}
