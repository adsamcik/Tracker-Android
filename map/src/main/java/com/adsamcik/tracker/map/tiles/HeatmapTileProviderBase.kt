package com.adsamcik.tracker.map.tiles

import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.data.Aggregation
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoQuery
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.graphics.BitmapPool
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.map.heatmap.HeatmapEngine
import com.adsamcik.tracker.map.heatmap.creators.HeatmapConfig
import com.adsamcik.tracker.map.heatmap.creators.HeatmapTileData
import com.adsamcik.tracker.map.heatmap.implementation.AgeWeightedHeatmap
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted
import com.adsamcik.tracker.shared.map.CoordinateBounds
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.math.ceil
import kotlin.math.max

/**
 * Base provider that implements shared heatmap tile generation pipeline.
 * Concrete layers only supply a HeatmapLayerSpec and any per-layer tweaks.
 */
internal abstract class HeatmapTileProviderBase(
    private val repo: GeoRepository,
    private val pool: BitmapPool,
    perf: PerformanceManager
) : OptimizedTileProvider(perf) {

    private val neighborhood = NormalizationNeighborhood(repo)

    protected abstract fun specFor(x: Int, y: Int, zoom: Int): HeatmapLayerSpec

    // Optional hysteresis for smoother radius transitions; subclasses can override
    protected open fun hysteresisZoom(currentZoom: Int): Double? = null

    override fun generateTile(
        x: Int,
        y: Int,
        zoom: Int,
        pool: BitmapPool?,
        budgets: PerformanceManager.PerformanceBudgets
    ): Tile {
        val spec = specFor(x, y, zoom)

        // Determine working resolution
        val baseSize = spec.heatmapBaseSize
        val heatmapSize = if (spec.scaleWithQuality) {
            max(64, (currentQuality() * baseSize).toInt().coerceAtMost(baseSize))
        } else baseSize

        // meters per pixel at this zoom/size
        val metersPerPixel = com.adsamcik.tracker.shared.base.extension.LocationExtensions.EARTH_CIRCUMFERENCE.toDouble() /
            MapFunctions.getTileCount(zoom).toDouble() /
            heatmapSize.toDouble()

        // Bounds for the tile
        val left = MapFunctions.toLon(x.toDouble(), zoom)
        val top = MapFunctions.toLat(y.toDouble(), zoom)
        val right = MapFunctions.toLon((x + 1).toDouble(), zoom)
        val bottom = MapFunctions.toLat((y + 1).toDouble(), zoom)
        val area = CoordinateBounds(top, right, bottom, left)

        // Radius and stamp
    val radiusInfo = spec.radiusComputer(zoom, metersPerPixel, hysteresisZoom(zoom))
        val stamp = spec.buildStamp(radiusInfo.baseRadius)
    // Derive pad directly from stamp extent (maxRadius); avoid arbitrary extra padding
    val pad = radiusInfo.maxRadius

        // Grid aggregation sizes
        val cellSizeLat = (top - bottom) / heatmapSize
        val cellSizeLon = (right - left) / heatmapSize

        // Extend query bounds so stamps near edges contribute across borders
        val extendLatitude = cellSizeLat * (radiusInfo.maxRadius + 1)
        val extendLongitude = cellSizeLon * (radiusInfo.maxRadius + 1)
        val queryBounds = Bounds(
            north = top + extendLatitude,
            east = right + extendLongitude,
            south = bottom - extendLatitude,
            west = left - extendLongitude
        )

        // Query aggregated points
        val weighted = runBlocking(Dispatchers.IO) {
            repo.queryWeightedAggregated(
                query = GeoQuery(
                    source = spec.source,
                    bounds = queryBounds,
                    timeFrom = null,
                    timeTo = null
                ),
                weightColumn = spec.weightColumn,
                aggregation = spec.aggregation,
                cellSizeLatDeg = cellSizeLat,
                cellSizeLonDeg = cellSizeLon
            ).first()
        }
        if (weighted.isEmpty()) return TileProvider.NO_TILE

        // Neighborhood-based saturation override over 3x3 tiles via service
        neighborhood.updateConfig(
            NormalizationNeighborhood.Config(
                source = spec.source,
                weightColumn = spec.weightColumn,
                aggregation = spec.aggregation,
                ageThresholdSec = spec.ageThresholdSec,
                maxHeat = spec.maxHeat,
                weightMerge = spec.weightMerge,
                alphaMerge = spec.alphaMerge
            )
        )

        val lonDelta = right - left
        val latDelta = top - bottom
        val bounds3x3 = Bounds(
            north = top + latDelta,
            east = right + lonDelta,
            south = bottom - latDelta,
            west = left - lonDelta
        )

        val saturationOverride = neighborhood.computeSaturationOverride(
            tileKey = "$zoom/$x/$y",
            zoom = zoom,
            bounds3x3 = bounds3x3,
            normSize = spec.neighborNormSize,
            stampPolicy = NormalizationNeighborhood.NormalizationStampPolicy(
                baseRadiusPxAtTile = radiusInfo.baseRadius,
                metersPerPixelAtTile = metersPerPixel,
                buildStamp = spec.buildStamp
            ),
            weightNormalizer = { v ->
                val n = spec.weightNormalizer(v)
                spec.neighborClamp?.let { clamp -> n.coerceIn(0f, clamp) } ?: n
            }
        )

        // Map to tile points
        val points = weighted.map { w ->
            val desired = spec.weightNormalizer(w.weight).toDouble().coerceIn(0.0, 1.0)
            val effMax = if (desired >= 1.0 || w.weight <= 0.0) 1e9 else w.weight / (1.0 - desired)
            TimeLocation2DWeighted(
                time = w.time,
                latitude = w.lat,
                longitude = w.lon,
                weight = w.weight
            ).also { it.normalize(effMax) }
        }

        val config = HeatmapConfig(
            colorScheme = spec.colorScheme,
            maxHeat = spec.maxHeat,
            ageThreshold = spec.ageThresholdSec,
            weightMergeFunction = spec.weightMerge,
            alphaMergeFunction = spec.alphaMerge,
            valueCurve = spec.valueCurve,
            alphaFromNormalized = spec.alphaFromNormalized,
            opacity = spec.opacity,
            weightPolicyRo = spec.weightPolicyRo
        )

        val data = HeatmapTileData(
            config = config,
            stamp = stamp,
            stampProvider = spec.dynamicStampProvider?.let { dsp ->
                { loc ->
                    val s = dsp(loc, metersPerPixel, radiusInfo.baseRadius, radiusInfo.maxRadius)
                    s
                }
            },
            ambientStampProvider = spec.ambientStampProvider,
            ambientWeightScale = spec.ambientWeightScale,
            heatmapSize = heatmapSize,
            x = x,
            y = y,
            zoom = zoom,
            area = area,
            pad = pad,
            saturationOverride = saturationOverride
        )

    val tile = HeatmapEngine(data)
    // Sort by time once here; the tile has an addAllSorted to avoid resorting
    val sorted = points.sortedBy { it.time }
    tile.addAllSorted(sorted)

        val bytes = tile.toByteArray(256, pool ?: this.pool)
        return Tile(256, 256, bytes)
    }
}
