package com.adsamcik.tracker.map.ui

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.layers.base.LayerReloadResult
import com.adsamcik.tracker.map.layers.base.SupportsDateRange
import com.adsamcik.tracker.map.presentation.bridge.LayerRefreshResult
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.shared.MapLayerData
import com.adsamcik.tracker.map.shared.layers.LayerDescriptor
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong

/**
 * Controls map layer lifecycle and manages active layer state.
 * Layers now produce [MapLibreLayerConfig] data rather than mutating a map.
 */
class LayerController {
    internal data class ViewportCacheKey(
        val bounds: BucketedBounds?,
        val zoom: Int,
        val dateFromMs: Long,
        val dateToMs: Long,
        val qualityBucket: Int,
    )

    internal data class BucketedBounds(
        val north: Long,
        val east: Long,
        val south: Long,
        val west: Long,
    )

    private var currentLayerDescriptors: List<LayerDescriptor> = emptyList()
    private val currentLayers = mutableListOf<BaseMapLayer<*, *>>()
    private var currentLegends: List<MapLayerData> = emptyList()
    private var currentConfig: MapLibreLayerConfig? = null
    private var currentQuality: Float = 1f
    private val layerConfigCache = mutableMapOf<String, ViewportConfigCache>()
    private val stateLock = Any()

    /**
     * Monotonic generation for every controller operation that changes desired layer
     * state. This invalidates in-flight refreshes not only when a newer refresh starts,
     * but also when layers are selected, cleared, or destroyed.
     */
    private val refreshGeneration = AtomicLong(0L)

    companion object {
        /**
         * Per-layer byte budget for the viewport refresh cache. A single dense (~80k point)
         * heatmap is ~12 MB UTF-16; 16 MB lets one large payload plus a small recent neighbour
         * coexist without unbounded retention. With ~5 active heatmap layers the worst-case
         * retention is ~80 MB (down from ~300 MB with the previous 5-bucket-per-layer cache;
         * see R2 round 5 finding #3). The single most-recent entry is always retained even
         * when it alone exceeds the budget, preserving the original perf intent of skipping
         * the GeoJSON re-encode on rapid back-and-forth pan within the same viewport bucket.
         */
        internal const val MAX_CACHE_BYTES_PER_LAYER: Long = 16L * 1024L * 1024L

        private const val QUALITY_BUCKET_SCALE = 100
        private const val MIN_BUCKET_DEGREES = 0.000001
    }

    /**
     * Set active layer with proper cleanup and error handling.
     * Suspends until the layer pipeline completes.
     */
    suspend fun setLayer(
        context: Context,
        descriptor: LayerDescriptor?,
        quality: Float,
        dateRange: LongRange,
        bounds: Bounds? = null,
        zoom: Float = 10f,
    ) {
        setLayers(context, listOfNotNull(descriptor), quality, dateRange, bounds, zoom)
    }

