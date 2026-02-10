package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.data.GeoQuery
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoSource
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.layers.base.HeatmapLayer
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.shared.utils.style.color.ColorConstants
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
        0.0f to ColorConstants.GREEN,
        0.5f to ColorConstants.ORANGE,
        1.0f to ColorConstants.RED
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
        val capped = if (input.size > budgets.maxPoints) {
            val step = (input.size / budgets.maxPoints).coerceAtLeast(1)
            input.filterIndexed { index, _ -> index % step == 0 }
        } else {
            input
        }
        return GeoJsonConverter.pointsToFeatureCollection(capped)
    }
}
