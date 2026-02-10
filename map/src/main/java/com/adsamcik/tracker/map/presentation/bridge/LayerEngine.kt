package com.adsamcik.tracker.map.presentation.bridge

import com.adsamcik.tracker.map.presentation.udf.MapOverlayState
import com.adsamcik.tracker.shared.map.MapLayerData
import kotlinx.collections.immutable.ImmutableList

/**
 * Pure engine interface for layers. No map SDK types in the contract.
 * Produces [MapLibreLayerConfig] data for rendering and [MapOverlayState] for overlays.
 */
interface LayerEngine {
    suspend fun selectSingleLayer(id: String?, quality: Float, dateRange: LongRange)
    fun clear()
    fun activeLegend(): MapLayerData?
    fun activeLayerConfig(): MapLibreLayerConfig?
    fun overlays(): ImmutableList<MapOverlayState>
    fun destroy() {}
}
