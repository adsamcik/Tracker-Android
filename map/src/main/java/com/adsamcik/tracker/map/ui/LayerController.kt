package com.adsamcik.tracker.map.ui

import android.content.Context
import android.util.Log
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.layers.base.SupportsDateRange
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.shared.map.MapLayerData
import com.adsamcik.tracker.shared.map.layers.LayerDescriptor
import kotlinx.coroutines.CancellationException

/**
 * Controls map layer lifecycle and manages active layer state.
 * Layers now produce [MapLibreLayerConfig] data rather than mutating a map.
 */
class LayerController {
    private var currentLayerDescriptors: List<LayerDescriptor> = emptyList()
    private val currentLayers = mutableListOf<BaseMapLayer<*, *>>()
    private var currentLegends: List<MapLayerData> = emptyList()
    private var currentConfig: MapLibreLayerConfig? = null

    companion object {
        private const val TAG = "LayerController"
    }

    /**
     * Set active layer with proper cleanup and error handling.
     * Suspends until the layer pipeline completes.
     */
    suspend fun setLayer(
        context: Context,
        descriptor: LayerDescriptor?,
        quality: Float,
        dateRange: LongRange
    ) {
        setLayers(context, listOfNotNull(descriptor), quality, dateRange)
    }

    /**
     * Set active layers with proper cleanup and error handling.
     * Suspends until all layer pipelines complete.
     */
    suspend fun setLayers(
        context: Context,
        descriptors: List<LayerDescriptor>,
        quality: Float,
        dateRange: LongRange
    ) {
        try {
            if (currentLayers.isNotEmpty()) {
                Log.d(TAG, "Clearing current layers: ${currentLayerDescriptors.map { it.id }}")
                clearCurrentLayers()
            }

            if (descriptors.isNotEmpty()) {
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
                        builtLayers.add(builtLayer)

                        if (builtLayer is SupportsDateRange) {
                            builtLayer.dateRange = dateRange
                        }

                        builtLayer.enable(context, quality).join()
                        builtLayer.lastConfig?.let(configs::add)
                    }
                } catch (e: CancellationException) {
                    builtLayers.forEach { it.disable() }
                    throw e
                }

                currentLayerDescriptors = descriptors
                currentLayers.clear()
                currentLayers.addAll(builtLayers)
                currentLegends = legends
                currentConfig = when (configs.size) {
                    0 -> null
                    1 -> configs.first()
                    else -> MapLibreLayerConfig.Composite(configs.toList())
                }
            } else {
                clearState()
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
    }
}
