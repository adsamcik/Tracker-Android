package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.data.GeoQuery
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoSource
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.graphics.GridAggregator
import com.adsamcik.tracker.map.layers.base.HeatmapLayer
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.shared.base.data.CellType
import kotlinx.coroutines.flow.first

/**
 * Heatmap layer showing cell tower signal strength.
 * Queries ASU cell data and normalizes it by radio technology for MapLibre's native heatmap.
 */
open class CellHeatmapLayer(
    private val repo: GeoRepository,
    private val perf: PerformanceManager = PerformanceManager()
) : HeatmapLayer<List<WeightedGeoFeature>, String>() {

    override fun colorStops(): List<Pair<Float, Int>> = HeatmapColorRamps.CellSignal

    override fun geoJsonFrom(processed: String): String = processed

    override fun radiusPx(): Float = 25f * quality

    override fun intensity(): Float = quality

    override suspend fun loadData(context: Context, bounds: Bounds?): List<WeightedGeoFeature> {
        val query = GeoQuery(
            source = GeoSource.CELL,
            bounds = bounds,
            timeFrom = dateRange.first.takeIf { it > 0L },
            timeTo = dateRange.last.takeIf { it < Long.MAX_VALUE },
            weight = "asu"
        )
        return repo.queryCellSignals(query).first().map { feature ->
            WeightedGeoFeature(
                lat = feature.lat,
                lon = feature.lon,
                time = feature.time,
                weight = feature.asu.toCellSignalWeight(feature.networkType),
            )
        }
    }

    override fun processData(
        input: List<WeightedGeoFeature>,
        budgets: PerformanceManager.PerformanceBudgets
    ): String {
        val cellSize = GridAggregator.cellSizeForZoom(zoom)

        val processed = if (cellSize > 0.0) {
            val cells = GridAggregator.aggregate(input, cellSize)
            GridAggregator.toWeightedFeatures(cells)
        } else {
            if (input.size > budgets.maxPoints) {
                val step = (input.size / budgets.maxPoints).coerceAtLeast(1)
                input.filterIndexed { index, _ -> index % step == 0 }
            } else {
                input
            }
        }
        return GeoJsonConverter.pointsToFeatureCollection(processed)
    }

}

private const val GSM_MAX_ASU: Double = 31.0
private const val CDMA_MAX_ASU: Double = 16.0
private const val WCDMA_MAX_ASU: Double = 31.0
private const val LTE_NR_MAX_ASU: Double = 97.0

private fun Double.toCellSignalWeight(networkType: Int): Double {
    if (this <= 0.0) return 0.0
    val maxAsu = when (CellType.values().getOrNull(networkType)) {
        CellType.GSM -> GSM_MAX_ASU
        CellType.CDMA -> CDMA_MAX_ASU
        CellType.WCDMA -> WCDMA_MAX_ASU
        CellType.LTE,
        CellType.NR -> LTE_NR_MAX_ASU
        CellType.Unknown,
        CellType.None,
        null -> LTE_NR_MAX_ASU
    }
    return (this / maxAsu).coerceIn(0.0, 1.0)
}
