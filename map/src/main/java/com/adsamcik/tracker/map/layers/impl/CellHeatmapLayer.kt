package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.map.heatmap.HeatmapTile
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
import kotlinx.coroutines.runBlocking
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow

class CellHeatmapLayer(
    private val repo: GeoRepository,
    private val pool: BitmapPool,
    private val perf: PerformanceManager = PerformanceManager()
) : HeatmapLayer<CellHeatmapLayer.Input, CellHeatmapLayer.Prepared>() {

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
                source = GeoSource.CELL
                weightColumn = "asu"
                aggregation = Aggregation.Max
                weightNormalizer = { v -> (v / DEFAULT_MAX_HEAT).toFloat().coerceIn(0f, 1f) }
                neighborClamp = 1f

                colorScheme = HeatmapColorScheme.viridis()
                maxHeat = DEFAULT_MAX_HEAT
                ageThresholdSec = DEFAULT_AGE_THRESHOLD_SECONDS
                weightMerge = { current: Float, _: Int, _: Float, value: Float -> max(value, current) }
                alphaMerge = { current: Int, stampValue: Float, _: Float ->
                    val newAlpha = (stampValue * 255f).toInt()
                    max(current, newAlpha)
                }
                valueCurve = { v ->
                    val t = 0.5f
                    val s = v * v * v * (v * (v * 6f - 15f) + 10f)
                    (1f - t) * v + t * s
                }

                heatmapBaseSize = HeatmapTile.BASE_HEATMAP_SIZE
                scaleWithQuality = true
                radiusComputer = { _, metersPerPixel, _ ->
                    val baseRadius = ceil(APPROXIMATE_SIZE_IN_METERS / metersPerPixel).toInt().coerceAtLeast(1)
                    RadiusInfo(baseRadius, baseRadius)
                }
                buildStamp = { r -> HeatmapStamp.generateNonlinear(r) { it.pow(FALLOFF_EXPONENT) } }
                dynamicStampProvider = null
                ambientStampProvider = null
                ambientWeightScale = 0f
                neighborNormSize = 64
            }
        }

        companion object {
            private const val APPROXIMATE_SIZE_IN_METERS = 50.0
            private const val FALLOFF_EXPONENT = 4.0f
            private const val DEFAULT_MAX_HEAT = 100f
            private const val DEFAULT_AGE_THRESHOLD_SECONDS = 15 * 60
        }
    }
}
