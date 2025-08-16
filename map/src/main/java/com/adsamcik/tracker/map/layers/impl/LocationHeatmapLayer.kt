package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.MapConstants
import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.map.heatmap.HeatmapTile
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileData
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

/**
 * v2 implementation of Location Heatmap using GeoRepository + OptimizedTileProvider.
 * This does not change UI wiring yet; it’s ready to be enabled via v2 flow.
 */
class LocationHeatmapLayer(
    private val repo: GeoRepository,
    private val pool: BitmapPool,
    private val perf: PerformanceManager = PerformanceManager()
) : HeatmapLayer<LocationHeatmapLayer.Input, LocationHeatmapLayer.Prepared>() {

    data class Input(val dummy: Unit = Unit) // Placeholder: input is pulled per-tile
    data class Prepared(val provider: TileProvider)

    private lateinit var provider: TileProviderV2

    override fun beforeEnable(context: Context, map: GoogleMap) {
        // no-op for now
    }

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
        private val perf: PerformanceManager,
    ) : OptimizedTileProvider(perf) {

        override fun generateTile(
            x: Int,
            y: Int,
            zoom: Int,
            pool: BitmapPool?,
            budgets: PerformanceManager.PerformanceBudgets
    ): Tile {
            // Compute geographic bounds for the tile
            val left = MapFunctions.toLon(x.toDouble(), zoom)
            val top = MapFunctions.toLat(y.toDouble(), zoom)
            val right = MapFunctions.toLon((x + 1).toDouble(), zoom)
            val bottom = MapFunctions.toLat((y + 1).toDouble(), zoom)

            val bounds = Bounds(top, right, bottom, left)

            // Choose heatmap working resolution based on quality (then scale to 256 px tile)
            val baseSize = HeatmapTile.BASE_HEATMAP_SIZE
            val heatmapSize = max(64, (currentQuality() * baseSize).toInt().coerceAtMost(baseSize))

            // Compute pixel size to derive stamp size similar to v1
            val pixelSizeMeters = com.adsamcik.tracker.shared.base.extension.LocationExtensions.EARTH_CIRCUMFERENCE.toDouble() /
                MapFunctions.getTileCount(zoom).toDouble() /
                heatmapSize.toDouble()

            val baseMeterSize = BASE_HEAT_SIZE_IN_METERS * HEATMAP_ZOOM_SCALE.pow((MapConstants.MAX_ZOOM - zoom).toDouble())
            val stampRadius = ceil(baseMeterSize / pixelSizeMeters).toInt().coerceAtLeast(1)
            val stamp = HeatmapStamp.generateNonlinear(stampRadius) { it.pow(2f) }

            // Aggregate points into grid cells roughly matching pixel size
            val cellSizeLat = (top - bottom) / heatmapSize
            val cellSizeLon = (right - left) / heatmapSize

            val weighted = runBlocking(Dispatchers.IO) {
                repo.queryWeightedAggregated(
                    query = GeoQuery(
                        source = GeoSource.LOCATION,
                        bounds = bounds,
                        timeFrom = null,
                        timeTo = null
                    ),
                    weightColumn = "hor_acc",
                    aggregation = com.adsamcik.tracker.map.data.Aggregation.Sum,
                    cellSizeLatDeg = cellSizeLat,
                    cellSizeLonDeg = cellSizeLon
                ).first()
            }

            if (weighted.isEmpty()) return TileProvider.NO_TILE

            // Map to v1 heatmap-friendly data model with simple normalization
            val normMax = DEFAULT_REQUIRED_ACCURACY_METERS
            val points = weighted.map { w ->
                TimeLocation2DWeighted(
                    time = w.time,
                    latitude = w.lat,
                    longitude = w.lon,
                    weight = w.weight
                ).also { it.normalize(normMax) }
            }

            val area = CoordinateBounds(top, right, bottom, left)
            val config = com.adsamcik.tracker.map.heatmap.creators.HeatmapConfig(
                colorScheme = HeatmapColorScheme.default,
                maxHeat = DEFAULT_MAX_HEAT,
                dynamicHeat = false,
                ageThreshold = DEFAULT_AGE_THRESHOLD_SECONDS,
                weightMergeFunction = { current, _, stampValue, value -> current + stampValue * value },
                alphaMergeFunction = { current, stampValue, _ -> max(current, (stampValue * 255f).toInt()) }
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
            private const val BASE_HEAT_SIZE_IN_METERS = 40.0
            private const val HEATMAP_ZOOM_SCALE = 1.4
            private const val DEFAULT_MAX_HEAT = 100f
            private const val DEFAULT_REQUIRED_ACCURACY_METERS = 50.0
            private const val DEFAULT_AGE_THRESHOLD_SECONDS = 15 * 60
        }
    }
}
