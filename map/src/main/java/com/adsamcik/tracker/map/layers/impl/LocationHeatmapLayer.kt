package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.MapConstants
import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.engine.Event
import com.adsamcik.tracker.map.heatmap.engine.HeatParams
import com.adsamcik.tracker.map.heatmap.engine.HeatTileEngine
import com.adsamcik.tracker.map.heatmap.engine.RectD
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
            // Build engine request
            val params = HeatParams(
                dtMinutes = 15,
                hsMetersBase = BASE_HEAT_SIZE_IN_METERS,
                htMinutesBase = DEFAULT_AGE_THRESHOLD_MINUTES,
                supersample = max(1, (currentQuality() * 2).toInt()),
                extraBlurPx = 0,
                multiscale = false,
                useFlows = true
            )

            // Repo-backed fetcher returning Δt events within expanded bbox and time window
        val fetcher = { bbox: RectD, tFrom: Long, tTo: Long ->
                runBlocking(Dispatchers.IO) {
            // Approximate resampling: query weighted aggregates on a grid that matches the working resolution.
            val left = bbox.left; val right = bbox.right; val bottom = bbox.bottom; val top = bbox.top
            val heatSize = com.adsamcik.tracker.map.heatmap.HeatmapTile.BASE_HEATMAP_SIZE * params.supersample
                    val cellSizeLat = (top - bottom) / heatSize
                    val cellSizeLon = (right - left) / heatSize
                    val rows = repo.queryWeightedAggregated(
                        query = GeoQuery(
                            source = GeoSource.LOCATION,
                            bounds = Bounds(top, right, bottom, left),
                            timeFrom = tFrom,
                            timeTo = tTo
                        ),
                        weightColumn = "hor_acc",
                        aggregation = com.adsamcik.tracker.map.data.Aggregation.Avg,
                        cellSizeLatDeg = cellSizeLat,
                        cellSizeLonDeg = cellSizeLon
                    ).first()
                    rows
                }.map { w -> Event(w.time!!, w.lat, w.lon, w.weight) }
            }

            val bmp = HeatTileEngine.renderHeatTile(
                req = HeatTileEngine.Request(
                    z = zoom, x = x, y = y,
                    timeMs = System.currentTimeMillis(),
                    params = params,
                    colorScheme = HeatmapColorScheme.viridis(),
                    supersample = max(1, (currentQuality() * 2).toInt()),
                    padExtra = 0,
                    quantiles = null,
                    hsMetersBase = BASE_HEAT_SIZE_IN_METERS
                ),
                fetch = fetcher
            )

            val out = java.io.ByteArrayOutputStream(bmp.byteCount)
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            val bytes = out.toByteArray()
            bmp.recycle()
            return Tile(256, 256, bytes)
        }

        companion object {
            private const val BASE_HEAT_SIZE_IN_METERS = 40.0
            private const val HEATMAP_ZOOM_SCALE = 1.4
            private const val DEFAULT_MAX_HEAT = 100f
            private const val DEFAULT_REQUIRED_ACCURACY_METERS = 50.0
            private const val DEFAULT_AGE_THRESHOLD_MINUTES = 30
        }
    }
}
