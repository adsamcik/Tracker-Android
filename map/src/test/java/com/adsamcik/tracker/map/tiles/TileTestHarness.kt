package com.adsamcik.tracker.map.tiles

import com.adsamcik.tracker.map.graphics.BitmapPool
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.google.android.gms.maps.model.Tile
import java.security.MessageDigest

/**
 * Headless helper to render a single tile via a TileProvider and return bytes & a checksum.
 * Intended for lightweight visual regression checks. Not a full golden system yet.
 */
class TileTestHarness(
    private val providerFactory: (perf: PerformanceManager) -> OptimizedTileProvider,
    private val pool: BitmapPool = BitmapPool(2)
) {
    private val perf = PerformanceManager()

    fun renderTile(x: Int, y: Int, zoom: Int, quality: Float = 1.0f): Result {
        val provider = providerFactory(perf)
        provider.setBitmapPool(pool)
        provider.updateQuality(quality)
        val tile: Tile = provider.getTile(x, y, zoom)
        return if (tile == com.google.android.gms.maps.model.TileProvider.NO_TILE || tile.data == null) {
            Result.Empty
        } else {
            val bytes = tile.data!!
            val sha1 = sha1Hex(bytes)
            Result.Image(bytes = bytes, sha1 = sha1)
        }
    }

    private fun sha1Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    sealed class Result {
        data object Empty : Result()
        data class Image(val bytes: ByteArray, val sha1: String) : Result()
    }
}
