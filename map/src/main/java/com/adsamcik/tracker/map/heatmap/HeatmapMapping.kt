package com.adsamcik.tracker.map.heatmap

import com.adsamcik.tracker.map.MapFunctions
import kotlin.math.floor

/**
 * Shared helpers for seam-safe mapping from geographic coordinates to tile-local pixel coordinates.
 * Uses floor with a small epsilon to avoid double-hits on tile borders.
 */
internal object HeatmapMapping {
    private const val EPS: Double = 1e-6

    /**
     * Map longitude to x pixel in padded heatmap coordinates.
     * @param lon longitude in degrees
     * @param tileCount number of tiles at the current zoom
     * @param tileStartX current tile x index
     * @param heatmapSize inner heatmap (cropped) width in pixels
     * @param pad overscan padding in pixels on each side
     */
    fun lonToX(
        lon: Double,
        tileCount: Int,
        tileStartX: Int,
        heatmapSize: Int,
        pad: Int,
        eps: Double = EPS
    ): Int {
        val tx = MapFunctions.toTileX(lon, tileCount)
        return floor(((tx - tileStartX) * heatmapSize) - eps).toInt() + pad
    }

    /**
     * Map latitude to y pixel in padded heatmap coordinates.
     * @param lat latitude in degrees
     * @param tileCount number of tiles at the current zoom
     * @param tileStartY current tile y index
     * @param heatmapSize inner heatmap (cropped) height in pixels
     * @param pad overscan padding in pixels on each side
     */
    fun latToY(
        lat: Double,
        tileCount: Int,
        tileStartY: Int,
        heatmapSize: Int,
        pad: Int,
        eps: Double = EPS
    ): Int {
        val ty = MapFunctions.toTileY(lat, tileCount)
        return floor(((ty - tileStartY) * heatmapSize) - eps).toInt() + pad
    }
}
