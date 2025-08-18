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

    // Last zoom value used to compute hysteresis for smoother transitions
    private var lastZoom: Double? = null
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

            // Zoom hysteresis: smooth radius changes across zoom levels to avoid popping
            val hysteresisZoom = lastZoom?.let { (it + zoom) / 2.0 } ?: zoom.toDouble()
            lastZoom = zoom.toDouble()
            // Choose stamp sizing mode: constant tile-pixel radius or meter-based
            val constantTilePxRadius: Int? = null // set e.g. 18 for ~constant look across zoom
            val baseMeterSize = BASE_HEAT_SIZE_IN_METERS * HEATMAP_ZOOM_SCALE.pow((MapConstants.MAX_ZOOM - hysteresisZoom))
            val stampRadius = (constantTilePxRadius ?: ceil(baseMeterSize / pixelSizeMeters).toInt()).coerceAtLeast(1)
            // Dynamic radius can go up to ~2.0x base; compute max to size overscan and queries safely
            val maxDynamicRadius = ceil((baseMeterSize * 2.0) / pixelSizeMeters).toInt().coerceAtLeast(stampRadius)
            // Default stamp for fallback
            val defaultStamp = HeatmapStamp.generateGaussian(stampRadius)
            // Cache for dynamic stamps by radius
            val stampCache = HashMap<Int, HeatmapStamp>()
            fun stampForRadius(r: Int): HeatmapStamp = stampCache.getOrPut(r) { HeatmapStamp.generateGaussian(r) }

            // Aggregate points into grid cells roughly matching pixel size
            val cellSizeLat = (top - bottom) / heatmapSize
            val cellSizeLon = (right - left) / heatmapSize

            // Extend query bounds so stamps near edges contribute across tile borders
            val extendLatitude = cellSizeLat * (maxDynamicRadius + 1)
            val extendLongitude = cellSizeLon * (maxDynamicRadius + 1)
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
                    weightColumn = "hor_acc",
                    aggregation = com.adsamcik.tracker.map.data.Aggregation.Avg,
                    cellSizeLatDeg = cellSizeLat,
                    cellSizeLonDeg = cellSizeLon
                ).first()
            }

            if (weighted.isEmpty()) return TileProvider.NO_TILE

            // Neighborhood saturation: compute robust percentile over a 3x3 tile area using a small temporary heatmap
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
                    weightColumn = "hor_acc",
                    aggregation = com.adsamcik.tracker.map.data.Aggregation.Avg,
                    cellSizeLatDeg = nghCellSizeLat,
                    cellSizeLonDeg = nghCellSizeLon
                ).first()
            }

            var saturationOverride: Float? = null
            if (neighborAgg.isNotEmpty()) {
                val tileCount = MapFunctions.getTileCount(zoom)
                val pixelMetersNeighbor = com.adsamcik.tracker.shared.base.extension.LocationExtensions.EARTH_CIRCUMFERENCE.toDouble() /
                    tileCount.toDouble() * 3.0 / normSize.toDouble()
                val stampR = ceil(baseMeterSize / pixelMetersNeighbor).toInt().coerceAtLeast(1)
                val tmp = AgeWeightedHeatmap(normSize, normSize, DEFAULT_AGE_THRESHOLD_SECONDS, DEFAULT_MAX_HEAT, false)
                val stampN = HeatmapStamp.generateGaussian(stampR)
                val minTimeN = neighborAgg.minOf { it.time }

                fun normAccToWeight(acc: Double): Float {
                    val normMax = DEFAULT_REQUIRED_ACCURACY_METERS
                    val v = 1.0 - (acc / normMax)
                    return v.coerceIn(0.0, 1.0).toFloat()
                }

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
                        normAccToWeight(wv.weight),
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

            // Compute a neighborhood-based percentile for tone consistency: approximate using current tile sample
            // and modestly bias upward with nearby-tiles heuristic (inflate by 5%).
            val neighborhoodPercentile = 0.985f
            val neighborSaturationBias = 1.05f

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
            val config = HeatmapConfig(
                colorScheme = HeatmapColorScheme.viridis(),
                maxHeat = DEFAULT_MAX_HEAT,
                dynamicHeat = false,
                ageThreshold = DEFAULT_AGE_THRESHOLD_SECONDS,
                weightMergeFunction = { current: Float, _: Int, stampValue: Float, value: Float -> current + stampValue * value },
                alphaMergeFunction = { current: Int, stampValue: Float, weight: Float ->
                    val a = (current.coerceIn(0,255)) / 255f
                    // Weight-gated soft union so low-weight points don’t appear fully opaque
                    val out = 1f - (1f - a) * (1f - stampValue * weight.coerceIn(0f,1f))
                    (out * 255f).toInt().coerceIn(0, 255)
                },
                valueCurve = { v ->
                    // Widen midrange: gentle S-curve that keeps endpoints but adds more steps around 0.3-0.7
                    // Curve: mix of identity and smootherstep; t = 0.65 gives ~wider middle
                    val t = 0.65f
                    val s = v * v * v * (v * (v * 6f - 15f) + 10f) // smootherstep
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
                stamp = defaultStamp,
                stampProvider = { loc ->
                    // Dynamic radius: scale with (1 - normalizedWeight) ~ reported accuracy
                    // normalizedWeight in this layer ~ 1 - (accuracy / normMax); invert to get blur factor
                    val inaccuracy = (1.0 - loc.normalizedWeight).coerceIn(0.0, 1.0)
                    val dynamicMeters = baseMeterSize * (0.6 + inaccuracy * 1.4) // range ~0.6x..2.0x
                    val dynamicRadius = ceil(dynamicMeters / pixelSizeMeters).toInt().coerceAtLeast(1).coerceAtMost(maxDynamicRadius)
                    stampForRadius(dynamicRadius)
                },
                // Ambient disabled by default; density-aware gain + smoothing will handle continuity
                heatmapSize = heatmapSize,
                x = x,
                y = y,
                zoom = zoom,
                area = area,
                pad = (maxDynamicRadius + 4),
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
            private const val DEFAULT_REQUIRED_ACCURACY_METERS = 50.0
            private const val DEFAULT_AGE_THRESHOLD_SECONDS = 15 * 60
        }
    }
}
