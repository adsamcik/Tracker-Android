package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.MapConstants
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.map.heatmap.HeatmapEngine
import com.adsamcik.tracker.map.heatmap.ValueCurves
import com.adsamcik.tracker.map.heatmap.implementation.MergePolicies
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
import kotlin.math.pow

class LocationHeatmapLayer(
    private val repo: GeoRepository,
    private val pool: BitmapPool,
    private val perf: PerformanceManager = PerformanceManager()
) : HeatmapLayer<LocationHeatmapLayer.Input, LocationHeatmapLayer.Prepared>() {

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
        private val perf: PerformanceManager,
    ) : HeatmapTileProviderBase(repo, pool, perf) {

        private var lastZoom: Double? = null
        override fun hysteresisZoom(currentZoom: Int): Double? {
            val h = lastZoom?.let { (it + currentZoom) / 2.0 } ?: currentZoom.toDouble()
            lastZoom = currentZoom.toDouble()
            return h
        }

        override fun specFor(x: Int, y: Int, zoom: Int): HeatmapLayerSpec {
            return com.adsamcik.tracker.map.tiles.heatmapSpec {
                source = GeoSource.LOCATION
                weightColumn = "hor_acc"
                aggregation = Aggregation.Avg

                weightNormalizer = { acc ->
                    val normMax = DEFAULT_REQUIRED_ACCURACY_METERS
                    (1.0 - (acc / normMax)).coerceIn(0.0, 1.0).toFloat()
                }
                neighborClamp = 1f

                colorScheme = HeatmapColorScheme.viridis()
                maxHeat = DEFAULT_MAX_HEAT
                ageThresholdSec = DEFAULT_AGE_THRESHOLD_SECONDS

                weightMerge = MergePolicies.additive
                alphaMerge = MergePolicies.alphaOver
                valueCurve = ValueCurves.smoothstep(0.65f)

                heatmapBaseSize = HeatmapEngine.BASE_HEATMAP_SIZE
                scaleWithQuality = true
                radiusComputer = { z, metersPerPixel, hZoom ->
                    val targetZoom = hZoom ?: z.toDouble()
                    val baseMeterSize = BASE_HEAT_SIZE_IN_METERS * HEATMAP_ZOOM_SCALE.pow((MapConstants.MAX_ZOOM - targetZoom))
                    val baseRadius = ceil(baseMeterSize / metersPerPixel).toInt().coerceAtLeast(1)
                    val maxRadius = ceil((baseMeterSize * 2.0) / metersPerPixel).toInt().coerceAtLeast(baseRadius)
                    RadiusInfo(baseRadius, maxRadius)
                }
                buildStamp = { r -> HeatmapStamp.generateGaussian(r) }
                dynamicStampProvider = { loc, metersPerPixel, _, maxRadius ->
                    val targetZoom = lastZoom ?: zoom.toDouble()
                    val baseMeterSize = BASE_HEAT_SIZE_IN_METERS * HEATMAP_ZOOM_SCALE.pow((MapConstants.MAX_ZOOM - targetZoom))
                    val inaccuracy = (1.0 - loc.normalizedWeight).coerceIn(0.0, 1.0)
                    val dynamicMeters = baseMeterSize * (0.6 + inaccuracy * 1.4)
                    val dynamicRadius = ceil(dynamicMeters / metersPerPixel).toInt().coerceAtLeast(1).coerceAtMost(maxRadius)
                    HeatmapStamp.generateGaussian(dynamicRadius)
                }
                ambientStampProvider = null
                ambientWeightScale = 0f
                neighborNormSize = 64
            }
        }

        companion object {
            private const val BASE_HEAT_SIZE_IN_METERS = 40.0
            private const val HEATMAP_ZOOM_SCALE = 1.4
            private const val DEFAULT_MAX_HEAT = 100f
            private const val DEFAULT_REQUIRED_ACCURACY_METERS = 50.0
            private const val DEFAULT_AGE_THRESHOLD_SECONDS = 15 * 60
        }
    }
}
