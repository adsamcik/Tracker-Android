package com.adsamcik.tracker.map.tiles

import android.util.Log
import com.adsamcik.tracker.map.graphics.BitmapPool
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A generic tile provider with LRU cache, timeout and optional BitmapPool support.
 * Subclasses provide tile generation via generateTile(x, y, zoom, pool, budgets).
 */
abstract class OptimizedTileProvider(
    private val performanceManager: PerformanceManager = PerformanceManager(),
    private val executor: ExecutorService = EXECUTOR
) : TileProvider {

    data class Key(val x: Int, val y: Int, val zoom: Int)

    private val lock = ReentrantLock()
    private var maxCacheTiles: Int = 64
    private var tileRenderTimeoutMs: Long = 3000

    private var bitmapPool: BitmapPool? = null
    fun setBitmapPool(pool: BitmapPool?) { bitmapPool = pool }

    private val cache: MutableMap<Key, Tile> = object : LinkedHashMap<Key, Tile>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Tile>?): Boolean = size > maxCacheTiles
    }

    private val inFlight = AtomicInteger(0)
    var tileRequestCountListener: ((Int) -> Unit)? = null

    @Volatile
    private var quality: Float = 1.0f

    /** Allow subclasses to read current quality without exposing the backing field. */
    protected fun currentQuality(): Float = quality

    fun updateQuality(q: Float) {
        if (q == quality) return
        quality = q
        val budgets = performanceManager.budgets(q)
        lock.withLock {
            tileRenderTimeoutMs = budgets.tileRenderTimeout
            maxCacheTiles = budgets.maxCacheSize
            cache.clear()
        }
    }

    override fun getTile(x: Int, y: Int, zoom: Int): Tile {
        val before = inFlight.incrementAndGet()
        tileRequestCountListener?.invoke(before)
        try {
            val key = Key(x, y, zoom)
            lock.withLock { cache[key] }?.let { return it }

            val budgets = performanceManager.budgets(quality)
            val future = executor.submit(Callable {
                try {
                    generateTile(x, y, zoom, bitmapPool, budgets)
                } catch (e: OutOfMemoryError) {
                    System.gc()
                    // Simple sleep is appropriate here since we're in an executor thread,
                    // and runBlocking in a Callable can cause deadlocks under load.
                    Thread.sleep(250)
                    generateTile(x, y, zoom, bitmapPool, budgets)
                }
            })

            val tile = try {
                future.get(tileRenderTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                future.cancel(true)
                Log.w(TAG, "Tile generation timeout or error for ($x,$y,$zoom): ${t.localizedMessage}")
                TileProvider.NO_TILE
            }
            lock.withLock { cache[key] = tile }
            return tile
        } finally {
            val after = inFlight.decrementAndGet()
            tileRequestCountListener?.invoke(after)
        }
    }

    /** Implement actual tile generation. Return NO_TILE to skip. */
    protected abstract fun generateTile(
        x: Int,
        y: Int,
        zoom: Int,
        pool: BitmapPool?,
        budgets: PerformanceManager.PerformanceBudgets
    ): Tile

    fun clearCache() {
        lock.withLock { cache.clear() }
    }

    fun trimMemory() {
        clearCache()
        bitmapPool?.clear()
    }

    companion object {
        private const val TAG = "OptTileProvider"
        private val EXECUTOR: ExecutorService = Executors.newCachedThreadPool()
    }
}
