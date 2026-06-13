package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.data.GeoQuery
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoSource
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.graphics.LegacyTileAggregator
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.layers.base.SupportsDateRange
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import kotlinx.coroutines.flow.first

/**
 * Legacy grid-tile heatmap (easter egg). Renders location density as discrete ~10 m square tiles —
 * the look the old Signals/Advention app had when heatmap tiles were rendered server-side — instead
 * of the smooth GPU heatmap. Produces [MapLibreLayerConfig.Fill] coloured by per-tile visit count.
 *
 * Fixed tile size means quality has no effect (capability advertised as supportsQuality = false).
 */
open class LegacyHeatmapLayer(
    private val repo: GeoRepository,
    private val perf: PerformanceManager = PerformanceManager(),
) : BaseMapLayer<List<WeightedGeoFeature>, String>(), SupportsDateRange {

    override var dateRange: LongRange = 0L..Long.MAX_VALUE

    override suspend fun loadData(context: Context, bounds: Bounds?): List<WeightedGeoFeature> {
        val query = GeoQuery(
            source = GeoSource.LOCATION,
            bounds = bounds,
            timeFrom = dateRange.first.takeIf { it > 0L },
            timeTo = dateRange.last.takeIf { it < Long.MAX_VALUE },
            weight = "hor_acc",
        )
        // Only positions matter here (tiles are coloured by visit count, not the weight column).
        return repo.queryWeighted(query, "hor_acc").first()
    }

    override fun processData(
        input: List<WeightedGeoFeature>,
        budgets: PerformanceManager.PerformanceBudgets,
    ): String {
        if (input.isEmpty()) return EMPTY_FEATURE_COLLECTION
        val centerLat = input.sumOf { it.lat } / input.size
        val tiles = LegacyTileAggregator.tile(points = input, centerLat = centerLat)
        return GeoJsonConverter.tilesToFeatureCollection(tiles)
    }

    override fun produceConfig(processed: String): MapLibreLayerConfig? {
        if (processed.isEmpty() || processed == EMPTY_FEATURE_COLLECTION) return null
        return MapLibreLayerConfig.Fill(
            geoJson = processed,
            colorStops = HeatmapColorRamps.LegacyTiles,
            opacity = TILE_OPACITY,
            outlineColorArgb = TILE_OUTLINE_ARGB,
            weightProperty = "weight",
        )
    }

    private companion object {
        const val EMPTY_FEATURE_COLLECTION = """{"type":"FeatureCollection","features":[]}"""
        const val TILE_OPACITY = 0.6f
        // Faint dark border so individual tiles read as discrete cells (the server-tile look).
        const val TILE_OUTLINE_ARGB = 0x33000000
    }
}
