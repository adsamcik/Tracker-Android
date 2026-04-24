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
 * Heatmap layer showing location density.
 * Queries weighted location data and produces GeoJSON for MapLibre's native heatmap.
 */
open class LocationHeatmapLayer(
    private val repo: GeoRepository,
    private val perf: PerformanceManager = PerformanceManager()
) : HeatmapLayer<List<WeightedGeoFeature>, String>() {

    // Five-stop ramp: transparent-blue → green → yellow → orange → red. Gives perceptual headroom so
    // that moderate-density areas stay cool rather than jumping straight to red.
    override fun colorStops(): List<Pair<Float, Int>> = listOf(
        0.0f to Color.argb(0, 0, 0, 255),
        0.2f to Color.rgb(0, 120, 255),
        0.45f to Color.rgb(0, 200, 120),
        0.7f to Color.YELLOW,
        0.9f to Color.rgb(255, 140, 0),
        1.0f to Color.RED
    )

    override fun geoJsonFrom(processed: String): String = processed

    override fun radiusPx(): Float = 20f * quality

    override fun intensity(): Float = quality

    override suspend fun loadData(context: Context, bounds: Bounds?): List<WeightedGeoFeature> {
        val query = GeoQuery(
            source = GeoSource.LOCATION,
            bounds = bounds,
            timeFrom = dateRange.first.takeIf { it > 0L },
            timeTo = dateRange.last.takeIf { it < Long.MAX_VALUE },
            weight = "hor_acc"
        )
        // `hor_acc` is GPS accuracy in meters (5–50+). Lower = more accurate. Invert and clamp
        // to [0, 1] so each point contributes a normalized weight to the heatmap; high-accuracy
        // fixes get weight ~0.9, poor fixes ~0.0. Without this, raw meter values (5–50) fed to
        // MapLibre's `heatmap-weight` saturate the color ramp on the very first sample.
        return repo.queryWeighted(query, "hor_acc").first().map { feature ->
            feature.copy(
                weight = (1.0 - feature.weight / MAX_ACCURACY_METERS).coerceIn(0.0, 1.0)
            )
        }
    }

    override fun processData(
        input: List<WeightedGeoFeature>,
        budgets: PerformanceManager.PerformanceBudgets
    ): String {
        val cellSize = GridAggregator.cellSizeForZoom(zoom)

        val processed = if (cellSize > 0.0) {
            // Aggregate, then re-weight each cell by visit count (normalized by max). This
            // surfaces true density hotspots instead of averaging per-point accuracy across
            // cells (which flattened the signal).
            val cells = GridAggregator.aggregate(input, cellSize)
            val maxCount = cells.maxOfOrNull { it.count } ?: 1
            if (maxCount <= 0) {
                emptyList()
            } else {
                cells.map { cell ->
                    WeightedGeoFeature(
                        lat = cell.lat,
                        lon = cell.lon,
                        time = cell.newestTime,
                        weight = (cell.count.toDouble() / maxCount.toDouble()).coerceIn(0.0, 1.0),
                    )
                }
            }
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
        // Accuracy above this (meters) yields a weight of 0. Calibrated against typical mobile
        // GPS: 5 m (excellent) → 0.9, 25 m (mediocre) → 0.5, 50 m (poor) → 0.0.
        const val MAX_ACCURACY_METERS: Double = 50.0
    }
}
