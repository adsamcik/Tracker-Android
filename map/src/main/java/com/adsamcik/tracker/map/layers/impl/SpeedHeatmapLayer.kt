package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.MapConstants
import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.map.heatmap.HeatmapEngine
import com.adsamcik.tracker.map.heatmap.ValueCurves
import com.adsamcik.tracker.map.heatmap.implementation.MergePolicies
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileData
import com.adsamcik.tracker.map.heatmap.creators.HeatmapConfig
import com.adsamcik.tracker.map.heatmap.implementation.AgeWeightedHeatmap
import com.adsamcik.tracker.map.data.Aggregation
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoQuery
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoSource
import com.adsamcik.tracker.map.graphics.BitmapPool
import com.adsamcik.tracker.map.layers.base.HeatmapLayer
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.tiles.HeatmapLayerSpec
import com.adsamcik.tracker.map.tiles.HeatmapTileProviderBase
import com.adsamcik.tracker.map.tiles.RadiusInfo
import com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted
import com.adsamcik.tracker.shared.map.CoordinateBounds
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileOverlayOptions
import com.google.android.gms.maps.model.TileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlin.math.ceil
import kotlin.math.pow

class SpeedHeatmapLayer(
    private val repo: GeoRepository,
    private val pool: BitmapPool,
    private val perf: PerformanceManager = PerformanceManager()
) : HeatmapLayer<SpeedHeatmapLayer.Input, SpeedHeatmapLayer.Prepared>() {

    data class Input(val dummy: Unit = Unit)
    data class Prepared(val provider: TileProvider)

    private lateinit var provider: TileProviderV2

    override fun beforeEnable(context: Context, map: GoogleMap) {}

    override suspend fun loadData(context: Context): Input = Input()

    override fun processData(input: Input, budgets: PerformanceManager.PerformanceBudgets): Prepared {
        provider = TileProviderV2(repo, pool, perf)
        provider.updateQuality(quality)
        return Prepared(provider)
    }

    override fun buildTileOverlay(processed: Prepared): TileOverlayOptions =
        TileOverlayOptions().tileProvider(processed.provider)

    private class TileProviderV2(
        repo: GeoRepository,
        private val pool: BitmapPool,
        perf: PerformanceManager,
    ) : HeatmapTileProviderBase(repo, pool, perf) {

        override fun specFor(x: Int, y: Int, zoom: Int): HeatmapLayerSpec {
            return com.adsamcik.tracker.map.tiles.heatmapSpec {
                source = GeoSource.LOCATION
                weightColumn = "speed"
                aggregation = Aggregation.Avg
                weightNormalizer = { v -> (v / DEFAULT_MAX_HEAT).toFloat().coerceIn(0f, 1f) }
                neighborClamp = 1f

                colorScheme = HeatmapColorScheme.viridis()
                maxHeat = DEFAULT_MAX_HEAT
                ageThresholdSec = DEFAULT_AGE_THRESHOLD_SECONDS

                weightMerge = MergePolicies.alphaLerp
                alphaMerge = MergePolicies.alphaScaledByWeightDiv(DEFAULT_MAX_HEAT)
                valueCurve = ValueCurves.smoothstep(0.65f)

                heatmapBaseSize = HeatmapEngine.BASE_HEATMAP_SIZE
                scaleWithQuality = true
                radiusComputer = { z, metersPerPixel, _ ->
                    val baseMeterSize = BASE_HEAT_SIZE_IN_METERS * HEATMAP_ZOOM_SCALE.pow((MapConstants.MAX_ZOOM - z).toDouble())
                    val baseRadius = ceil(baseMeterSize / metersPerPixel).toInt().coerceAtLeast(1)
                    RadiusInfo(baseRadius, baseRadius)
                }
                buildStamp = { r -> HeatmapStamp.generateGaussian(r) }
                dynamicStampProvider = null
                ambientStampProvider = null
                ambientWeightScale = 0f
                neighborNormSize = 64
            }
        }

        companion object {
            private const val BASE_HEAT_SIZE_IN_METERS = 40.0
            private const val HEATMAP_ZOOM_SCALE = 1.4
            private const val DEFAULT_MAX_HEAT = 100f
            private const val DEFAULT_AGE_THRESHOLD_SECONDS = 15 * 60
        }
    }
}
