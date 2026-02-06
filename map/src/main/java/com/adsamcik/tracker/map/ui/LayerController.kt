package com.adsamcik.tracker.map.ui

import android.content.Context
import android.util.Log
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.layers.base.SupportsDateRange
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.shared.map.MapLayerData
import com.adsamcik.tracker.shared.map.layers.LayerDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Controls map layer lifecycle and manages active layer state.
 * Layers now produce [MapLibreLayerConfig] data rather than mutating a map.
 */
class LayerController {
    private var currentLayerDescriptor: LayerDescriptor? = null
    private var currentLayer: BaseMapLayer<*, *>? = null
    private var currentLegend: MapLayerData? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "LayerController"
    }

    /**
     * Set active layer with proper cleanup and error handling.
     */
    fun setLayer(
        context: Context,
        descriptor: LayerDescriptor?,
        quality: Float,
        dateRange: LongRange
    ) {
        scope.launch {
            try {
                currentLayer?.let {
                    Log.d(TAG, "Clearing current layer: ${currentLayerDescriptor?.id}")
                    clearCurrentLayer()
                }

                if (descriptor != null) {
                    Log.d(TAG, "Setting new layer: ${descriptor.id}")
                    val entry = descriptor.recipe.factory.create() as LayerEntry
                    val builtLayer = entry.build(context)
                    currentLegend = entry.legend
                    currentLayer = builtLayer
                    currentLayerDescriptor = descriptor

                    // Set date range if supported
                    if (builtLayer is SupportsDateRange) {
                        builtLayer.dateRange = dateRange
                    }

                    // Enable the layer (no map needed -- layer produces data)
                    builtLayer.enable(context, quality)
                } else {
                    clearState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting layer: ${descriptor?.id}", e)
                Reporter.report(e)
                clearState()
            }
        }
    }

    /**
     * Clear current layer.
     */
    fun clear() {
        scope.launch {
            try {
                clearCurrentLayer()
                clearState()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing layer", e)
                Reporter.report(e)
                clearState()
            }
        }
    }

    /**
     * Get active legend data.
     */
    fun activeLegend(): MapLayerData? = currentLegend

    /**
     * Get active layer config produced by the current layer.
     */
    fun activeLayerConfig(): MapLibreLayerConfig? = currentLayer?.lastConfig

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
            clearCurrentLayer()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        } finally {
            clearState()
            scope.cancel()
        }
    }

    private fun clearCurrentLayer() {
        currentLayer?.disable()
    }

    private fun clearState() {
        currentLayerDescriptor = null
        currentLayer = null
        currentLegend = null
    }
}
