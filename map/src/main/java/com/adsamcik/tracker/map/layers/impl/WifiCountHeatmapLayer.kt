package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.data.GeoQuery
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoSource
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.layers.base.HeatmapLayer
import com.adsamcik.tracker.map.perf.PerformanceManager
import kotlinx.coroutines.flow.first

/**
 * Heatmap layer showing Wi-Fi access point count density.
 * Queries count-weighted Wi-Fi data and produces GeoJSON for MapLibre's native heatmap.
 */
class WifiCountHeatmapLayer(
    private val repo: GeoRepository,
    private val perf: PerformanceManager = PerformanceManager()
) : HeatmapLayer<List<WeightedGeoFeature>, String>() {

    override fun colorStops(): List<Pair<Float, Int>> = listOf(
        0.0f to 0x001FC8FF,
        0.18f to 0x661FC8FF,
        0.42f to 0xFF1FC8FF.toInt(),
        0.7f to 0xFF3F51B5.toInt(),
        1.0f to 0xFF6A1B9A.toInt()
    )

    override fun geoJsonFrom(processed: String): String = processed

    override fun radiusPx(): Float = 18f * quality

    override fun intensity(): Float = quality

    override suspend fun loadData(context: Context): List<WeightedGeoFeature> {
        // Use unweighted query; each observation contributes weight 1.0
        // so the heatmap shows AP count density, not signal strength.
        val query = GeoQuery(source = GeoSource.WIFI)
        return repo.query(query).first().map { feature ->
            WeightedGeoFeature(feature.lat, feature.lon, feature.time, weight = 1.0)
        }
    }

    override fun processData(
        input: List<WeightedGeoFeature>,
        budgets: PerformanceManager.PerformanceBudgets
    ): String {
        val aggregated = aggregateWifiCells(
            input = input,
            maxPoints = budgets.maxPoints,
            cellSizeDegrees = (0.0006 / quality.coerceAtLeast(0.6f)).toFloat()
        )
        if (aggregated.isEmpty()) return GeoJsonConverter.pointsToFeatureCollection(aggregated)

        val maxWeight = aggregated.maxOf { it.weight }.coerceAtLeast(1.0)
        val normalized = aggregated.map { feature ->
            feature.copy(weight = (feature.weight / maxWeight).coerceIn(0.0, 1.0))
        }
        return GeoJsonConverter.pointsToFeatureCollection(normalized)
    }
}
