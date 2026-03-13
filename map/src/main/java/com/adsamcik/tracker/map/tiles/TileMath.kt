package com.adsamcik.tracker.map.tiles

import com.adsamcik.tracker.map.data.Bounds

/**
 * Tile coordinate math for the Z/X/Y (slippy map) tile scheme.
 * Converts between tile coordinates, lat/lon bounds, and pixel positions.
 */
object TileMath {

    /**
     * Convert tile coordinates to lat/lon [Bounds].
     * Uses the Web Mercator (EPSG:3857) tile grid.
     */
    fun tileToBounds(z: Int, x: Int, y: Int): Bounds {
        val n = 1 shl z // 2^z
        val lonMin = x.toDouble() / n * 360.0 - 180.0
        val lonMax = (x + 1.0) / n * 360.0 - 180.0
        val latMax = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2.0 * y / n))))
        val latMin = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2.0 * (y + 1) / n))))
        return Bounds(north = latMax, south = latMin, east = lonMax, west = lonMin)
    }

    /**
     * Convert lat/lon to tile coordinates at [zoom] level.
     * Returns a Pair(tileX, tileY).
     */
    fun latLonToTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val n = 1 shl zoom
        val x = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val latRad = Math.toRadians(lat)
        val y = ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n)
            .toInt()
            .coerceIn(0, n - 1)
        return x to y
    }

    /**
     * Convert lat/lon to pixel coordinates within a tile of given [extent] (default 4096).
     * Returns Pair(pixelX, pixelY) relative to the tile origin.
     */
    fun coordinateToTilePixel(
        lat: Double,
        lon: Double,
        z: Int,
        tileX: Int,
        tileY: Int,
        extent: Int = 4096,
    ): Pair<Int, Int> {
        val n = 1 shl z
        val px = ((lon + 180.0) / 360.0 * n - tileX) * extent
        val latRad = Math.toRadians(lat)
        val py = ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n - tileY) * extent
        return px.toInt() to py.toInt()
    }

    /**
     * Return the set of tile coordinates that cover the given [bounds] at [zoom].
     * Useful for determining which tiles need to be loaded for a viewport.
     */
    fun tilesForBounds(bounds: Bounds, zoom: Int): List<Triple<Int, Int, Int>> {
        val (minX, minY) = latLonToTile(bounds.north, bounds.west, zoom)
        val (maxX, maxY) = latLonToTile(bounds.south, bounds.east, zoom)
        val tiles = mutableListOf<Triple<Int, Int, Int>>()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                tiles.add(Triple(zoom, x, y))
            }
        }
        return tiles
    }
}
