package com.adsamcik.tracker.map.presentation.bridge

import com.adsamcik.tracker.shared.map.MapLayerData

/**
 * Phase 4: Pure engine interface for layers without leaking GoogleMap to the feature layer.
 * Implementations may use GoogleMap internally but callers only see data.
 */
interface LayerEngine {
    fun selectSingleLayer(id: String?, quality: Float, dateRange: LongRange)
    fun clear()
    fun activeLegend(): MapLayerData?
    fun activeTileProvider(): com.google.android.gms.maps.model.TileProvider?
    /** Declarative overlays for current layer selection (e.g., polylines). */
    fun overlays(): kotlinx.collections.immutable.ImmutableList<com.adsamcik.tracker.map.presentation.udf.MapOverlayState>
}
