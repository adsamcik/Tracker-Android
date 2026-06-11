package com.adsamcik.tracker.map.presentation.bridge

import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.presentation.udf.MapOverlayState
import com.adsamcik.tracker.map.shared.MapLayerData
import kotlinx.collections.immutable.ImmutableList

/**
 * Pure engine interface for layers. No map SDK types in the contract.
 * Produces [MapLibreLayerConfig] data for rendering and [MapOverlayState] for overlays.
 */
interface LayerEngine {
    suspend fun selectLayers(ids: Set<String>, quality: Float, dateRange: LongRange, bounds: Bounds? = null, zoom: Float = 10f)
    suspend fun refreshLayersInPlace(bounds: Bounds? = null, zoom: Float = 10f, dateRange: LongRange)
    suspend fun selectSingleLayer(id: String?, quality: Float, dateRange: LongRange, bounds: Bounds? = null, zoom: Float = 10f) {
        selectLayers(id?.let(::setOf) ?: emptySet(), quality, dateRange, bounds, zoom)
    }
    fun clear()
    fun activeLegend(): MapLayerData?
    fun activeLayerConfig(): MapLibreLayerConfig?
    fun overlays(): ImmutableList<MapOverlayState>
    fun destroy() {}
}
