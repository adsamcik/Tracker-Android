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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancelAndJoin
import kotlin.math.ceil
import kotlin.math.max

/**
 * Base provider that implements shared heatmap tile generation pipeline.
 * Concrete layers only supply a HeatmapLayerSpec and any per-layer tweaks.
 */
internal abstract class HeatmapTileProviderBase(
    private val repo: GeoRepository,
    private val pool: BitmapPool,
    perf: PerformanceManager,
    invalidateTiles: () -> Unit = {}, // initial no-op; layer wires later
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val prefetchDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val externalScope: CoroutineScope? = null,
) : OptimizedTileProvider(perf) {

    // Mutable so layer can inject overlay.clearTileCache() after overlay created
    private var invalidateTilesCb: () -> Unit = invalidateTiles
    fun setInvalidateTilesCallback(cb: () -> Unit) { invalidateTilesCb = cb }

    private val cacheScope: CoroutineScope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var invalidateJob: Job? = null
    private var firstDebounceAt: Long = 0L
    private fun scheduleInvalidate() {
        val now = System.currentTimeMillis()
        if (invalidateJob?.isActive == true) {
            // Force flush if waiting too long (max wait 300ms)
            if (now - firstDebounceAt > 300) {
                invalidateJob?.cancel()
            } else return
        }
        firstDebounceAt = now
        invalidateJob = cacheScope.launch {
            delay(150)
            // TileOverlay.clearTileCache() must be called on the main thread
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                invalidateTilesCb()
            }
        }
    }
    private val dataCache = HeatmapDataCache(repo, cacheScope, onEntryReady = { scheduleInvalidate() }, ioDispatcher = ioDispatcher)
    private val neighborhood = NormalizationNeighborhood(repo, cacheScope, { scheduleInvalidate() }, dispatcher = ioDispatcher)

    // Active prefetch job (viewport). Cancels previous when a new one starts.
    private var prefetchJob: Job? = null
    @Volatile internal var testPrefetchEnsures: Int = 0 // test hook

    /** Prefetch tiles covering the given geographic bounds (lat/lon degrees) at zoom plus a border. */
    private var lastPrefetchMs: Long = -1L
    fun prefetchViewport(bounds: CoordinateBounds, zoom: Int, borderTiles: Int = 1, inline: Boolean = false) {
        if (zoom < 5) return // skip overly broad prefetch

        suspend fun doWork() {
            // Cancel any previous prefetch first
            prefetchJob?.cancel()
            val tileCount = MapFunctions.getTileCount(zoom)
            fun lonToX(lon: Double): Int = ((lon + 180.0) / 360.0 * tileCount).toInt().coerceIn(0, tileCount - 1)
            fun latToY(lat: Double): Int {
                val latRad = Math.toRadians(lat)
                val n = Math.log(Math.tan(Math.PI / 4 + latRad / 2))
                return (((1 - n / Math.PI) / 2 * tileCount).toInt()).coerceIn(0, tileCount - 1)
            }
            // Compute tile span; Mercator Y decreases as latitude increases, so order may invert.
            val minX = lonToX(bounds.left) - borderTiles
            val maxX = lonToX(bounds.right) + borderTiles
            val ySouth = latToY(bounds.bottom)
            val yNorth = latToY(bounds.top)
            val rawMinY = kotlin.math.min(ySouth, yNorth) - borderTiles
            val rawMaxY = kotlin.math.max(ySouth, yNorth) + borderTiles
            val minY = rawMinY
            val maxY = rawMaxY
            val candidateCount = (maxX - minX + 1) * (maxY - minY + 1)
            val now = System.currentTimeMillis()
            if (candidateCount > 20 && lastPrefetchMs >= 0 && (now - lastPrefetchMs) < 250) return
            lastPrefetchMs = now
            val activeJob = prefetchJob
            for (x in minX..maxX) {
                if (activeJob?.isActive == false) return
                if (x < 0 || x >= tileCount) continue
                for (y in minY..maxY) {
                    if (y < 0 || y >= tileCount) continue
                    // Derive a lightweight spec to compute cell sizes & query; we only need normalization of grid sizes.
                    val spec = specFor(x, y, zoom)
                    val baseSize = spec.heatmapBaseSize
                    val heatmapSize = if (spec.scaleWithQuality) {
                        max(64, (currentQuality() * baseSize).toInt().coerceAtMost(baseSize))
                    } else baseSize
                    val left = MapFunctions.toLon(x.toDouble(), zoom)
                    val top = MapFunctions.toLat(y.toDouble(), zoom)
                    val right = MapFunctions.toLon((x + 1).toDouble(), zoom)
                    val bottom = MapFunctions.toLat((y + 1).toDouble(), zoom)
                    val cellSizeLat = (top - bottom) / heatmapSize
                    val cellSizeLon = (right - left) / heatmapSize
                    // Minimal ensure to start async fetch; ignore return state.
                    dataCache.ensure(
                        query = GeoQuery(spec.source, Bounds(top, right, bottom, left), null, null),
                        weightColumn = spec.weightColumn,
                        aggregation = spec.aggregation::class.simpleName ?: spec.aggregation.toString(),
                        zoom = zoom,
                        x = x,
                        y = y,
                        cellLatDeg = cellSizeLat,
                        cellLonDeg = cellSizeLon,
                        aggregationEnum = spec.aggregation
                    )
                    testPrefetchEnsures++
                }
            }
        }

        if (inline) {
            // Run synchronously for deterministic unit tests.
            kotlinx.coroutines.runBlocking(prefetchDispatcher) { doWork() }
        } else {
            prefetchJob = cacheScope.launch(prefetchDispatcher) { doWork() }
        }
    }

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

        val query = GeoQuery(
            source = spec.source,
            bounds = queryBounds,
            timeFrom = null,
            timeTo = null
        )
        val entry = dataCache.get(
            source = spec.source.ordinal,
            weightColumn = spec.weightColumn,
            aggregation = spec.aggregation::class.simpleName ?: spec.aggregation.toString(),
            zoom = zoom,
            x = x,
            y = y,
            cellLatDeg = cellSizeLat,
            cellLonDeg = cellSizeLon,
        )
        val weighted = when (entry) {
            is HeatmapDataCache.EntryState.Ready -> entry.data
            is HeatmapDataCache.EntryState.Empty -> return TileProvider.NO_TILE
            null, HeatmapDataCache.EntryState.Loading -> {
                // Trigger async fetch then return NO_TILE as a temporary placeholder.
                // Maps SDK will request again (it retries missing tiles with backoff).
                dataCache.ensure(
                    query = query,
                    weightColumn = spec.weightColumn,
                    aggregation = spec.aggregation::class.simpleName ?: spec.aggregation.toString(),
                    zoom = zoom,
                    x = x,
                    y = y,
                    cellLatDeg = cellSizeLat,
                    cellLonDeg = cellSizeLon,
                    aggregationEnum = spec.aggregation
                )
                return TileProvider.NO_TILE
            }
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

        val saturationOverride = neighborhood.ensureAndGetOverride(
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
