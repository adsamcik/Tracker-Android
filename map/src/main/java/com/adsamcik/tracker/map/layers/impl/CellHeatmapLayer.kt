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
import kotlinx.coroutines.flow.first

/**
 * Heatmap layer showing cell tower signal strength.
 * Queries ASU-weighted cell data and produces GeoJSON for MapLibre's native heatmap.
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
        return repo.queryWeighted(query, "asu").first().map { feature ->
            feature.copy(weight = (feature.weight / MAX_ASU).coerceIn(0.0, 1.0))
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

    private companion object {
        // Android ASU values vary by radio technology; 97 is the LTE/NR upper bound and keeps
        // stronger readings on the hot end without saturating ordinary mid-strength samples.
        const val MAX_ASU: Double = 97.0
    }
}
