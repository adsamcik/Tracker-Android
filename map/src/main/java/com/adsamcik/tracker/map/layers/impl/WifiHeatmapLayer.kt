package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.MapConstants
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.map.heatmap.HeatmapTile
import com.adsamcik.tracker.map.heatmap.creators.APPROXIMATE_DISTANCE_IN_METERS
import com.adsamcik.tracker.map.heatmap.creators.LOSS_EXPONENT
import com.adsamcik.tracker.map.heatmap.creators.MAX_WIFI_HEAT
import com.adsamcik.tracker.map.heatmap.creators.NORMALIZER
import com.adsamcik.tracker.map.heatmap.creators.VISUAL_SCALE
import com.adsamcik.tracker.map.data.Aggregation
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoSource
import com.adsamcik.tracker.map.graphics.BitmapPool
import com.adsamcik.tracker.map.layers.base.HeatmapLayer
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.tiles.HeatmapLayerSpec
import com.adsamcik.tracker.map.tiles.HeatmapTileProviderBase
import com.adsamcik.tracker.map.tiles.RadiusInfo
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.TileOverlayOptions
import com.google.android.gms.maps.model.TileProvider
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max

class WifiHeatmapLayer(
    private val repo: GeoRepository,
    private val pool: BitmapPool,
    private val perf: PerformanceManager = PerformanceManager()
) : HeatmapLayer<WifiHeatmapLayer.Input, WifiHeatmapLayer.Prepared>() {

    data class Input(val dummy: Unit = Unit)
    data class Prepared(val provider: TileProvider)

    private lateinit var provider: TileProviderV2

    override fun beforeEnable(context: Context, map: GoogleMap) {}

    override fun loadData(context: Context): Input = Input()

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
                source = GeoSource.WIFI
                weightColumn = "level"
                aggregation = Aggregation.Sum
                weightNormalizer = { v -> (v / MAX_WIFI_HEAT).toFloat().coerceIn(0f, 1f) }
                neighborClamp = 1f

                colorScheme = HeatmapColorScheme.viridis()
                maxHeat = MAX_WIFI_HEAT
                ageThresholdSec = DEFAULT_AGE_THRESHOLD_SECONDS
                weightMerge = { current: Float, _: Int, stampValue: Float, value: Float -> current + stampValue * value }
                alphaMerge = { current: Int, stampValue: Float, _: Float -> ((current.toFloat() + stampValue) / 2f).toInt() }
                valueCurve = { v ->
                    val t = 0.6f
                    val s = v * v * v * (v * (v * 6f - 15f) + 10f)
                    (1f - t) * v + t * s
                }

                heatmapBaseSize = HeatmapTile.BASE_HEATMAP_SIZE
                scaleWithQuality = true
                radiusComputer = { _, metersPerPixel, _ ->
                    val r = ceil(APPROXIMATE_DISTANCE_IN_METERS / (metersPerPixel / VISUAL_SCALE)).toInt().coerceAtLeast(1)
                    RadiusInfo(r, r)
                }
                buildStamp = { r -> HeatmapStamp.generateNonlinear(r) { (10 * LOSS_EXPONENT * log10(max(it * APPROXIMATE_DISTANCE_IN_METERS, 1f))) / NORMALIZER } }
                dynamicStampProvider = null
                ambientStampProvider = null
                ambientWeightScale = 0f
                neighborNormSize = 64
            }
        }

        companion object {
            private const val DEFAULT_AGE_THRESHOLD_SECONDS = 15 * 60
        }
    }
}
