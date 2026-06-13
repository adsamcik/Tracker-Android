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
 * Heatmap layer showing location density.
 * Queries weighted location data and produces GeoJSON for MapLibre's native heatmap.
 */
open class LocationHeatmapLayer(
    private val repo: GeoRepository,
    private val perf: PerformanceManager = PerformanceManager()
) : HeatmapLayer<List<WeightedGeoFeature>, String>() {

    // Five-stop ramp: transparent-blue → green → yellow → orange → red. Gives perceptual headroom so
    // that moderate-density areas stay cool rather than jumping straight to red.
    override fun colorStops(): List<Pair<Float, Int>> = HeatmapColorRamps.LocationDensity

    override fun geoJsonFrom(processed: String): String = processed

    override fun radiusPx(): Float = GridAggregator.radiusForQuality(20f, quality)

    override suspend fun loadData(context: Context, bounds: Bounds?): List<WeightedGeoFeature> {
        val query = GeoQuery(
            source = GeoSource.LOCATION,
            bounds = bounds,
            timeFrom = dateRange.first.takeIf { it > 0L },
            timeTo = dateRange.last.takeIf { it < Long.MAX_VALUE },
            weight = "hor_acc"
        )
        // `hor_acc` is GPS accuracy in meters (5–50+). Lower = more accurate. Invert and clamp to
        // [0, 1] so each fix carries a confidence weight (high-accuracy ~0.9, poor ~0.0). The density
        // heatmap colours cells by visit COUNT (see processData), so this per-fix weight is currently
        // retained only as confidence metadata; it intentionally does not drive the colour directly,
        // which is what previously let raw meter values saturate the ramp.
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
        // cellSizeForZoom is always > 0, so every zoom aggregates raw fixes into a grid. This both
        // bounds the rendered point count and — crucially — stops a travelled path's tightly-spaced
        // raw points from piling up inside one heatmap radius and saturating to solid red.
        val cellSize = GridAggregator.cellSizeForZoom(zoom, quality)
        val cells = GridAggregator.aggregate(input, cellSize)

        // Weight each cell by an ABSOLUTE log-density of its visit count (not count / viewportMax).
        // Absolute → a cell's colour depends only on its own data, so colours stay put as the user
        // pans (no "breathing") and the log curve produces real cool→hot shades across the huge
        // dynamic range of visit counts.
        val weighted = cells.map { cell ->
            WeightedGeoFeature(
                lat = cell.lat,
                lon = cell.lon,
                time = cell.newestTime,
                weight = GridAggregator.densityWeight(cell.count),
            )
        }

        // Safety cap: in the rare case a single viewport still resolves into more cells than the
        // render budget allows, thin them uniformly so spatial coverage (and the gradient) is kept.
        val capped = if (weighted.size > budgets.maxPoints) {
            val step = (weighted.size / budgets.maxPoints).coerceAtLeast(1)
            weighted.filterIndexed { index, _ -> index % step == 0 }
        } else {
            weighted
        }
        return GeoJsonConverter.pointsToFeatureCollection(capped)
    }

    private companion object {
        // Accuracy above this (meters) yields a weight of 0. Calibrated against typical mobile
        // GPS: 5 m (excellent) → 0.9, 25 m (mediocre) → 0.5, 50 m (poor) → 0.0.
        const val MAX_ACCURACY_METERS: Double = 50.0
    }
}
