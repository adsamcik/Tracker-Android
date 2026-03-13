package com.adsamcik.tracker.map.tiles

/**
 * Thread-safe LRU cache for tile GeoJSON data.
 * Keyed by "layerId/z/x/y". Supports per-layer invalidation.
 */
class TileCache(private val maxSize: Int = 256) {

    private val cache = object : LinkedHashMap<String, String>(
        /* initialCapacity */ maxSize / 2,
        /* loadFactor */ 0.75f,
        /* accessOrder */ true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            return size > maxSize
        }
    }

    /** Build a cache key from tile coordinates and layer id. */
    fun key(layerId: String, z: Int, x: Int, y: Int): String = "$layerId/$z/$x/$y"

    /** Get cached GeoJSON for [key], or null on miss. */
    fun get(key: String): String? = synchronized(cache) { cache[key] }

    /** Store GeoJSON for [key]. */
    fun put(key: String, geoJson: String): Unit = synchronized(cache) { cache[key] = geoJson }

    /** Invalidate all tiles for a given [layerId]. */
    fun invalidateLayer(layerId: String): Unit = synchronized(cache) {
        cache.keys.removeAll { it.startsWith("$layerId/") }
    }

    /** Clear the entire cache. */
    fun clear(): Unit = synchronized(cache) { cache.clear() }

    /** Current number of cached tiles. */
    fun size(): Int = synchronized(cache) { cache.size }
}
