package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.map.heatmap.HeatmapTile
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileData
import com.adsamcik.tracker.map.heatmap.creators.HeatmapConfig
import com.adsamcik.tracker.map.data.Aggregation
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoQuery
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoSource
import com.adsamcik.tracker.map.graphics.BitmapPool
import com.adsamcik.tracker.map.layers.base.HeatmapLayer
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.tiles.OptimizedTileProvider
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
        private val repo: GeoRepository,
        private val pool: BitmapPool,
        perf: PerformanceManager,
    ) : OptimizedTileProvider(perf) {

        override fun generateTile(
            x: Int,
            y: Int,
            zoom: Int,
            pool: BitmapPool?,
            budgets: PerformanceManager.PerformanceBudgets
        ): Tile {
            val left = MapFunctions.toLon(x.toDouble(), zoom)
            val top = MapFunctions.toLat(y.toDouble(), zoom)
            val right = MapFunctions.toLon((x + 1).toDouble(), zoom)
            val bottom = MapFunctions.toLat((y + 1).toDouble(), zoom)
            val bounds = Bounds(top, right, bottom, left)

            val heatmapSize = HeatmapTile.BASE_HEATMAP_SIZE
            val pixelInMeters = com.adsamcik.tracker.shared.base.extension.LocationExtensions.EARTH_CIRCUMFERENCE.toDouble() /
                MapFunctions.getTileCount(zoom).toDouble() /
                heatmapSize.toDouble()

            val radius = ceil(APPROXIMATE_SIZE_IN_METERS / pixelInMeters).toInt().coerceAtLeast(1)
            val stamp = HeatmapStamp.generateNonlinear(radius) { it.pow(FALLOFF_EXPONENT) }

            val cellSizeLat = (top - bottom) / heatmapSize
            val cellSizeLon = (right - left) / heatmapSize

            val weighted = runBlocking(Dispatchers.IO) {
                repo.queryWeightedAggregated(
                    query = GeoQuery(
                        source = GeoSource.CELL,
                        bounds = bounds,
                        timeFrom = null,
                        timeTo = null
                    ),
                    weightColumn = "asu",
                    aggregation = Aggregation.Max,
                    cellSizeLatDeg = cellSizeLat,
                    cellSizeLonDeg = cellSizeLon
                ).first()
            }

            if (weighted.isEmpty()) return TileProvider.NO_TILE

            val points = weighted.map { w ->
                TimeLocation2DWeighted(
                    time = w.time,
                    latitude = w.lat,
                    longitude = w.lon,
                    weight = w.weight
                )
            }

            val area = CoordinateBounds(top, right, bottom, left)
            val config = com.adsamcik.tracker.map.heatmap.creators.HeatmapConfig(
                colorScheme = HeatmapColorScheme.default,
                maxHeat = DEFAULT_MAX_HEAT,
                dynamicHeat = false,
                ageThreshold = DEFAULT_AGE_THRESHOLD_SECONDS,
                weightMergeFunction = { current: Float, _: Int, _: Float, value: Float ->
                    max(value, current)
                },
                alphaMergeFunction = { current: Int, stampValue: Float, _: Float ->
                    val newAlpha = (stampValue * 255f).toInt()
                    max(current, newAlpha)
                }
            )
            val data = HeatmapTileData(
                config = config,
                stamp = stamp,
                heatmapSize = heatmapSize,
                x = x,
                y = y,
                zoom = zoom,
                area = area
            )
            val tile = HeatmapTile(data)
            tile.addAll(points.sortedWith(compareBy({ it.longitude }, { it.latitude })))

            val bytes = tile.toByteArray(256, pool ?: this.pool)
            return Tile(256, 256, bytes)
        }

        companion object {
            private const val APPROXIMATE_SIZE_IN_METERS = 50.0
            private const val FALLOFF_EXPONENT = 4.0f
            private const val DEFAULT_MAX_HEAT = 100f
            private const val DEFAULT_AGE_THRESHOLD_SECONDS = 15 * 60
        }
    }
}
