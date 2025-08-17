package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.MapConstants
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

        // Temporal cache to smooth neighborhood saturation (percentile) across frames
        private data class SatEntry(var p: Float, var t: Long)
        private val satCache = object : java.util.LinkedHashMap<String, SatEntry>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SatEntry>?): Boolean = size > 512
        }
        private fun tileKey(x: Int, y: Int, zoom: Int) = "$zoom/$x/$y"
        private fun smoothSaturation(key: String, raw: Float): Float {
            val now = System.currentTimeMillis()
            val prev = satCache[key]
            if (prev == null) {
                satCache[key] = SatEntry(raw, now)
                return raw
            }
            val dt = (now - prev.t).coerceAtLeast(0L).toFloat()
            val tau = 800f // ms time constant for EMA
            val alpha = (dt / tau).coerceIn(0f, 1f)
            val smoothed = prev.p + (raw - prev.p) * alpha
            prev.p = smoothed
            prev.t = now
            return smoothed
        }

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

            val baseMeterSize = BASE_HEAT_SIZE_IN_METERS * HEATMAP_ZOOM_SCALE.pow((MapConstants.MAX_ZOOM - zoom).toDouble())
            val stampRadius = ceil(baseMeterSize / pixelInMeters).toInt().coerceAtLeast(1)
            // Gaussian kernel for smoother blending between neighboring points
            val stamp = HeatmapStamp.generateGaussian(stampRadius)

            val cellSizeLat = (top - bottom) / heatmapSize
            val cellSizeLon = (right - left) / heatmapSize

            // Extend query bounds so stamps overlapping the tile border are included
            val extendLatitude = cellSizeLat * (stampRadius + 1)
            val extendLongitude = cellSizeLon * (stampRadius + 1)
            val queryBounds = Bounds(
                top + extendLatitude,
                right + extendLongitude,
                bottom - extendLatitude,
                left - extendLongitude
            )

            val weighted = runBlocking(Dispatchers.IO) {
                repo.queryWeightedAggregated(
                    query = GeoQuery(
                        source = GeoSource.LOCATION,
                        bounds = queryBounds,
                        timeFrom = null,
                        timeTo = null
                    ),
                    weightColumn = "speed",
                    aggregation = Aggregation.Avg,
                    cellSizeLatDeg = cellSizeLat,
                    cellSizeLonDeg = cellSizeLon
                ).first()
            }

            if (weighted.isEmpty()) return TileProvider.NO_TILE

            // Neighborhood saturation over 3x3 tiles to stabilize normalization across tiles
            val lonDelta = right - left
            val latDelta = top - bottom
            val nghLeft = left - lonDelta
            val nghRight = right + lonDelta
            val nghTop = top + latDelta
            val nghBottom = bottom - latDelta

            val normSize = 64
            val nghCellSizeLat = (nghTop - nghBottom) / normSize
            val nghCellSizeLon = (nghRight - nghLeft) / normSize

            val neighborAgg = runBlocking(Dispatchers.IO) {
                repo.queryWeightedAggregated(
                    query = GeoQuery(
                        source = GeoSource.LOCATION,
                        bounds = Bounds(nghTop, nghRight, nghBottom, nghLeft),
                        timeFrom = null,
                        timeTo = null
                    ),
                    weightColumn = "speed",
                    aggregation = Aggregation.Avg,
                    cellSizeLatDeg = nghCellSizeLat,
                    cellSizeLonDeg = nghCellSizeLon
                ).first()
            }

            var saturationOverride: Float? = null
            if (neighborAgg.isNotEmpty()) {
                val tileCount = MapFunctions.getTileCount(zoom)
                val pixelMetersNeighbor = com.adsamcik.tracker.shared.base.extension.LocationExtensions.EARTH_CIRCUMFERENCE.toDouble() /
                    tileCount.toDouble() * 3.0 / normSize.toDouble()
                val baseMeterSize = BASE_HEAT_SIZE_IN_METERS * HEATMAP_ZOOM_SCALE.pow((MapConstants.MAX_ZOOM - zoom).toDouble())
                val stampR = ceil(baseMeterSize / pixelMetersNeighbor).toInt().coerceAtLeast(1)
                val tmp = AgeWeightedHeatmap(normSize, normSize, DEFAULT_AGE_THRESHOLD_SECONDS, DEFAULT_MAX_HEAT, false)
                val stampN = HeatmapStamp.generateGaussian(stampR)
                val minTimeN = neighborAgg.minOf { it.time }
                fun normWeight(v: Double): Float = (v / DEFAULT_MAX_HEAT).toFloat().coerceIn(0f, 1f)
                neighborAgg.sortedBy { it.time }.forEach { wv ->
                    val txN = MapFunctions.toTileX(wv.lon, tileCount)
                    val tyN = MapFunctions.toTileY(wv.lat, tileCount)
                    val localX = (((txN - (x - 1)) / 3.0) * normSize).toInt()
                    val localY = (((tyN - (y - 1)) / 3.0) * normSize).toInt()
                    val ageSec = (((wv.time - minTimeN).coerceAtLeast(0L)) / com.adsamcik.tracker.shared.base.Time.SECOND_IN_MILLISECONDS).toInt()
                    tmp.addPoint(
                        localX,
                        localY,
                        ageSec,
                        normWeight(wv.weight),
                        stampN,
                        { current, _, stampValue, value -> current + stampValue * value },
                        { cur, stampValue, weight ->
                            val a = cur / 255f
                            val out = 1f - (1f - a) * (1f - stampValue * weight.coerceIn(0f,1f))
                            (out * 255f).toInt().coerceIn(0, 255)
                        }
                    )
                }
                fun percentileForZoom(z: Int): Float = if (z <= 13) 0.95f else 0.985f
                val rawSat = tmp.estimatePercentile(percentileForZoom(zoom)).coerceAtLeast(1f)
                saturationOverride = smoothSaturation(tileKey(x, y, zoom), rawSat)
            }

            val points = weighted.map { w ->
                TimeLocation2DWeighted(
                    time = w.time,
                    latitude = w.lat,
                    longitude = w.lon,
                    weight = w.weight
                )
            }

            val area = CoordinateBounds(top, right, bottom, left)
            val config = HeatmapConfig(
                colorScheme = HeatmapColorScheme.viridis(),
                maxHeat = DEFAULT_MAX_HEAT,
                dynamicHeat = false,
                ageThreshold = DEFAULT_AGE_THRESHOLD_SECONDS,
                weightMergeFunction = { original: Float, currentAlpha: Int, _: Float, new: Float ->
                    val alpha = currentAlpha / 255f
                    (new * (1 - alpha)) + (original * alpha)
                },
                alphaMergeFunction = { _: Int, newAlpha: Float, weight: Float ->
                    val normalizedWeight = weight / DEFAULT_MAX_HEAT
                    val b = (newAlpha * normalizedWeight).coerceIn(0f,1f)
                    // Soft-union with existing alpha already applied in weightMergeFunction
                    (b * 255f).toInt().coerceIn(0, 255)
                },
                valueCurve = { v ->
                    val t = 0.65f
                    val s = v * v * v * (v * (v * 6f - 15f) + 10f)
                    (1f - t) * v + t * s
                },
                alphaFromNormalized = true,
                opacity = 0.9f,
                revisitIntervalSec = 15 * 60,
                revisitEasing = com.adsamcik.tracker.map.heatmap.creators.RevisitEasing.Exponential,
                revisitEasingStrength = 3f
            )
            val data = HeatmapTileData(
                config = config,
                stamp = stamp,
                heatmapSize = heatmapSize,
                x = x,
                y = y,
                zoom = zoom,
                area = area,
                pad = (stampRadius + 1),
                saturationOverride = saturationOverride
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
            private const val DEFAULT_AGE_THRESHOLD_SECONDS = 15 * 60
        }
    }
}
