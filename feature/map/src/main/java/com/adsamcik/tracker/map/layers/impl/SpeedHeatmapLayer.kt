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
 * Heatmap layer showing speed distribution.
 * Queries speed-weighted location data and produces GeoJSON for MapLibre's native heatmap.
 */
open class SpeedHeatmapLayer(
    private val repo: GeoRepository,
    private val perf: PerformanceManager = PerformanceManager()
) : HeatmapLayer<List<WeightedGeoFeature>, String>() {

    override fun colorStops(): List<Pair<Float, Int>> = HeatmapColorRamps.Speed

    override fun geoJsonFrom(processed: String): String = processed

    override fun radiusPx(): Float = GridAggregator.radiusForQuality(20f, quality)

    override suspend fun loadData(context: Context, bounds: Bounds?): List<WeightedGeoFeature> {
        val query = GeoQuery(
            source = GeoSource.LOCATION,
            bounds = bounds,
            timeFrom = dateRange.first.takeIf { it > 0L },
            timeTo = dateRange.last.takeIf { it < Long.MAX_VALUE },
            weight = "speed"
        )
        // `speed` is m/s. 30 m/s ≈ 108 km/h which is a reasonable fast-car cap; above that we
        // clamp to full-red. Normalize raw m/s to [0, 1] so the color ramp maps meaningfully and
        // MapLibre's `heatmap-weight` doesn't saturate on the first sample.
        return repo.queryWeighted(query, "speed").first().map { feature ->
            feature.copy(weight = (feature.weight / MAX_SPEED_MPS).coerceIn(0.0, 1.0))
        }
    }

    override fun processData(
        input: List<WeightedGeoFeature>,
        budgets: PerformanceManager.PerformanceBudgets
    ): String {
        // cellSizeForZoom is always > 0, so every zoom aggregates into a grid. For speed we keep the
        // per-cell AVERAGE (weights are already normalized to [0, 1] in loadData), not a count.
        val cellSize = GridAggregator.cellSizeForZoom(zoom, quality)
        val cells = GridAggregator.aggregate(input, cellSize)
        val weighted = GridAggregator.toWeightedFeatures(cells)

        // Safety cap: thin uniformly if a viewport still resolves into more cells than the budget.
        val capped = if (weighted.size > budgets.maxPoints) {
            val step = (weighted.size / budgets.maxPoints).coerceAtLeast(1)
            weighted.filterIndexed { index, _ -> index % step == 0 }
        } else {
            weighted
        }
        return GeoJsonConverter.pointsToFeatureCollection(capped)
    }

    private companion object {
        // 30 m/s ≈ 108 km/h — above this we saturate to the hottest color bucket (car/transport).
        const val MAX_SPEED_MPS: Double = 30.0
    }
}
