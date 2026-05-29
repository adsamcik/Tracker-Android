package com.adsamcik.tracker.map.ui

import android.content.Context
import android.util.Log
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.layers.base.SupportsDateRange
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.shared.MapLayerData
import com.adsamcik.tracker.map.shared.layers.LayerDescriptor
import kotlinx.coroutines.CancellationException

/**
 * Controls map layer lifecycle and manages active layer state.
 * Layers now produce [MapLibreLayerConfig] data rather than mutating a map.
 */
class LayerController {
    private data class ViewportCacheKey(
        val bounds: BucketedBounds?,
        val zoom: Int,
        val dateFromMs: Long,
        val dateToMs: Long,
        val qualityBucket: Int,
    )

    private data class BucketedBounds(
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
    private val layerConfigCache = mutableMapOf<String, LinkedHashMap<ViewportCacheKey, MapLibreLayerConfig?>>()

    companion object {
        private const val TAG = "LayerController"
        private const val MAX_CACHE_BUCKETS_PER_LAYER = 5
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
        try {
            if (currentLayers.isNotEmpty()) {
                Log.d(TAG, "Clearing current layers: ${currentLayerDescriptors.map { it.id }}")
                clearCurrentLayers()
            }
            clearState()
            currentQuality = quality

            if (descriptors.isNotEmpty()) {
                val builtDescriptors = mutableListOf<LayerDescriptor>()
                val builtLayers = mutableListOf<BaseMapLayer<*, *>>()
                val legends = mutableListOf<MapLayerData>()
                val configs = mutableListOf<MapLibreLayerConfig>()

                try {
                    descriptors.forEach { descriptor ->
                        Log.d(TAG, "Setting layer: ${descriptor.id}")
                        val entry = descriptor.recipe.factory.create() as? LayerEntry
                            ?: run {
                                Log.e(TAG, "Factory for ${descriptor.id} did not produce a LayerEntry")
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
                        }
                        putCachedConfig(descriptor.id, cacheKey(bounds, zoom, dateRange, quality), config)
                    }
                } catch (e: CancellationException) {
                    builtLayers.forEach { it.disable() }
                    throw e
                }

                currentLayerDescriptors = builtDescriptors
                currentLayers.clear()
                currentLayers.addAll(builtLayers)
                currentLegends = legends
                currentConfig = combinedConfig(configs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error setting layers: ${descriptors.joinToString { it.id }}", e)
            Reporter.report(e)
            clearState()
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
    ) {
        if (currentLayers.isEmpty()) return

        try {
            val configs = mutableListOf<MapLibreLayerConfig>()
            currentLayerDescriptors.zip(currentLayers).forEach { (descriptor, layer) ->
                if (layer is SupportsDateRange) {
                    layer.dateRange = dateRange
                }

                val key = cacheKey(bounds, zoom, dateRange, currentQuality)
                val cache = layerConfigCache[descriptor.id]
                val config = if (cache != null && cache.containsKey(key)) {
                    cache[key]
                } else {
                    layer.reloadData(context, bounds, zoom).also { refreshed ->
                        putCachedConfig(descriptor.id, key, refreshed)
                    }
                }
                if (config != null) {
                    configs.add(config)
                }
            }
            currentConfig = combinedConfig(configs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing layers in place: ${currentLayerDescriptors.joinToString { it.id }}", e)
            Reporter.report(e)
        }
    }

    /**
     * Clear current layer.
     */
    fun clear() {
        try {
            clearCurrentLayers()
            clearState()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing layer", e)
            Reporter.report(e)
            clearState()
        }
    }

    /**
     * Get active legend data.
     */
    fun activeLegend(): MapLayerData? = currentLegends.firstOrNull()
    fun activeLegends(): List<MapLayerData> = currentLegends

    /**
     * Get active layer config produced by the current layer.
     */
    fun activeLayerConfig(): MapLibreLayerConfig? = currentConfig

    /**
     * Handle low memory situations by clearing cache.
     */
    fun onLowMemory() {
        Log.d(TAG, "Handling low memory - clearing layer cache")
        layerConfigCache.clear()
    }

    /**
     * Clean up resources when controller is no longer needed.
     */
    fun destroy() {
        Log.d(TAG, "Destroying LayerController")
        try {
            clearCurrentLayers()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        } finally {
            clearState()
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
        val lonBucket = ((east - west) * 0.1).coerceAtLeast(MIN_BUCKET_DEGREES)
        return BucketedBounds(
            north = bucket(north, latBucket),
            east = bucket(east, lonBucket),
            south = bucket(south, latBucket),
            west = bucket(west, lonBucket),
        )
    }

    private fun bucket(value: Double, size: Double): Long = kotlin.math.floor(value / size).toLong()

    private fun putCachedConfig(layerId: String, key: ViewportCacheKey, config: MapLibreLayerConfig?) {
        val cache = layerConfigCache.getOrPut(layerId) {
            object : LinkedHashMap<ViewportCacheKey, MapLibreLayerConfig?>(MAX_CACHE_BUCKETS_PER_LAYER, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ViewportCacheKey, MapLibreLayerConfig?>): Boolean {
                    return size > MAX_CACHE_BUCKETS_PER_LAYER
                }
            }
        }
        cache[key] = config
    }

}
