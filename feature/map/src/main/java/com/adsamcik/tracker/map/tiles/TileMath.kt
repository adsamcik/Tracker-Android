package com.adsamcik.tracker.map.tiles

import com.adsamcik.tracker.map.data.Bounds

/**
 * Tile coordinate math for the Z/X/Y (slippy map) tile scheme.
 * Converts between tile coordinates, lat/lon bounds, and pixel positions.
 *
 * All functions operate in the Web Mercator (EPSG:3857) tile grid,
 * which is valid for latitudes in [−85.05, 85.05]. Inputs outside
 * this range are clamped to avoid Mercator singularities.
 *
 * **Antimeridian note:** [tilesForBounds] does not wrap across the
 * ±180° longitude boundary. Bounds that cross the antimeridian must
 * be split into two before calling.
 */
object TileMath {

    /** Maximum latitude supported by Web Mercator before singularity. */
    internal const val MAX_MERCATOR_LAT = 85.05112878

    /** Maximum zoom level supported (prevents shift overflow). */
    internal const val MAX_ZOOM = 25

    /**
     * Convert tile coordinates to lat/lon [Bounds].
     * Uses the Web Mercator (EPSG:3857) tile grid.
     *
     * @throws IllegalArgumentException if [z] is outside 0..[MAX_ZOOM] or tile out of range.
     */
    fun tileToBounds(z: Int, x: Int, y: Int): Bounds {
        require(z in 0..MAX_ZOOM) { "Zoom $z out of valid range 0..$MAX_ZOOM" }
        val n = 1 shl z // 2^z
        require(x in 0 until n && y in 0 until n) {
            "Tile ($x,$y) out of range for zoom $z (max ${n - 1})"
        }
        val lonMin = x.toDouble() / n * 360.0 - 180.0
        val lonMax = (x + 1.0) / n * 360.0 - 180.0
        val latMax = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2.0 * y / n))))
        val latMin = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2.0 * (y + 1) / n))))
        return Bounds(north = latMax, south = latMin, east = lonMax, west = lonMin)
    }

    /**
     * Convert lat/lon to tile coordinates at [zoom] level.
     * Returns a Pair(tileX, tileY).
     * Latitude is clamped to [MAX_MERCATOR_LAT] to avoid singularity.
     *
     * @throws IllegalArgumentException if [zoom] is outside 0..[MAX_ZOOM].
     */
    fun latLonToTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        require(zoom in 0..MAX_ZOOM) { "Zoom $zoom out of valid range 0..$MAX_ZOOM" }
        val n = 1 shl zoom
        val x = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val clampedLat = lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)
        val latRad = Math.toRadians(clampedLat)
        val y = ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n)
            .toInt()
            .coerceIn(0, n - 1)
        return x to y
    }

    /**
     * Convert lat/lon to pixel coordinates within a tile of given [extent] (default 4096).
     * Returns Pair(pixelX, pixelY) relative to the tile origin.
     * Latitude is clamped to [MAX_MERCATOR_LAT] to avoid singularity.
     *
     * @throws IllegalArgumentException if [z] is outside 0..[MAX_ZOOM].
     */
    fun coordinateToTilePixel(
        lat: Double,
        lon: Double,
        z: Int,
        tileX: Int,
        tileY: Int,
        extent: Int = 4096,
    ): Pair<Int, Int> {
        require(z in 0..MAX_ZOOM) { "Zoom $z out of valid range 0..$MAX_ZOOM" }
        val n = 1 shl z
        val px = ((lon + 180.0) / 360.0 * n - tileX) * extent
        val clampedLat = lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)
        val latRad = Math.toRadians(clampedLat)
        val py = ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n - tileY) * extent
        return px.toInt() to py.toInt()
    }

    /**
     * Return the set of tile coordinates that cover the given [bounds] at [zoom].
     * Useful for determining which tiles need to be loaded for a viewport.
     *
     * @throws IllegalArgumentException if [zoom] is outside 0..[MAX_ZOOM].
     */
    fun tilesForBounds(bounds: Bounds, zoom: Int): List<Triple<Int, Int, Int>> {
        require(zoom in 0..MAX_ZOOM) { "Zoom $zoom out of valid range 0..$MAX_ZOOM" }
        val (minX, minY) = latLonToTile(bounds.north, bounds.west, zoom)
        val (maxX, maxY) = latLonToTile(bounds.south, bounds.east, zoom)
        val tiles = mutableListOf<Triple<Int, Int, Int>>()
        val xCoordinates = linkedSetOf<Int>()
        if (bounds.crossesAntimeridian) {
            xCoordinates.addAll(minX until (1 shl zoom))
            xCoordinates.addAll(0..maxX)
        } else {
            xCoordinates.addAll(minX..maxX)
        }
        for (x in xCoordinates) {
            for (y in minY..maxY) {
                tiles.add(Triple(zoom, x, y))
            }
        }
        return tiles
    }
}
