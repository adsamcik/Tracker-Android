package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.data.GeoQuery
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoSource
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.layers.base.HeatmapLayer
import com.adsamcik.tracker.map.perf.PerformanceManager
import kotlinx.coroutines.flow.first
import kotlin.math.floor

/**
 * Heatmap layer showing Wi-Fi density weighted by signal quality.
 * Wi-Fi levels are stored in negative dBm values, so they must be normalized into 0..1
 * before handing them to MapLibre's heatmap weight expression.
 */
class WifiHeatmapLayer(
    private val repo: GeoRepository,
    private val perf: PerformanceManager = PerformanceManager()
) : HeatmapLayer<List<WeightedGeoFeature>, String>() {

    override fun colorStops(): List<Pair<Float, Int>> = listOf(
        0.0f to 0x00FF9800,
        0.18f to 0x66FFB74D,
        0.45f to 0xFFFF9800.toInt(),
        0.72f to 0xFFFF7043.toInt(),
        1.0f to 0xFFD50000.toInt()
    )

    override fun geoJsonFrom(processed: String): String = processed

    override fun radiusPx(): Float = 18f * quality

    override fun intensity(): Float = quality

    override suspend fun loadData(context: Context, bounds: Bounds?): List<WeightedGeoFeature> {
        val query = GeoQuery(
            source = GeoSource.WIFI,
            bounds = bounds,
            weight = "level"
        )
        return repo.queryWeighted(query, "level").first().map { feature ->
            feature.copy(weight = feature.weight.toWifiSignalWeight())
        }
    }

    override fun processData(
        input: List<WeightedGeoFeature>,
        budgets: PerformanceManager.PerformanceBudgets
    ): String {
        val aggregated = aggregateWifiCells(
            input = input,
            maxPoints = budgets.maxPoints,
            cellSizeDegrees = run {
                val zoomCellSize = com.adsamcik.tracker.map.graphics.GridAggregator.cellSizeForZoom(zoom)
                val qualityCellSize = (0.00045 / quality.coerceAtLeast(0.6f)).toDouble()
                maxOf(zoomCellSize, qualityCellSize).toFloat()
            }
        )
        return GeoJsonConverter.pointsToFeatureCollection(aggregated)
    }
}

private fun Double.toWifiSignalWeight(): Double {
    if (!isFinite()) return 0.0
    val clampedDbm = coerceIn(-100.0, -30.0)
    return ((clampedDbm + 100.0) / 70.0).coerceIn(0.0, 1.0)
}

internal fun aggregateWifiCells(
    input: List<WeightedGeoFeature>,
    maxPoints: Int,
    cellSizeDegrees: Float,
): List<WeightedGeoFeature> {
    if (input.isEmpty()) return emptyList()

    data class CellAccumulator(
        var latSum: Double = 0.0,
        var lonSum: Double = 0.0,
        var newestTime: Long = Long.MIN_VALUE,
        var weightSum: Double = 0.0,
        var count: Int = 0,
    )

    val cellSize = cellSizeDegrees.toDouble().coerceAtLeast(0.0001)
    val cells = LinkedHashMap<Pair<Long, Long>, CellAccumulator>(input.size.coerceAtMost(maxPoints))

    input.forEach { feature ->
        val latBucket = floor(feature.lat / cellSize).toLong()
        val lonBucket = floor(feature.lon / cellSize).toLong()
        val key = latBucket to lonBucket
        val cell = cells.getOrPut(key) { CellAccumulator() }
        cell.latSum += feature.lat
        cell.lonSum += feature.lon
        cell.newestTime = maxOf(cell.newestTime, feature.time)
        cell.weightSum += feature.weight
        cell.count += 1
    }

    val aggregated = cells.values
        .map { cell ->
            WeightedGeoFeature(
                lat = cell.latSum / cell.count,
                lon = cell.lonSum / cell.count,
                time = cell.newestTime,
                weight = (cell.weightSum / cell.count).coerceIn(0.0, 1.0)
            )
        }
        .sortedByDescending { it.weight }

    return if (aggregated.size > maxPoints) aggregated.take(maxPoints) else aggregated
}
