package com.adsamcik.tracker.map.ui

import android.content.Context
import android.util.Log
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.shared.map.MapLayerData
import com.adsamcik.tracker.shared.map.layers.LayerDescriptor
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.TileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Controls map layer lifecycle and manages active layer state.
 * Handles layer switching, memory management, and error recovery.
 */
class LayerController {
    private var currentLayerDescriptor: LayerDescriptor? = null
    private var currentLayer: Any? = null // The actual layer instance
    private var currentLegend: MapLayerData? = null
    private var currentTileProvider: TileProvider? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    companion object {
        private const val TAG = "LayerController"
    }
    
    /**
     * Set active layer with proper cleanup and error handling
     */
    fun setLayer(
        context: Context,
        map: GoogleMap,
        descriptor: LayerDescriptor?,
        quality: Float,
        dateRange: LongRange
    ) {
        scope.launch {
            try {
                // Clear current layer first
                currentLayer?.let { layer ->
                    Log.d(TAG, "Clearing current layer: ${currentLayerDescriptor?.id}")
                    clearCurrentLayer()
                }
                
                // Set new layer if provided
                if (descriptor != null) {
                    Log.d(TAG, "Setting new layer: ${descriptor.id}")
                    
                    // Create layer instance
                    val layerInstance = descriptor.recipe.factory.create()
                    currentLayer = layerInstance
                    currentLayerDescriptor = descriptor
                    // TODO: Need to get legend data from layer instance
                    currentLegend = null
                    
                    // Enable the layer
                    if (layerInstance is BaseMapLayer<*, *>) {
                        layerInstance.enable(context, map, quality)
                    }
                    
                    // Set tile provider if available
                    currentTileProvider = if (layerInstance is TileProvider) {
                        layerInstance
                    } else null
                    
                } else {
                    // Clear everything
                    clearState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting layer: ${descriptor?.id}", e)
                Reporter.report(e)
                // Reset to safe state
                clearState()
            }
        }
    }
    
    /**
     * Clear current layer
     */
    fun clear(map: GoogleMap) {
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
     * Get active legend data
     */
    fun activeLegend(): MapLayerData? = currentLegend
    
    /**
     * Get active tile provider
     */
    fun activeTileProvider(): TileProvider? = currentTileProvider
    
    /**
     * Get active layer instance (unsafe - for internal use)
     */
    fun activeLayerUnsafe(): Any? = currentLayer
    
    /**
     * Handle low memory situations by clearing cache
     */
    fun onLowMemory() {
        Log.d(TAG, "Handling low memory - clearing layer cache")
        // Implement cache clearing if needed
    }
    
    /**
     * Clean up resources when controller is no longer needed
     */
    fun destroy() {
        Log.d(TAG, "Destroying LayerController")
        scope.launch {
            try {
                clearCurrentLayer()
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            } finally {
                clearState()
                scope.cancel()
            }
        }
    }
    
    private fun clearCurrentLayer() {
        currentLayer?.let { layer ->
            if (layer is BaseMapLayer<*, *>) {
                layer.disable()
            }
        }
    }
    
    private fun clearState() {
        currentLayerDescriptor = null
        currentLayer = null
        currentLegend = null
        currentTileProvider = null
    }
}
