package com.adsamcik.tracker.map.heatmap

import java.util.concurrent.ConcurrentHashMap

/**
 * Simple thread-safe pool for reusing float arrays to reduce GC pressure during
 * heatmap blur and normalization operations. Arrays are keyed by size and cleared on return.
 */
internal object ScratchBufferPool {
    private val pools = ConcurrentHashMap<Int, ArrayDeque<FloatArray>>()
    private const val MAX_POOL_SIZE = 8

    fun acquire(size: Int): FloatArray {
        val pool = pools[size]
        if (pool != null) {
            synchronized(pool) {
                if (pool.isNotEmpty()) {
                    return pool.removeFirst()
                }
            }
        }
        return FloatArray(size)
    }

    fun release(array: FloatArray) {
        val size = array.size
        // Clear the array for reuse
        array.fill(0f)
        
        val pool = pools.getOrPut(size) { ArrayDeque() }
        synchronized(pool) {
            if (pool.size < MAX_POOL_SIZE) {
                pool.addLast(array)
            }
        }
    }

    fun clear() {
        pools.clear()
    }
}
