package com.adsamcik.tracker.map.graphics

import android.graphics.Bitmap
import android.os.Build
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Very small, size & config strict bitmap pool to reduce GC churn when repeatedly
 * rendering tiles / bitmaps. Bitmaps are cleared (eraseColor(0)) on release.
 * Not thread-safe for mutation outside provided API.
 */
class BitmapPool(
    private val maxSize: Int = 16,
) {
    private val lock = ReentrantLock()
    private val pool = ArrayDeque<Bitmap>(maxSize)

    fun acquire(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        lock.withLock {
            val it = pool.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (b.width == width && b.height == height && b.config == config && !b.isRecycled) {
                    it.remove()
                    return b
                }
            }
        }
        return Bitmap.createBitmap(width, height, config)
    }

    fun release(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        // Skip immutable bitmaps (can occur if misused from outside)
        if (!bitmap.isMutable) { bitmap.recycle(); return }
        // Guard against hardware bitmaps or other unsupported configs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) return
        lock.withLock {
            if (pool.size >= maxSize) {
                bitmap.recycle()
            } else {
                bitmap.eraseColor(0)
                pool.addLast(bitmap)
            }
        }
    }

    fun clear() {
        lock.withLock {
            pool.forEach { if (!it.isRecycled) it.recycle() }
            pool.clear()
        }
    }
}
