package com.adsamcik.tracker.map.graphics

import com.adsamcik.tracker.map.data.WeightedGeoFeature
import kotlin.math.floor

/**
 * Spatial grid aggregation for heatmap layers at low zoom levels.
 *
 * At world/continent zoom (< 13), individual points are aggregated into grid
 * cells so the renderer handles ~2K–20K cells instead of potentially 80K+ raw
 * points. At street zoom (>= 13) no aggregation is applied.
 *
 * Longitude wrapping around ±180° is handled by normalizing to [−180, 180)
 * before bucketing.
 */
object GridAggregator {

    data class AggregatedCell(
        val lat: Double,
        val lon: Double,
        val weight: Double,
        val count: Int,
        val newestTime: Long,
    )

    /**
     * Aggregate [points] into grid cells of [cellSizeDegrees].
     * Returns cell-center coordinates with averaged weight and total count.
     *
     * @param cellSizeDegrees grid cell width/height in degrees; must be > 0.
     */
    fun aggregate(
        points: List<WeightedGeoFeature>,
        cellSizeDegrees: Double,
    ): List<AggregatedCell> {
        if (points.isEmpty() || cellSizeDegrees <= 0.0) return emptyList()

        val cells = LinkedHashMap<Long, CellAccumulator>(
            (points.size / 4).coerceAtLeast(16)
        )

        for (feature in points) {
            val latBucket = floor(feature.lat / cellSizeDegrees).toLong()
            val normLon = normalizeLon(feature.lon)
            val lonBucket = floor(normLon / cellSizeDegrees).toLong()
            val key = packKey(latBucket, lonBucket)
            val cell = cells.getOrPut(key) { CellAccumulator() }
            cell.latSum += feature.lat
            cell.lonSum += normLon
            cell.weightSum += feature.weight
            cell.newestTime = maxOf(cell.newestTime, feature.time)
            cell.count += 1
        }

        val result = ArrayList<AggregatedCell>(cells.size)
        for (cell in cells.values) {
            result.add(AggregatedCell(
                lat = cell.latSum / cell.count,
                lon = cell.lonSum / cell.count,
                weight = cell.weightSum / cell.count,
                count = cell.count,
                newestTime = cell.newestTime,
            ))
        }
        return result
    }

    /**
     * Convert aggregated cells back to [WeightedGeoFeature] for the existing
     * GeoJSON pipeline.
     */
    fun toWeightedFeatures(cells: List<AggregatedCell>): List<WeightedGeoFeature> {
        val features = ArrayList<WeightedGeoFeature>(cells.size)
        for (cell in cells) {
            features.add(WeightedGeoFeature(
                lat = cell.lat,
                lon = cell.lon,
                time = cell.newestTime,
                weight = cell.weight,
            ))
        }
        return features
    }

    /**
     * Cell size in degrees for a given zoom level.
     * Returns 0.0 when no aggregation should be applied (zoom >= 13).
     */
    fun cellSizeForZoom(zoom: Float): Double = when {
        zoom < 5f -> 0.5      // ~50 km cells
        zoom < 9f -> 0.1      // ~10 km cells
        zoom < 13f -> 0.01    // ~1 km cells
        else -> 0.0            // No aggregation; use raw points
    }

    /** Normalize longitude to [−180, 180). */
    private fun normalizeLon(lon: Double): Double {
        var n = lon % 360.0
        if (n < -180.0) n += 360.0
        if (n >= 180.0) n -= 360.0
        return n
    }

    /** Pack two Int-range bucket indices into a single Long key. */
    private fun packKey(latBucket: Long, lonBucket: Long): Long =
        (latBucket shl 32) or (lonBucket and 0xFFFFFFFFL)

    private class CellAccumulator {
        var latSum: Double = 0.0
        var lonSum: Double = 0.0
        var weightSum: Double = 0.0
        var newestTime: Long = Long.MIN_VALUE
        var count: Int = 0
    }
}
