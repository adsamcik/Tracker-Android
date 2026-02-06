package com.adsamcik.tracker.map.tiles

import android.util.Log
import com.adsamcik.tracker.map.MapFunctions
import com.adsamcik.tracker.map.data.Aggregation
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoQuery
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoSource
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.map.heatmap.NormalizationPolicy
import com.adsamcik.tracker.map.heatmap.implementation.AgeWeightedHeatmap
import com.adsamcik.tracker.map.heatmap.implementation.AlphaMergeFunction
import com.adsamcik.tracker.map.heatmap.implementation.WeightMergeFunction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max

/**
 * Service that computes neighborhood-based saturation overrides for heatmap tiles.
 * Encapsulates: building a small temporary heatmap from neighbor aggregates,
 * percentile selection via NormalizationPolicy, and EMA-based smoothing cache.
 */
internal class NormalizationNeighborhood(
    private val repo: GeoRepository,
    private val scope: CoroutineScope,
    private val scheduleInvalidate: () -> Unit,
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {

    internal data class Config(
        val source: GeoSource,
        val weightColumn: String,
        val aggregation: Aggregation,
        val ageThresholdSec: Int,
        val maxHeat: Float,
        val weightMerge: WeightMergeFunction,
        val alphaMerge: AlphaMergeFunction
    )

    /**
     * Policy data for stamping in the neighborhood grid.
     * baseRadiusPxAtTile and metersPerPixelAtTile come from the main tile.
     */
    internal data class NormalizationStampPolicy(
        val baseRadiusPxAtTile: Int,
        val metersPerPixelAtTile: Double,
        val buildStamp: (radius: Int) -> HeatmapStamp
    )

    private var cfg: Config? = null

    fun updateConfig(newConfig: Config) {
        cfg = newConfig
    }

    // EMA cache for neighborhood saturation smoothing
    private data class SatEntry(var p: Float, var t: Long)
    private val satCache = object : java.util.LinkedHashMap<String, SatEntry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SatEntry>?): Boolean = size > 512
    }

    private fun smoothSaturation(key: String, raw: Float): Float {
        val now = System.currentTimeMillis()
        val prev = satCache[key]
        val (smoothed, ts) = NormalizationPolicy.smoothSaturation(prev?.p, prev?.t, raw, now)
        if (prev == null) satCache[key] = SatEntry(smoothed, ts) else { prev.p = smoothed; prev.t = ts }
        return smoothed
    }

    /**
     * Compute a saturation override based on a 3x3 neighborhood around the tile.
     * Returns null if there isn't enough data nearby.
     */
    private sealed interface SatState { object Loading: SatState; data class Ready(val value: Float): SatState }
    private val neighborhoodStates = java.util.concurrent.ConcurrentHashMap<String, SatState>()

    fun ensureAndGetOverride(
        tileKey: String,
        zoom: Int,
        bounds3x3: Bounds,
        normSize: Int,
        stampPolicy: NormalizationStampPolicy,
        weightNormalizer: (Double) -> Float
    ): Float? {
        val cfg = this.cfg ?: return null
        when (val st = neighborhoodStates[tileKey]) {
            is SatState.Ready -> return st.value
            SatState.Loading -> return null
            null -> {
                neighborhoodStates[tileKey] = SatState.Loading
                val compute: suspend () -> Float? = {
                    try {
                        val nghCellSizeLat = (bounds3x3.north - bounds3x3.south) / normSize
                        val nghCellSizeLon = (bounds3x3.east - bounds3x3.west) / normSize
                        val neighborAgg = repo.queryWeightedAggregated(
                            query = GeoQuery(
                                source = cfg.source,
                                bounds = bounds3x3,
                                timeFrom = null,
                                timeTo = null
                            ),
                            weightColumn = cfg.weightColumn,
                            aggregation = cfg.aggregation,
                            cellSizeLatDeg = nghCellSizeLat,
                            cellSizeLonDeg = nghCellSizeLon
                        ).first()
                        if (neighborAgg.isEmpty()) {
                            neighborhoodStates.remove(tileKey)
                            scheduleInvalidate()
                            // No data; exit with null
                            null
                        } else {
                            val tileCount = MapFunctions.getTileCount(zoom)
                            val pixelMetersNeighbor = com.adsamcik.tracker.shared.base.extension.LocationExtensions.EARTH_CIRCUMFERENCE.toDouble() /
                                tileCount.toDouble() * 3.0 / normSize.toDouble()
                            val stampR = ceil((stampPolicy.baseRadiusPxAtTile * stampPolicy.metersPerPixelAtTile) / pixelMetersNeighbor)
                                .toInt().coerceAtLeast(1)
                            val tmp = AgeWeightedHeatmap(normSize, normSize, cfg.ageThresholdSec, cfg.maxHeat)
                            val stampN = stampPolicy.buildStamp(stampR)
                            val minTimeN = neighborAgg.minOf { it.time }
                            neighborAgg.sortedBy { it.time }.forEach { wv ->
                                val localX = (((wv.lon - bounds3x3.west) / (bounds3x3.east - bounds3x3.west)) * normSize).toInt()
                                val localY = (((bounds3x3.north - wv.lat) / (bounds3x3.north - bounds3x3.south)) * normSize).toInt()
                                val xClamped = max(0, minOf(normSize - 1, localX))
                                val yClamped = max(0, minOf(normSize - 1, localY))
                                val ageSec = (((wv.time - minTimeN).coerceAtLeast(0L)) / com.adsamcik.tracker.shared.base.Time.SECOND_IN_MILLISECONDS).toInt()
                                tmp.addPoint(xClamped, yClamped, ageSec, weightNormalizer(wv.weight), stampN, cfg.weightMerge, cfg.alphaMerge)
                            }
                            val cov = tmp.activeCoverage()
                            val p = NormalizationPolicy.percentileFor(zoom, cov)
                            val rawSat = NormalizationPolicy.robustPercentile(tmp, p).coerceAtLeast(1f)
                            val smoothed = smoothSaturation(tileKey, rawSat)
                            neighborhoodStates[tileKey] = SatState.Ready(smoothed)
                            scheduleInvalidate()
                            smoothed
                        }
                    } catch (e: Throwable) {
                        Log.w("NormalizationNeighborhood", "Failed to compute neighborhood saturation for tile $tileKey: ${e.message}")
                        neighborhoodStates.remove(tileKey)
                        scheduleInvalidate()
                        null
                    }
                }
                // Always use async execution via scope.launch; no blocking calls.
                scope.launch(dispatcher) { compute() }
                return null
            }
        }
    }
}
