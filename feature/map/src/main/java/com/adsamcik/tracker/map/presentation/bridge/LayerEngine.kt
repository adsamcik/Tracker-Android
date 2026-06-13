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

    /**
     * Summary of recorded speed within [radiusMeters] of the given point, limited to [dateRange].
     * Returns `null` when no speed samples are found. Powers the interactive speed heatmap, where
     * tapping the map reads the average and maximum speed travelled around that location. Default
     * no-op for engines that don't support point queries.
     */
    suspend fun querySpeedSummaryAt(
        lat: Double,
        lng: Double,
        radiusMeters: Double,
        dateRange: LongRange,
    ): SpeedSummary? = null

    fun destroy() {}
}

/**
 * Summary of speed samples around a point. Speeds are in metres per second; [sampleCount] is the
 * number of contributing location fixes.
 */
data class SpeedSummary(
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val sampleCount: Int,
)