    /**
     * Set active layers with proper cleanup and error handling.
     * Suspends until all layer pipelines complete.
     */
    suspend fun setLayers(
        context: Context,
        descriptors: List<LayerDescriptor>,
        quality: Float,
        dateRange: LongRange,
        bounds: Bounds? = null,
        zoom: Float = 10f,
    ) {
        val myGeneration = refreshGeneration.incrementAndGet()
        try {
            synchronized(stateLock) {
                if (myGeneration != refreshGeneration.get()) return
                if (currentLayers.isNotEmpty()) {
                    clearCurrentLayers()
                }
                clearState()
                currentQuality = quality
            }

            if (descriptors.isNotEmpty()) {
                val builtDescriptors = mutableListOf<LayerDescriptor>()
                val builtLayers = mutableListOf<BaseMapLayer<*, *>>()
                val legends = mutableListOf<MapLayerData>()
                val configs = mutableListOf<MapLibreLayerConfig>()
                val cacheableConfigs = mutableListOf<Pair<String, MapLibreLayerConfig>>()

                try {
                    descriptors.forEach { descriptor ->
                        val entry = descriptor.recipe.factory.create() as? LayerEntry
                            ?: run {
                                return@forEach
                            }
                        val builtLayer = entry.build(context)
                        legends.add(entry.legend)
                        builtDescriptors.add(descriptor)
                        builtLayers.add(builtLayer)

                        if (builtLayer is SupportsDateRange) {
                            builtLayer.dateRange = dateRange
                        }

                        builtLayer.enable(context, quality, bounds, zoom).join()
                        val config = builtLayer.lastConfig
                        if (config != null) {
                            configs.add(config)
                            cacheableConfigs.add(descriptor.id to config)
                        }
                    }
                } catch (e: CancellationException) {
                    builtLayers.forEach { it.disable() }
                    throw e
                }

                synchronized(stateLock) {
                    if (myGeneration != refreshGeneration.get()) {
                        builtLayers.forEach { it.disable() }
                        return
                    }
                    currentLayerDescriptors = builtDescriptors
                    currentLayers.clear()
                    currentLayers.addAll(builtLayers)
                    currentLegends = legends
                    currentConfig = combinedConfig(configs)
                    val key = cacheKey(bounds, zoom, dateRange, quality)
                    cacheableConfigs.forEach { (layerId, config) ->
                        putCachedConfig(layerId, key, config)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            synchronized(stateLock) {
                if (myGeneration == refreshGeneration.get()) {
                    clearState()
                }
            }
        }
    }

    /**
     * Refresh data for the existing layer instances without clearing sources/layers.
     */
    suspend fun refreshLayersInPlace(
        context: Context,
        bounds: Bounds? = null,
        zoom: Float = 10f,
        dateRange: LongRange,
        forceReload: Boolean = false,
    ): LayerRefreshResult {
        val myGeneration = refreshGeneration.incrementAndGet()
        val (descriptors, layers, quality) = synchronized(stateLock) {
            if (currentLayers.isEmpty()) return LayerRefreshResult.Success
            Triple(currentLayerDescriptors.toList(), currentLayers.toList(), currentQuality)
        }

        try {
            val configs = mutableListOf<MapLibreLayerConfig>()
            descriptors.zip(layers).forEach { (descriptor, layer) ->
                if (myGeneration != refreshGeneration.get()) return LayerRefreshResult.Superseded
                if (layer is SupportsDateRange) {
                    layer.dateRange = dateRange
                }

                val key = cacheKey(bounds, zoom, dateRange, quality)
                val cachedConfig = synchronized(stateLock) {
                    layerConfigCache[descriptor.id]?.takeIf { it.containsKey(key) }?.get(key)
                }
                // Reactive/live refreshes force a reload: the cache is keyed by viewport (not data
                // version), so a cache hit here would return stale data even though new fixes arrived.
                val config = if (!forceReload && cachedConfig != null) {
                    cachedConfig
                } else {
                    val result = layer.reloadData(context, bounds, zoom)
                    if (myGeneration != refreshGeneration.get()) {
                        return LayerRefreshResult.Superseded
                    }
                    when (result) {
                        is LayerReloadResult.Success -> {
                            synchronized(stateLock) {
                                if (myGeneration != refreshGeneration.get()) {
                                    return LayerRefreshResult.Superseded
                                }
                                putCachedConfig(descriptor.id, key, result.config)
                            }
                            result.config
                        }
                        LayerReloadResult.Empty -> null
                        is LayerReloadResult.Failure -> {
                            return LayerRefreshResult.Failure(result.cause)
                        }
                    }
                }
                if (config != null) {
                    configs.add(config)
                }
            }
            synchronized(stateLock) {
                if (myGeneration != refreshGeneration.get()) {
                    return LayerRefreshResult.Superseded
                }
                currentConfig = combinedConfig(configs)
            }
            return LayerRefreshResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return LayerRefreshResult.Failure(e)
        }
    }

    /**
     * Clear current layer.
     */
    fun clear() {
        refreshGeneration.incrementAndGet()
        try {
            synchronized(stateLock) {
                clearCurrentLayers()
                clearState()
            }
        } catch (_: Exception) {
            synchronized(stateLock) {
                clearState()
            }
        }
    }

    /**
     * Get active legend data.
     */
    fun activeLegend(): MapLayerData? = synchronized(stateLock) { currentLegends.firstOrNull() }
    fun activeLegends(): List<MapLayerData> = synchronized(stateLock) { currentLegends.toList() }

    /**
     * Get active layer config produced by the current layer.
     */
    fun activeLayerConfig(): MapLibreLayerConfig? = synchronized(stateLock) { currentConfig }

    /**
     * Handle low memory situations by clearing cache.
     */
    fun onLowMemory() {
        synchronized(stateLock) {
            layerConfigCache.clear()
        }
    }

    /**
     * Clean up resources when controller is no longer needed.
     */
    fun destroy() {
        refreshGeneration.incrementAndGet()
        try {
            synchronized(stateLock) {
                clearCurrentLayers()
            }
        } catch (_: Exception) {
            return
        } finally {
            synchronized(stateLock) {
                clearState()
            }
        }
    }

    private fun clearCurrentLayers() {
        currentLayers.forEach { it.disable() }
    }

    private fun clearState() {
        currentLayerDescriptors = emptyList()
        currentLayers.clear()
        currentLegends = emptyList()
        currentConfig = null
        layerConfigCache.clear()
    }

    private fun combinedConfig(configs: List<MapLibreLayerConfig>): MapLibreLayerConfig? = when (configs.size) {
        0 -> null
        1 -> configs.first()
        else -> MapLibreLayerConfig.Composite(configs.toList())
    }

    private fun cacheKey(
        bounds: Bounds?,
        zoom: Float,
        dateRange: LongRange,
        quality: Float,
    ): ViewportCacheKey = ViewportCacheKey(
        bounds = bounds?.toBucketedBounds(),
        zoom = zoom.toInt(),
        dateFromMs = dateRange.first,
        dateToMs = dateRange.last,
        qualityBucket = (quality * QUALITY_BUCKET_SCALE).toInt(),
    )

    private fun Bounds.toBucketedBounds(): BucketedBounds {
        val latBucket = ((north - south) * 0.1).coerceAtLeast(MIN_BUCKET_DEGREES)
        val lonBucket = (longitudeSpan * 0.1).coerceAtLeast(MIN_BUCKET_DEGREES)
        return BucketedBounds(
            north = bucket(north, latBucket),
            east = bucket(east, lonBucket),
            south = bucket(south, latBucket),
            west = bucket(west, lonBucket),
        )
    }

    private fun bucket(value: Double, size: Double): Long = kotlin.math.floor(value / size).toLong()

    private fun putCachedConfig(layerId: String, key: ViewportCacheKey, config: MapLibreLayerConfig) {
        val cache = layerConfigCache.getOrPut(layerId) {
            ViewportConfigCache(MAX_CACHE_BYTES_PER_LAYER)
        }
        cache.put(key, config)
    }

}
