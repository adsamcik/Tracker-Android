package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.engine.Event
import com.adsamcik.tracker.map.heatmap.engine.HeatParams
import com.adsamcik.tracker.map.heatmap.engine.HeatTileEngine
import com.adsamcik.tracker.map.heatmap.engine.QuantileService
import com.adsamcik.tracker.map.data.Aggregation
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoQuery
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoSource
import com.adsamcik.tracker.map.graphics.BitmapPool
import com.adsamcik.tracker.map.layers.base.HeatmapLayer
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.tiles.OptimizedTileProvider
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileOverlayOptions
import com.google.android.gms.maps.model.TileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.math.max

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
            val params = HeatParams(
                dtMinutes = 15,
                hsMetersBase = 50.0,
                htMinutesBase = 30,
                supersample = max(1, (currentQuality() * 2).toInt()),
                extraBlurPx = 0,
                multiscale = true,
                useFlows = false
            )

        val fetcher = { bbox: com.adsamcik.tracker.map.heatmap.engine.RectD, tFrom: Long, tTo: Long ->
                runBlocking(Dispatchers.IO) {
            val left = bbox.left; val right = bbox.right; val bottom = bbox.bottom; val top = bbox.top
                    val heatSize = com.adsamcik.tracker.map.heatmap.HeatmapTile.BASE_HEATMAP_SIZE * params.supersample
                    val cellSizeLat = (top - bottom) / heatSize
                    val cellSizeLon = (right - left) / heatSize
                    repo.queryWeightedAggregated(
                        query = GeoQuery(
                            source = GeoSource.CELL,
                            bounds = Bounds(top, right, bottom, left),
                            timeFrom = tFrom,
                            timeTo = tTo
                        ),
                        weightColumn = "asu",
                        aggregation = Aggregation.Max,
                        cellSizeLatDeg = cellSizeLat,
                        cellSizeLonDeg = cellSizeLon
                    ).first()
                }.map { w -> Event(w.time, w.lat, w.lon, w.weight) }
            }

        // Quantiles window matching engine's internal window
        val now = System.currentTimeMillis()
        val htSec = params.htMinutesBase * 60L
        val tFrom = now - 3 * htSec * 1000
        val tTo = now + 3 * htSec * 1000
        val key = QuantileService.WindowKey(zoom, tFrom, tTo)
        qService.begin(key)

        val bmp = HeatTileEngine.renderHeatTile(
                req = HeatTileEngine.Request(
                    z = zoom, x = x, y = y,
            timeMs = now,
                    params = params,
                    colorScheme = HeatmapColorScheme.viridis(),
            supersample = max(1, (currentQuality() * 2).toInt()),
            qService = qService,
            qKey = key,
            collectQuantiles = true
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
                private val qService = QuantileService()
            }
    }
}
