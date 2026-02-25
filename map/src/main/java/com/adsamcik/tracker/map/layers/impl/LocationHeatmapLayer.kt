package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import android.graphics.Color
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.data.GeoQuery
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoSource
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.layers.base.HeatmapLayer
import com.adsamcik.tracker.map.perf.PerformanceManager
import kotlinx.coroutines.flow.first

/**
 * Heatmap layer showing location density.
 * Queries weighted location data and produces GeoJSON for MapLibre's native heatmap.
 */
open class LocationHeatmapLayer(
    private val repo: GeoRepository,
    private val perf: PerformanceManager = PerformanceManager()
) : HeatmapLayer<List<WeightedGeoFeature>, String>() {

    override fun colorStops(): List<Pair<Float, Int>> = listOf(
        0.0f to Color.BLUE,
        0.5f to Color.YELLOW,
        1.0f to Color.RED
    )

    override fun geoJsonFrom(processed: String): String = processed

    override fun radiusPx(): Float = 20f * quality

    override fun intensity(): Float = quality

    override suspend fun loadData(context: Context): List<WeightedGeoFeature> {
        val query = GeoQuery(
            source = GeoSource.LOCATION,
            weight = "hor_acc"
        )
        return repo.queryWeighted(query, "hor_acc").first()
    }

    override fun processData(
        input: List<WeightedGeoFeature>,
        budgets: PerformanceManager.PerformanceBudgets
    ): String {
        val capped = if (input.size > budgets.maxPoints) {
            val step = (input.size / budgets.maxPoints).coerceAtLeast(1)
            input.filterIndexed { index, _ -> index % step == 0 }
        } else {
            input
        }
        return GeoJsonConverter.pointsToFeatureCollection(capped)
    }
}
