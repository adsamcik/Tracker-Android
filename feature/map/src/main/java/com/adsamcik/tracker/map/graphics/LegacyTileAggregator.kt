package com.adsamcik.tracker.map.graphics

import com.adsamcik.tracker.map.data.GridTile
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import kotlin.math.cos
import kotlin.math.floor

/**
 * Buckets location fixes into a fixed ground-size grid for the legacy grid-tile heatmap — the look
 * the old Signals/Advention app had when heatmap tiles were rendered server-side as discrete
 * ~10 m × 10 m squares (rather than the smooth GPU heatmap used today).
 *
 * The latitude cell size is a constant degree span; the longitude cell size is widened by
 * `1 / cos(centerLat)` so tiles stay roughly square on the ground regardless of latitude. Tiles are
 * snapped to a global grid (floor bucketing) so neighbouring cells tile seamlessly with no gaps or
 * overlaps. Output is capped (densest tiles first) so a huge viewport can't flood the renderer.
 */
object LegacyTileAggregator {

    /** Tile edge length in metres. */
    const val TILE_METERS: Double = 10.0

    /** Hard cap on rendered tiles; protects the renderer at low zoom / large viewports. */
    const val MAX_TILES: Int = 25_000

    private const val METERS_PER_DEG_LAT = 111_320.0

    /**
     * Aggregate [points] into ~[tileMeters] square grid tiles. [centerLat] sets the longitude cell
     * size (use the viewport / data centre). Returns at most [maxTiles] tiles, densest first, each
     * with its grid-snapped bounds and a visit count normalized to [0, 1].
     */
    fun tile(
        points: List<WeightedGeoFeature>,
        centerLat: Double,
        tileMeters: Double = TILE_METERS,
        maxTiles: Int = MAX_TILES,
    ): List<GridTile> {
        if (points.isEmpty() || tileMeters <= 0.0) return emptyList()

        val cellLat = tileMeters / METERS_PER_DEG_LAT
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(1e-6)
        val cellLon = tileMeters / (METERS_PER_DEG_LAT * cosLat)

        val counts = HashMap<CellIndex, Int>(points.size / 2 + 16)
        for (p in points) {
            val latIdx = floor(p.lat / cellLat).toLong()
            val lonIdx = floor(normalizeLon(p.lon) / cellLon).toLong()
            val idx = CellIndex(latIdx, lonIdx)
            counts[idx] = (counts[idx] ?: 0) + 1
        }

        val maxCount = counts.values.maxOrNull() ?: return emptyList()
        if (maxCount <= 0) return emptyList()

        val selected = if (counts.size > maxTiles) {
            counts.entries.sortedByDescending { it.value }.take(maxTiles)
        } else {
            counts.entries
        }

        return selected.map { (idx, count) ->
            val south = idx.latIdx * cellLat
            val west = idx.lonIdx * cellLon
            GridTile(
                west = west,
                south = south,
                east = west + cellLon,
                north = south + cellLat,
                weight = (count.toDouble() / maxCount.toDouble()).coerceIn(0.0, 1.0),
                count = count,
            )
        }
    }

    /** Normalize longitude to [−180, 180). */
    private fun normalizeLon(lon: Double): Double {
        var n = lon % 360.0
        if (n < -180.0) n += 360.0
        if (n >= 180.0) n -= 360.0
        return n
    }

    private data class CellIndex(val latIdx: Long, val lonIdx: Long)
}
