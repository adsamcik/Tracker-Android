package com.adsamcik.tracker.map.graphics

import com.adsamcik.tracker.map.data.WeightedGeoFeature
import kotlin.math.floor

/**
 * Spatial grid aggregation for heatmap layers.
 *
 * Individual points are aggregated into grid cells at every zoom so the renderer handles a bounded
 * number of weighted cells instead of tens of thousands of raw points. Crucially, aggregation also
 * keeps the heatmap readable: raw GPS points along a travelled path sit only metres apart, so within
 * one heatmap radius dozens overlap and MapLibre's `heatmap-density` sums far past 1.0, clamping the
 * whole path to the top (red) colour stop. One weighted point per cell keeps density in range so the
 * colour gradient is actually visible.
 *
 * Longitude wrapping around ±180° is handled by normalizing to [−180, 180)
 * before bucketing.
 */
object GridAggregator {

    /** Lower bound for the render quality multiplier (matches the slowest Map-settings quality stop). */
    const val MIN_QUALITY: Float = 0.25f

    /** Upper bound for the render quality multiplier (matches the most detailed Map-settings stop). */
    const val MAX_QUALITY: Float = 8.0f

    /**
     * Heatmap blob radius (px) for a [basePx] radius at quality 1, scaled to stay coherent with the
     * quality-scaled [cellSizeForZoom]. Higher quality → smaller, sharper blobs over the finer grid;
     * lower quality → larger blobs over the coarser grid. Keeping radius ≈ cell size avoids both
     * dotty gaps (radius too small for the cells) and density-saturated red blobs (radius too large).
     */
    fun radiusForQuality(basePx: Float, quality: Float): Float =
        basePx / quality.coerceIn(MIN_QUALITY, MAX_QUALITY)

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
     * Grid cell size in degrees for a given zoom level and render [quality]. Always returns a
     * positive value so heatmap layers aggregate at every zoom level.
     *
     * Base cell sizes are chosen to be roughly the heatmap blob radius (~18–25 px) wide on screen at
     * each zoom: large enough that points within a path no longer pile up into a saturated red blob,
     * small enough that neighbouring cells still blend into a smooth gradient rather than discrete
     * dots.
     *
     * [quality] then scales the resolution: higher quality shrinks the cells (finer grid = more
     * detail, more cells to render), lower quality enlarges them (coarser = faster). This is what
     * makes the quality setting change the heatmap's *detail*, not just its blob size. Layers that
     * pair this with a quality-scaled [radius][HeatmapLayer.radiusPx] keep cells ≈ radius so the
     * gradient stays smooth and unsaturated at every quality level.
     */
    fun cellSizeForZoom(zoom: Float, quality: Float = 1f): Double {
        val base = when {
            zoom < 5f -> 0.5       // ~50 km cells
            zoom < 9f -> 0.1       // ~10 km cells
            zoom < 13f -> 0.01     // ~1 km cells
            zoom < 15f -> 0.002    // ~220 m cells — street level
            zoom < 17f -> 0.0005   // ~55 m cells — block level
            else -> 0.00012        // ~13 m cells — building level
        }
        return base / quality.coerceIn(MIN_QUALITY, MAX_QUALITY)
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
