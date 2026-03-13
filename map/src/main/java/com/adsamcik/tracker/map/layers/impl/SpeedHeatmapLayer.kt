package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import android.graphics.Color
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
 * Heatmap layer showing speed distribution.
 * Queries speed-weighted location data and produces GeoJSON for MapLibre's native heatmap.
 */
open class SpeedHeatmapLayer(
    private val repo: GeoRepository,
    private val perf: PerformanceManager = PerformanceManager()
) : HeatmapLayer<List<WeightedGeoFeature>, String>() {

    override fun colorStops(): List<Pair<Float, Int>> = listOf(
        0.0f to Color.rgb(153, 102, 255),  // Very Slow: Purple
        0.2f to Color.rgb(102, 204, 255),  // Walking: Light Blue
        0.4f to Color.rgb(102, 255, 102),  // Running: Light Green
        0.6f to Color.rgb(255, 255, 102),  // Bike: Yellow
        0.8f to Color.rgb(255, 128, 0),    // Transport: Orange
        1.0f to Color.rgb(255, 51, 51)     // Car: Red
    )

    override fun geoJsonFrom(processed: String): String = processed

    override fun radiusPx(): Float = 20f * quality

    override fun intensity(): Float = quality

    override suspend fun loadData(context: Context, bounds: Bounds?): List<WeightedGeoFeature> {
        val query = GeoQuery(
            source = GeoSource.LOCATION,
            bounds = bounds,
            weight = "speed"
        )
        return repo.queryWeighted(query, "speed").first()
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
